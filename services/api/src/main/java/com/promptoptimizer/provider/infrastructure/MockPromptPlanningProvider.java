package com.promptoptimizer.provider.infrastructure;

import com.promptoptimizer.enhancement.domain.PlanOption;
import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.PlanQuestionType;
import com.promptoptimizer.provider.application.PromptPlanningProvider;
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
        List<PlanQuestion> questions;
        String summary;
        if (containsAny(prompt, "研究", "论文", "死亡率", "发病率", "时间序列", "arriaga", "yll")) {
            questions = researchQuestions(prompt);
            summary = "我已理解你的研究目标。还需要确认研究范围、数据口径和交付方式，之后会直接生成完整提示词。";
        } else if (containsAny(prompt, "开发", "代码", "接口", "功能", "bug", "报错", "重构")) {
            questions = softwareQuestions(prompt, request.contextDescription());
            summary = request.planningContext() == null
                    ? "我已理解你要完成的软件任务。补充下面几个会影响实现方案的细节后，就可以生成最终提示词。"
                    : "我已结合现有项目资料理解这项软件任务。再确认少量无法从文件中确定的细节后，就可以生成最终提示词。";
        } else if (containsAny(prompt, "写作", "文章", "报告", "演讲", "课程", "作业")) {
            questions = writingQuestions(prompt);
            summary = "我已理解你的内容目标。确认受众和交付形式后，就可以生成最终提示词。";
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
            questions.add(freeText(
                    "research-region",
                    "这项研究具体覆盖哪个地区？",
                    "请填写明确的省、市、国家或区域名称。",
                    List.of("广东省", "北京市", "长三角地区")
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
                            option("public", "公开数据库", "从公开平台下载的数据表", "使用公开数据库下载的结构化数据，并在结果中注明来源。", false)
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
                            option("source", "沿用数据源分类", "保持原始数据库中的亚类口径", "沿用数据源已有的疾病亚类分类，同时说明各类别定义。", false)
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
                            option("age", "年龄组", "例如 0–14、15–44、45–64、65 岁及以上", "按年龄组进行分层比较，并在分析前明确年龄分组边界。", false),
                            option("urban-rural", "城乡", "比较城市与农村人群", "按城乡属性进行分层比较。", false),
                            option("occupation", "职业", "数据包含职业字段时使用", "在数据字段允许时按职业类别进行分层比较。", false)
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
                            option("spss", "SPSS", "适合菜单操作和常规统计", "使用 SPSS 完成可支持的统计分析，并说明 Arriaga 分解所需的补充实现。", false)
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
                            option("none", "不需要代码", "只提供研究设计和方法说明", "不提供代码，重点给出研究设计、统计方法、表格与图表方案。", false)
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
            questions.add(freeText(
                    "software-environment",
                    "这项任务要在哪个项目或技术环境中实现？",
                    "例如现有系统、语言、框架和数据库。",
                    List.of("Spring Boot 3 + PostgreSQL", "Vue 3 + TypeScript", "Python + FastAPI")
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
                            option("session", "服务端 Session", "由服务端保存登录状态", "采用服务端 Session，并说明 Cookie、安全属性和过期策略。", false)
                    ),
                    true
            ));
        }
        if (!containsAny(prompt, "验收", "完成标准", "测试通过", "预期结果")) {
            questions.add(freeText(
                    "software-done",
                    "达到什么结果时，你会认为这项任务已经完成？",
                    "填写最关键的可验证结果即可。",
                    List.of("接口正常返回并覆盖异常场景", "现有测试全部通过且新增回归测试", "页面交互与设计稿一致")
            ));
        }
        return questions;
    }

    private List<PlanQuestion> writingQuestions(String prompt) {
        List<PlanQuestion> questions = new ArrayList<>();
        if (!containsAny(prompt, "读者", "受众", "面向")) {
            questions.add(freeText("writing-audience", "这份内容主要写给谁看？", "受众会影响术语密度、语气和解释深度。", List.of("本科生", "行业从业者", "管理层")));
        }
        if (!containsAny(prompt, "字数", "页", "大纲", "markdown", "演示稿", "格式")) {
            questions.add(freeText("writing-format", "你需要什么形式和篇幅的成品？", "例如文章、报告、大纲或演示稿。", List.of("3000 字分析报告", "10 页演示稿大纲", "Markdown 文章")));
        }
        return questions;
    }

    private List<PlanQuestion> generalQuestions(String prompt) {
        List<PlanQuestion> questions = new ArrayList<>();
        if (prompt.length() < 80) {
            questions.add(freeText("general-goal", "你最希望最终结果帮助你完成什么？", "描述使用场景和希望解决的问题。", List.of("做出选择", "完成一份可提交的作业", "制定可执行方案")));
        }
        if (!containsAny(prompt, "输出", "格式", "报告", "表格", "清单", "方案")) {
            questions.add(freeText("general-deliverable", "你希望最终得到什么形式的结果？", "例如方案、报告、表格、讲解或可执行步骤。", List.of("分步骤方案", "带依据的分析报告", "可直接复制的表格")));
        }
        return questions;
    }

    private PlanQuestion freeText(String id, String question, String hint, List<String> examples) {
        return new PlanQuestion(id, question, hint, PlanQuestionType.FREE_TEXT, List.of(), examples, true);
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
