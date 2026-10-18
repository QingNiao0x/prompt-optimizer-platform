package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.PlanQuestionType;
import com.promptoptimizer.provider.domain.PlanningProviderRequest;
import com.promptoptimizer.template.domain.TaskDeliveryProfile;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 将独立小型代码任务的纯脚本/函数/模块包装交给实现者，不确认实际参数或选择某个候选。
 * 按全部候选的包装类别及独立影响核对；有资料、既有契约或未识别技术时保留，避免吞掉业务决定。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class RoutineCodePackaging {
    private static final Pattern RESERVED_SCOPE = Pattern.compile(
            "由我|由用户|我来决定|让我选择|询问|确认|尚未|待定|未确定|未定|冲突|"
                    + "现有|既有|项目|仓库|兼容|沿用|保持|对接|集成|部署|生产|公开|发布|SDK|接口|插件|嵌入|方案|文档|报告|"
                    + "不|未|无|禁止|仅|只|如果|假如|若|除非|例外|未来|以后");
    private static final Pattern FORM = Pattern.compile("脚本|函数|模块|命令行|\\bCLI\\b");
    private static final Pattern LANGUAGE = Pattern.compile("\\b(?:Python|JavaScript|TypeScript|Java|Kotlin|Rust|Go|CSharp)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern OUTPUT_OBJECT = Pattern.compile("(?:输出|返回|打印)([\\p{IsHan}]{2,20})(?=$|[，,。；;])");
    // 责任建议不能吞掉业务行为、真实取值、授权、条件或依赖选择；检查题干及全部候选元信息。
    private static final Pattern INDEPENDENT_EFFECT = Pattern.compile(
            "新增|另外|另需|同时还|权限|授权|共享|公开|发布|部署|生产|兼容|对接|集成|数据库|网络|服务端|"
                    + "阈值|时延|超时|重试|吞吐|性能|内存|收费|分母|纳入|排除|排序|顺序|大小写|空白|比较规则|"
                    // “按提示输入”属于命令行交互动作；其余按某口径处理仍作为独立条件保留。
                    + "按(?!提示)|逐|比较|去除|忽略|正则|模糊|加密|脱敏|日志|敏感|返回网址|输出网址|第三方|依赖|"
                    + "字段|分组|格式|编码|类型|路径|数组|文件|精度|取整|小数|几位|单位集合|机构|医院|部门|"
                    + "发送|通知|写入|保存|删除|修改|访问|账户|身份|管理员|内网|外网|额外|"
                    + "如果|假如|若|除非|例外|未来|以后|尚未|待定|未确定|不|未|禁止|仅|只|[<>!=]|\\d",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TECHNICAL_TOKEN = Pattern.compile("[A-Za-z][A-Za-z0-9_.+-]*");
    private static final Pattern LOCAL_PACKAGING_MODAL = Pattern.compile(
            "(?:不(?:提供|交付|实现|做)|仅(?:提供|交付|实现)|只(?:提供|交付|实现))([^，,。；;]+)");

    private RoutineCodePackaging() { }

    /** 不读取旧任务或外部材料；非包装类别、显式用户选择和独立影响均回退为保留。 */
    static boolean delegated(PlanQuestion question, PlanningProviderRequest input) {
        String raw = input.rawPrompt();
        if (TaskDeliveryProfile.identify(raw) != TaskDeliveryProfile.SOFTWARE_IMPLEMENTATION
                || RESERVED_SCOPE.matcher(raw).find() || hasOtherEvidence(input)
                || question.type() != PlanQuestionType.SINGLE_CHOICE || question.options().size() < 2
                || !question.examples().isEmpty()
                || question.options().stream().anyMatch(option -> !FORM.matcher(option.label()).find())) return false;
        List<String> details = Stream.concat(Stream.of(question.hint()), question.options().stream()
                .flatMap(option -> Stream.of(option.label(), option.description(), option.answer(), option.recommendationReason())))
                .map(value -> value == null ? "" : value).toList();
        Set<String> languages = languages(String.join(" ", details));
        Set<String> specified = languages(raw);
        if (languages.size() > 1 || !specified.isEmpty() && !specified.containsAll(languages)) return false;
        // 两种已在原文出现的技术仍可能是未选的独立条件；不能因“原文提过”就把CSV/JSON等差异归为包装。
        var optionTechnologies = question.options().stream().map(option -> technicalTokens(String.join(" ",
                safe(option.label()), safe(option.description()), safe(option.answer())))).distinct().toList();
        if (optionTechnologies.size() != 1) return false;
        // 包装是平台定义的执行职责，不依赖题干模板或模型自报的owner；不选择任一候选答案。
        if (hasIndependentEffect(question.question(), raw) || hasIndependentEffect(question.hint(), raw)) return false;
        return question.options().stream().allMatch(option -> {
            String local = String.join("；", safe(option.label()), safe(option.description()), safe(option.answer()));
            // 选项内“只提供函数、不做CLI”只限定该候选的包装能力；题干/hint和用户原文的否定不归一化。
            String normalized = normalizeLocalPackaging(local, raw);
            String reason = normalizeLocalPackaging(safe(option.recommendationReason()), raw)
                    .replaceAll("(?:只|仅)(?=说明|描述|提及|提到)", "");
            return !hasIndependentEffect(normalized, raw) && !hasIndependentEffect(reason, raw)
                    && sameOutputSubject(local, raw);
        });
    }

    /** 完整元信息中的业务影响和未识别技术不能被包装分类覆盖；当前语言和原题标识保留其来源。 */
    private static boolean hasIndependentEffect(String value, String raw) {
        String text = safe(value);
        if (INDEPENDENT_EFFECT.matcher(LANGUAGE.matcher(text).replaceAll("")).find()) return true;
        return TECHNICAL_TOKEN.matcher(text).results().anyMatch(match -> {
            String token = match.group();
            return !token.equalsIgnoreCase("CLI") && !LANGUAGE.matcher(token).matches()
                    && !Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(token) + "(?![A-Za-z0-9_])", Pattern.CASE_INSENSITIVE)
                        .matcher(raw).find();
        });
    }

    /** 提取完整候选中的非包装技术标识；只用于比较独立条件，不推断技术已经由用户选定。 */
    private static Set<String> technicalTokens(String text) {
        return TECHNICAL_TOKEN.matcher(text).results().map(match -> match.group().toLowerCase(java.util.Locale.ROOT))
                .filter(token -> !token.equals("cli") && !LANGUAGE.matcher(token).matches()).collect(Collectors.toSet());
    }

    /** 模态词只能归一化到有已知包装受词的局部句；机构、数据、条件等未知修饰继续保留。 */
    private static String normalizeLocalPackaging(String text, String raw) {
        var matcher = LOCAL_PACKAGING_MODAL.matcher(text);
        var result = new StringBuilder();
        while (matcher.find()) {
            String target = matcher.group(1);
            String rest = target.replaceAll("可被其他代码调用的|可被调用的|可复用的|可直接运行的", "")
                    .replaceAll("脚本|函数|模块|命令行|CLI|入口|调用|接口|方式|工具|库|类|的|可复用", "").strip();
            boolean packaging = FORM.matcher(target).find() && (rest.isEmpty() || raw.contains(rest))
                    && !hasIndependentEffect(target, raw);
            matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(packaging ? target : matcher.group()));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /** 有材料、历史、已绑定决定或待决来源时沿用既有查证路径，本规则不猜测它们的交付契约。 */
    private static boolean hasOtherEvidence(PlanningProviderRequest input) {
        if (!input.contextDescription().isBlank() || !input.conversationHistory().isEmpty()
                || !input.knownDecisions().isEmpty() || !input.sourceObjectGuidance().isBlank()
                || input.taskView() != null && !input.taskView().pendingDecisions().isEmpty()) return true;
        var context = input.planningContext();
        return context != null && (!context.description().isBlank() || context.analyzedFileCount() > 0
                || !context.technologies().isEmpty() || !context.dependencies().isEmpty() || !context.directoryOverview().isEmpty()
                || !context.fileSummaries().isEmpty() || !context.factCards().isEmpty() || !context.warnings().isEmpty());
    }

    /** 结果映射须仍对应原动作；返回其他对象或格式是独立选择，不因同题提过输入对象而放行。 */
    private static boolean sameOutputSubject(String value, String raw) {
        // 仅在原目标明确提取/获取同一对象时接受具名输出；不能因原文提过URL就允许新增输出URL。
        return OUTPUT_OBJECT.matcher(value).results().allMatch(match -> {
            String object = match.group(1);
            if (object.equals("结果")) return true;
            if (object.endsWith("结果") && object.length() > 2 && raw.contains(object.substring(0, object.length() - 2))) return true;
            return raw.matches("(?s).*(?:提取|获取|抓取|爬取|查找|读取|解析)[^，,。；;！？!?]*"
                    + Pattern.quote(object) + "[。.!！]?$" );
        });
    }

    private static String safe(String text) { return text == null ? "" : text; }

    /** 语言差异本身是独立选择；包装判断不把不同语言折叠为同一个候选。 */
    private static Set<String> languages(String value) {
        return LANGUAGE.matcher(value).results().map(match -> match.group().toLowerCase(java.util.Locale.ROOT))
                .collect(Collectors.toSet());
    }
}
