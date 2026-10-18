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
 * 仅接收完整选项均为包装的题目；有资料、既有契约或未知附加文本时保留，避免吞掉业务决定。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class RoutineCodePackaging {
    private static final Pattern QUESTION = Pattern.compile(
            "^(?:(?:希望|需要)交付什么(?:形态|形式)的成果|(?:代码|本次代码)(?:采用哪种|以什么)(?:交付形式|形式交付))[？?]$");
    private static final Pattern RESERVED_SCOPE = Pattern.compile(
            "由我|由用户|我来决定|让我选择|询问|确认|尚未|待定|未确定|未定|冲突|"
                    + "现有|既有|项目|仓库|兼容|沿用|保持|对接|集成|部署|生产|公开|发布|SDK|接口|插件|嵌入|方案|文档|报告|"
                    + "不|未|无|禁止|仅|只|如果|假如|若|除非|例外|未来|以后");
    private static final Pattern FORM = Pattern.compile("脚本|函数|模块|命令行|\\bCLI\\b");
    private static final Pattern LANGUAGE = Pattern.compile("\\b(?:Python|JavaScript|TypeScript|Java|Kotlin|Rust|Go|CSharp)\\b", Pattern.CASE_INSENSITIVE);
    // 只归一化完整包装文本的副本；否定、条件、数值、权限、网络服务和未识别内容均不能被消去。
    private static final Pattern PACKAGING_WORDS = Pattern.compile(
            "可被其他代码调用的|可直接运行的|可直接运行|可复用|通过命令行|命令行参数|命令行|运行后|单个|独立|一个|"
                    + "脚本|函数|模块|工具|代码|交付|决定是|选择|支持|提供|传入|输入|输出|返回|结果|参数|入口|CLI|"
                    + "还是|或者|的|可|并|或|带|是");
    private static final Pattern OUTPUT_OBJECT = Pattern.compile("(?:输出|返回|打印)([\\p{IsHan}]{2,20})(?=$|[，,。；;])");
    private static final Pattern INPUT_ARGUMENT = Pattern.compile("(?:传入|输入)(?:URL|网址|地址|参数)");

    private RoutineCodePackaging() { }

    /** 不读取旧任务或外部材料；非纯包装、显式用户选择和任何未解释的候选信息均回退为保留。 */
    static boolean delegated(PlanQuestion question, PlanningProviderRequest input) {
        String raw = input.rawPrompt();
        if (TaskDeliveryProfile.identify(raw) != TaskDeliveryProfile.SOFTWARE_IMPLEMENTATION
                || RESERVED_SCOPE.matcher(raw).find() || hasOtherEvidence(input)
                || question.type() != PlanQuestionType.SINGLE_CHOICE || question.options().size() < 2
                || !QUESTION.matcher(question.question()).matches() || !question.examples().isEmpty()
                || question.options().stream().anyMatch(option -> !FORM.matcher(option.label()).find())) return false;
        List<String> details = Stream.concat(Stream.of(question.hint()), question.options().stream()
                .flatMap(option -> Stream.of(option.label(), option.description(), option.answer(), option.recommendationReason())))
                .map(value -> value == null ? "" : value).toList();
        Set<String> languages = languages(String.join(" ", details));
        Set<String> specified = languages(raw);
        if (languages.size() > 1 || !specified.isEmpty() && !specified.containsAll(languages)) return false;
        return details.stream().allMatch(value -> purePackaging(value, raw));
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

    /** 输出对象须逐字来自当前任务；余下只能是纯包装词，不把近义内容或新增业务行为当作等价。 */
    private static boolean purePackaging(String value, String raw) {
        // 仅在原目标明确提取/获取同一对象时接受具名输出；不能因原文提过URL就允许新增输出URL。
        if (OUTPUT_OBJECT.matcher(value).results().anyMatch(match -> !match.group(1).equals("结果")
                && !raw.matches("(?s).*(?:提取|获取|抓取|爬取|查找|读取|解析)[^，,。；;！？!?]*"
                + Pattern.quote(match.group(1)) + "[。.!！]?$"))) return false;
        String remainder = OUTPUT_OBJECT.matcher(value).replaceAll(match ->
                raw.contains(match.group(1)) ? "" : match.group());
        remainder = INPUT_ARGUMENT.matcher(remainder).replaceAll("");
        remainder = LANGUAGE.matcher(remainder).replaceAll("");
        remainder = PACKAGING_WORDS.matcher(remainder).replaceAll("");
        return remainder.replaceAll("[\\s，,。；;、：:]", "").isEmpty();
    }

    /** 语言差异本身是独立选择；包装判断不把不同语言折叠为同一个候选。 */
    private static Set<String> languages(String value) {
        return LANGUAGE.matcher(value).results().map(match -> match.group().toLowerCase(java.util.Locale.ROOT))
                .collect(Collectors.toSet());
    }
}
