package com.promptoptimizer.provider.infrastructure;

import com.promptoptimizer.enhancement.domain.PlanOption;
import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.PlanQuestionType;
import com.promptoptimizer.provider.service.PromptPlanningProvider;
import com.promptoptimizer.provider.domain.PlanningProviderRequest;
import com.promptoptimizer.provider.domain.PlanningProviderResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 本地联调使用的确定性计划 Provider。
 *
 * <p>真实跨行业语义追问由模型 Provider 完成；Mock 只覆盖科研、软件、写作和通用任务的代表性路径。</p>
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Component
@ConditionalOnProperty(prefix = "app.provider", name = "mode", havingValue = "mock", matchIfMissing = true)
public class MockPromptPlanningProvider implements PromptPlanningProvider {

    private static final Pattern CONCRETE_REGION = Pattern.compile(
            "(?<!某)[\\p{IsHan}A-Za-z]{1,20}(?:省|市|自治区|特别行政区|国家)|长三角|珠三角|京津冀|全国|中国"
    );

    @Override
    public PlanningProviderResponse plan(PlanningProviderRequest request) {
        StringBuilder input = new StringBuilder(request.rawPrompt())
                .append(' ')
                .append(request.contextDescription());
        request.conversationHistory().forEach(message -> input.append(' ').append(message.content()));
        if (request.planningContext() != null) {
            input.append(' ').append(request.planningContext().description());
            request.planningContext().technologies().forEach(value -> input.append(' ').append(value));
            request.planningContext().dependencies().forEach(value -> input.append(' ').append(value));
            request.planningContext().directoryOverview().forEach(value -> input.append(' ').append(value));
            request.planningContext().fileSummaries().forEach(value -> input.append(' ').append(value));
        }
        String prompt = input.toString().toLowerCase(Locale.ROOT);
        String task = request.rawPrompt().toLowerCase(Locale.ROOT);
        boolean researchGoal = containsAny(task, "研究", "论文", "死亡率", "发病率", "时间序列", "arriaga", "yll");
        boolean explicitSoftwareGoal = containsAny(task, "开发", "接口", "bug", "报错", "重构", "修复", "登录");
        List<PlanQuestion> questions;
        String summary;
        if (explicitSoftwareGoal || (!researchGoal && containsAny(task, "代码", "功能"))) {
            questions = softwareQuestions(prompt, request.contextDescription());
            summary = request.planningContext() == null
                    ? "我已理解你要完成的软件任务。补充下面几个会影响实现方案的细节后，就可以生成最终提示词。"
                    : "我已结合现有项目资料理解这项软件任务。再确认少量无法从文件中确定的细节后，就可以生成最终提示词。";
        } else if (researchGoal) {
            questions = researchQuestions(prompt);
            summary = "我已理解你的研究目标。还需要确认研究范围、数据口径和交付方式，之后会直接生成完整提示词。";
        } else if (containsAny(task, "写作", "文章", "报告", "演讲", "课程", "作业")) {
            questions = writingQuestions(prompt);
            summary = "我已理解你的内容目标。确认受众和交付形式后，就可以生成最终提示词。";
        } else if (containsAny(prompt, "研究", "论文", "死亡率", "发病率", "时间序列", "arriaga", "yll")) {
            questions = researchQuestions(prompt);
            summary = "我已结合上下文理解你的研究目标。请确认仍缺失的关键口径。";
        } else if (containsAny(prompt, "开发", "代码", "接口", "功能", "bug", "报错", "重构")) {
            questions = softwareQuestions(prompt, request.contextDescription());
            summary = "我已结合上下文理解你的软件任务。请确认仍缺失的关键选择。";
        } else {
            questions = generalQuestions(prompt);
            summary = "我已理解你的主要目标。回答下面几个关键问题后，就可以生成最终提示词。";
        }
        return new PlanningProviderResponse(
                summary,
                questions.stream().limit(8).toList(),
                "mock",
                "deterministic-planner-v2",
                true
        );
    }

    /** 仅对研究需求中未明确的地区、数据、分组和工具提出确定性示例问题。 */
    private List<PlanQuestion> researchQuestions(String prompt) {
        List<PlanQuestion> questions = new ArrayList<>();
        if (!hasConcreteRegion(prompt)) {
            questions.add(singleChoice(
                    "research-region",
                    "这项研究具体覆盖哪个地区？",
                    "请选择明确范围；没有写明时不要猜一个地名。",
                    List.of(
                            option("pending", "未写明的地区标为待确认", "不另猜省或市", "地区以原始需求里已经写明的范围为限；没写明的地区在结果中标为待确认。", true),
                            option("guangdong", "广东省", "以广东省为研究范围", "研究范围定为广东省。", false),
                            option("beijing", "北京市", "以北京市为研究范围", "研究范围定为北京市。", false),
                            option("yangtze", "长三角地区", "覆盖上海、江苏、浙江、安徽", "研究范围定为长三角地区。", false)
                    ),
                    true
            ));
        }
        if (!containsAny(prompt, "疾控", "死因登记", "医院", "数据库", "csv", "excel", "xlsx")) {
            questions.add(singleChoice(
                    "research-data",
                    "你将使用什么数据来源和文件格式？",
                    "数据来源和字段结构会直接影响清洗、质量控制和统计方法。",
                    List.of(
                            option("registry", "疾控或死因登记数据", "CSV 或 Excel 数据表", "使用疾控中心或死因登记系统导出的 CSV/Excel 数据。", false),
                            option("hospital", "医院记录", "医院病案或死亡记录", "使用医院病案或死亡记录，文件格式为 CSV/Excel。", false),
                            option("public", "公开数据库", "从公开平台下载的数据表", "使用公开数据库下载的结构化数据，并在结果中注明来源。", false),
                            option("yearbook", "统计年鉴或公报", "政府公开的汇总数据", "使用统计年鉴或政府公报中的汇总数据，并注明年份和口径。", false)
                    ),
                    true
            ));
        }
        if (containsAny(prompt, "亚类", "亚型") && !containsAny(prompt, "icd", "缺血性心脏病", "脑血管病", "高血压性心脏病")) {
            questions.add(singleChoice(
                    "research-disease-groups",
                    "心脑血管疾病亚类按什么标准划分？",
                    "请选择已有口径；如果数据有自己的分类，请直接填写。",
                    List.of(
                            option("icd", "按 ICD 编码分组", "依据数据版本使用对应 ICD 编码", "按数据所采用的 ICD 版本划分疾病亚类，并列出每个亚类的编码范围。", true),
                            option("source", "沿用数据源分类", "保持原始数据库中的亚类口径", "沿用数据源已有的疾病亚类分类，同时说明各类别定义。", false),
                            option("broad", "只分两大类", "心脑血管各作为一类", "只把心脑血管疾病分成心血管和脑血管两大类，不展开亚类。", false),
                            option("diagnosis", "按诊断名称", "使用病案里的诊断文本", "按医院诊断名称分组，并说明归并规则。", false)
                    ),
                    true
            ));
        }
        if (containsAny(prompt, "分人群", "人群比较", "人群") && !containsAny(prompt, "年龄组", "城乡", "职业", "教育程度")) {
            questions.add(multipleChoice(
                    "research-population-groups",
                    "除性别和地区外，还需要按哪些人群特征分组？",
                    "可多选；具体分组边界也可以在自定义回答中补充。",
                    List.of(
                            option("age", "年龄组", "例如 0–14、15–44、45–64、65 岁及以上", "按年龄组进行分层比较，并在分析前明确年龄分组边界。", true),
                            option("urban-rural", "城乡", "比较城市与农村人群", "按城乡属性进行分层比较。", false),
                            option("occupation", "职业", "数据包含职业字段时使用", "在数据字段允许时按职业类别进行分层比较。", false),
                            option("education", "教育程度", "数据包含教育字段时使用", "在数据字段允许时按教育程度进行分层比较。", false)
                    ),
                    true
            ));
        }
        if (!containsAny(prompt, "python", "r语言", " r ", "spss", "stata", "sas")) {
            questions.add(singleChoice(
                    "research-tool",
                    "你希望使用哪种分析工具？",
                    "系统会据此调整方法说明、代码和图表实现。",
                    List.of(
                            option("r", "R", "适合流行病学统计和可复现报告", "使用 R 完成数据处理、统计分析、Arriaga 分解和图表绘制。", true),
                            option("python", "Python", "适合数据处理和自动化分析", "使用 Python 完成数据处理、统计分析、Arriaga 分解和图表绘制。", false),
                            option("spss", "SPSS", "适合菜单操作和常规统计", "使用 SPSS 完成可支持的统计分析，并说明 Arriaga 分解所需的补充实现。", false),
                            option("stata", "Stata", "适合队列和生存分析", "使用 Stata 完成数据处理、统计分析和图表。", false)
                    ),
                    true
            ));
        }
        if (!containsAny(prompt, "提供代码", "代码实现", "完整代码", "无需代码", "不需要代码", "脚本")) {
            questions.add(singleChoice(
                    "research-code",
                    "最终结果是否需要包含可运行的代码？",
                    "代码可以覆盖数据清洗、指标计算、分解分析和可视化。",
                    List.of(
                            option("full", "需要完整代码", "从数据导入到结果输出", "提供可运行的完整代码，包括数据校验、清洗、分析、图表和结果导出。", true),
                            option("core", "只要关键代码", "聚焦核心计算与分解方法", "提供关键计算和 Arriaga 分解代码，并说明其余处理步骤。", false),
                            option("none", "不需要代码", "只提供研究设计和方法说明", "不提供代码，重点给出研究设计、统计方法、表格与图表方案。", false),
                            option("pseudo", "只要步骤和伪代码", "不绑定某一种语言", "给出可核对的计算步骤和伪代码，不绑定具体统计软件。", false)
                    ),
                    false
            ));
        }
        return questions;
    }

    /** 结合项目描述补齐软件任务的环境、认证方式与完成标准问题。 */
    private List<PlanQuestion> softwareQuestions(String prompt, String contextDescription) {
        List<PlanQuestion> questions = new ArrayList<>();
        if (contextDescription.isBlank() && !containsAny(prompt, "java", "spring", "vue", "react", "python", "go", "rust", "node")) {
            questions.add(singleChoice(
                    "software-environment",
                    "这项任务要在哪个项目或技术环境中实现？",
                    "没有项目材料时，不预设一个框架。",
                    List.of(
                            option("unspecified", "先不预设框架", "实现前说明现有技术栈", "先说明现有语言、框架和数据库，再实现；没有材料时不预设框架。", true),
                            option("spring", "Spring Boot 3 + PostgreSQL", "Java 服务端", "在 Spring Boot 3 和 PostgreSQL 中实现。", false),
                            option("vue", "Vue 3 + TypeScript", "浏览器前端", "在 Vue 3 和 TypeScript 中实现。", false),
                            option("python", "Python + FastAPI", "Python 服务端", "在 Python 和 FastAPI 中实现。", false)
                    ),
                    true
            ));
        }
        if (prompt.contains("登录") && !containsAny(prompt, "jwt", "session", "cookie", "oauth", "单点登录")) {
            questions.add(singleChoice(
                    "software-login-mode",
                    "登录成功后采用哪种身份保持方式？",
                    "如果项目已有认证方式，优先沿用现有实现。",
                    List.of(
                            option("existing", "沿用项目现有方式", "先检查现有认证代码", "先检查并沿用项目现有的认证与会话机制；不存在时再提出最小方案。", true),
                            option("jwt", "JWT", "使用访问令牌和刷新令牌", "采用 JWT，包含访问令牌、刷新令牌、退出和失效处理。", false),
                            option("session", "服务端 Session", "由服务端保存登录状态", "采用服务端 Session，并说明 Cookie、安全属性和过期策略。", false),
                            option("oauth", "外部登录", "接入已有的身份提供方", "接入项目已有的外部登录，不另行发明一套账号体系。", false)
                    ),
                    true
            ));
        }
        if (!containsAny(prompt, "验收", "完成标准", "测试通过", "预期结果")) {
            questions.add(singleChoice(
                    "software-done",
                    "达到什么结果时，你会认为这项任务已经完成？",
                    "选择一个可以核对的完成标准。",
                    List.of(
                            option("behavior", "现有行为仍可用", "并补上本次需求的验证", "现有功能保持可用，同时为本次需求补上可核对的验证。", true),
                            option("api", "接口覆盖异常", "正常返回和失败都能核对", "接口正常返回，并覆盖主要异常场景。", false),
                            option("tests", "测试通过", "原有测试加本次回归", "现有测试全部通过，并为本次改动增加回归测试。", false),
                            option("ui", "界面与设计一致", "按现有页面核对交互", "页面交互与现有设计一致。", false)
                    ),
                    true
            ));
        }
        return questions;
    }

    private List<PlanQuestion> writingQuestions(String prompt) {
        List<PlanQuestion> questions = new ArrayList<>();
        if (!containsAny(prompt, "读者", "受众", "面向")) {
            questions.add(singleChoice(
                    "writing-audience",
                    "这份内容主要写给谁看？",
                    "受众会影响术语密度、语气和解释深度。",
                    List.of(
                            option("unspecified-reader", "读者尚未写明", "先服务能完成该任务的人", "读者以原始需求里写明的对象为准；没写明时先写给能完成该任务的人，并少用未解释的术语。", true),
                            option("student", "本科生", "术语配简短解释", "主要写给本科生，术语配简短解释。", false),
                            option("practitioner", "行业从业者", "可以保留专业表述", "主要写给行业从业者，保留完成工作所需的专业表述。", false),
                            option("manager", "管理层", "结论先于过程", "主要写给管理层，先给结论和取舍，再补充依据。", false)
                    ),
                    true
            ));
        }
        if (!containsAny(prompt, "字数", "页", "大纲", "markdown", "演示稿", "格式")) {
            questions.add(singleChoice(
                    "writing-format",
                    "你需要什么形式和篇幅的成品？",
                    "形式会改变结构和详略。",
                    List.of(
                            option("steps", "可执行步骤", "篇幅按内容需要", "先给可执行步骤，篇幅按内容需要，不预先凑字数。", true),
                            option("report", "3000 字分析报告", "完整文章", "交付约 3000 字的分析报告。", false),
                            option("deck", "10 页演示稿大纲", "适合讲述", "交付约 10 页的演示稿大纲。", false),
                            option("markdown", "Markdown 文章", "便于继续修改", "交付一篇 Markdown 文章。", false)
                    ),
                    true
            ));
        }
        return questions;
    }

    private List<PlanQuestion> generalQuestions(String prompt) {
        List<PlanQuestion> questions = new ArrayList<>();
        if (prompt.length() < 80) {
            questions.add(singleChoice(
                    "general-goal",
                    "你最希望最终结果帮助你完成什么？",
                    "这会决定结果的详略和形态。",
                    List.of(
                            option("decide", "做出选择", "比较后给出取舍", "最终结果帮助你在可行做法中做出选择，并写明取舍。", true),
                            option("homework", "完成可提交的作业", "符合作业要求", "最终结果是一份可提交的作业。", false),
                            option("plan", "制定可执行方案", "能按步骤做", "最终结果是一份可执行方案。", false),
                            option("explain", "把问题讲清楚", "先解释再给做法", "最终结果先把问题讲清楚，再给出做法。", false)
                    ),
                    true
            ));
        }
        if (!containsAny(prompt, "输出", "格式", "报告", "表格", "清单", "方案")) {
            questions.add(singleChoice(
                    "general-deliverable",
                    "你希望最终得到什么形式的结果？",
                    "形式会改变提示词的输出要求。",
                    List.of(
                            option("steps-out", "分步骤方案", "可以按顺序执行", "交付按顺序执行的分步骤方案。", true),
                            option("report-out", "带依据的分析报告", "结论和依据分开", "交付带依据的分析报告。", false),
                            option("table-out", "可直接复制的表格", "适合对照", "交付可直接复制的表格。", false),
                            option("brief-out", "口头讲解提纲", "适合转述", "交付一份口头讲解提纲。", false)
                    ),
                    true
            ));
        }
        return questions;
    }

    private PlanQuestion singleChoice(
            String id,
            String question,
            String hint,
            List<PlanOption> options,
            boolean custom
    ) {
        return new PlanQuestion(id, question, hint, PlanQuestionType.SINGLE_CHOICE, options, List.of(), custom);
    }

    private PlanQuestion multipleChoice(
            String id,
            String question,
            String hint,
            List<PlanOption> options,
            boolean custom
    ) {
        return new PlanQuestion(id, question, hint, PlanQuestionType.MULTIPLE_CHOICE, options, List.of(), custom);
    }

    private PlanOption option(
            String id,
            String label,
            String description,
            String answer,
            boolean recommended
    ) {
        return new PlanOption(id, label, description, answer, recommended);
    }

    private boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasConcreteRegion(String value) {
        return CONCRETE_REGION.matcher(value).find();
    }
}
