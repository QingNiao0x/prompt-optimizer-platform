package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.PlanningKnownDecision;
import com.promptoptimizer.provider.domain.PlanningProviderRequest;
import com.promptoptimizer.template.domain.TaskIntentResolver;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 将当前任务明确委托的现有接口查证交给执行者，不把未读取的契约变成已确认参数。
 * 仅覆盖唯一具名接口的输入／输出问法；多对象、复合决定和无法证明覆盖的表达继续提问。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class ExistingContractLookup {
    private static final Pattern GOAL = Pattern.compile("(?:实现|开发|新增|补充)\\s*([\\p{IsHan}A-Za-z0-9_]{1,24})接口");
    private static final Pattern REUSE = Pattern.compile("(?:^|。)(?:请|本次)?(?:沿用|遵循|复用)(?:项目)?(?:现有|已有|既有)[^。；;\\n]{0,90}(?:契约|接口规范|接口约定)");
    private static final Pattern LOOKUP = Pattern.compile("(?:查阅|核查|查|阅读)(?:项目)?(?:现有|已有|当前)?(?:源码|代码|接口文档)");
    private static final Pattern RESERVED = Pattern.compile(
            "(?:凭据|请求参数|输入参数|响应|返回|认证)[^。；;\\n]{0,30}(?:由我|由用户|让我|尚未确定|待确认|重新选择|重新设计)"
                    + "|(?:由我|由用户|让我)[^。；;\\n]{0,30}(?:凭据|请求参数|响应|返回|认证)"
                    + "|(?:凭据|请求参数|输入参数|响应|返回|认证)[^。；;\\n]{0,20}(?:以后|稍后|之后).{0,6}确认");
    private static final Pattern INDEPENDENT = Pattern.compile(
            "冲突|新增|新建|更改|重新|另外|此外|还需|同时|以及|并且|授权|权限|隐私|泄露|脱敏|生产|迁移|删除"
                    + "|阈值|有效期|缓存|重试|超时|成本|预算|管理员|普通用户|不同角色|\\d");
    private final String subject;
    private final String evidence;
    private final boolean inputContract;
    private final boolean outputContract;

    private ExistingContractLookup(String subject, String evidence) {
        this.subject = subject;
        this.evidence = evidence;
        String reused = java.util.Arrays.stream(evidence.split("[。；;\\n]"))
                .filter(clause -> REUSE.matcher(clause).find()).collect(java.util.stream.Collectors.joining("。"));
        boolean input = reused.matches("(?s).*(?:请求|输入|凭据|入参).*" );
        boolean output = reused.matches("(?s).*(?:响应|返回|输出|出参).*" );
        // 只沿用一个属性时不扩展到另一个；泛指完整契约的明确委托才同时覆盖输入和输出。
        this.inputContract = input || !output;
        this.outputContract = output || !input;
    }

    /** 只读取本次原始指令，不让附件、候选答案、引用或自报 knownDecisions 扩大委托。 */
    static ExistingContractLookup from(PlanningProviderRequest input) {
        String active = currentInstructions(input.rawPrompt());
        if (!REUSE.matcher(active).find() || !LOOKUP.matcher(active).find() || RESERVED.matcher(active).find()) return null;
        // 没有明确沿用与查证指令时立即退出，不让其他领域反复运行工程分类。
        var intent = input.taskView() == null ? TaskIntentResolver.resolve(TemplateCode.AUTO, input.rawPrompt())
                : input.taskView().intent();
        if (!intent.engineeringConstraints()) return null;
        var goals = new ArrayList<String>();
        for (String clause : active.split("[。；;\\n]")) {
            var goal = GOAL.matcher(clause);
            while (goal.find()) {
                // “实现甲接口和乙接口”没有唯一作用对象，不能把甲的查询委托扩到乙。
                if (clause.split("接口", -1).length != 2) return null;
                goals.add(goal.group(1));
            }
        }
        var subjects = goals.stream().distinct().toList();
        if (subjects.size() != 1) return null;
        String subject = subjects.getFirst();
        var clauses = java.util.Arrays.stream(active.split("[。；;\\n]"))
                .filter(clause -> REUSE.matcher(clause).find() || LOOKUP.matcher(clause).find()).toList();
        var lookup = new ExistingContractLookup(subject, String.join("。", clauses));
        // 唯一开发目标仍不能继承另一接口的沿用要求，且不把整份长需求复制到索引。
        if (lookup.evidence.length() > 1200 || clauses.stream().anyMatch(clause -> !lookup.sameInterfaceObjects(clause))) return null;
        return lookup;
    }

    /** 复用既有 LOOKUP 索引渠道；记录原文来源，明确参数尚未核实，不产生用户答案。 */
    PlanningKnownDecision decision() {
        return new PlanningKnownDecision(PlanningKnownDecision.Kind.EXISTING_CONTRACT_LOOKUP,
                subject + "接口的现有" + (inputContract && outputContract ? "输入与输出" : inputContract ? "输入" : "输出") + "契约",
                "执行者应先核查并沿用上述属性的现有契约；实际参数未核实，不默认选值。"
                        + "查证后不存在适用契约或存在冲突时，再澄清影响结果的选择。原始依据：" + evidence,
                List.of("rawPrompt"));
    }

    /** 完整题干限定对象与属性；题干、提示、全部候选及示例中的独立条件都能阻止过滤。 */
    boolean covers(PlanQuestion question) {
        String text = question.question().replaceAll("\\s+", "");
        String target = Pattern.quote(subject);
        String input = target + "接口(?:接受|使用|支持|采用|需要)(?:哪种|哪些|什么)(?:凭据(?:组合)?|请求参数|输入参数)[？?]?";
        String output = target + "(?:成功后)?接口(?:返回什么|返回哪些内容|的响应格式是什么|的响应结构是什么)[？?]?";
        if (!(inputContract && text.matches(input)) && !(outputContract && text.matches(output))) return false;
        var details = new ArrayList<String>();
        details.add(question.question());
        details.add(question.hint());
        details.addAll(question.examples());
        question.options().forEach(option -> {
            details.add(option.label()); details.add(option.description());
            details.add(option.answer()); details.add(option.recommendationReason());
        });
        return details.stream().allMatch(this::withinScope);
    }

    /** 只允许同一契约的候选说明；另一个具名接口不能凭“沿用现有”标签被吞掉。 */
    private boolean withinScope(String detail) {
        if (detail == null || detail.isBlank()) return true;
        if (INDEPENDENT.matcher(detail).find()) return false;
        return sameInterfaceObjects(detail);
    }

    /** 只校验具名接口，不将同一段出现的其他对象归入当前查证范围。 */
    private boolean sameInterfaceObjects(String detail) {
        var named = Pattern.compile("([\\p{IsHan}A-Za-z0-9_]{1,24})接口").matcher(detail);
        while (named.find()) {
            String name = named.group(1);
            if (!name.endsWith(subject) && !name.endsWith(subject + "成功后")
                    && !name.matches("实际|现有|已有|统一|这|该|本次")) return false;
        }
        return true;
    }

    /** 引文、代码和参考章节不授予查证职责；否定和未来条件也不能激活当前委托。 */
    private static String currentInstructions(String raw) {
        var lines = new ArrayList<String>();
        boolean fenced = false;
        boolean reference = false;
        for (String source : (raw == null ? "" : raw).split("\\R")) {
            String line = source.strip();
            if (line.startsWith("```") || line.startsWith("~~~")) { fenced = !fenced; continue; }
            if (fenced || line.startsWith(">") || line.startsWith("\"") || line.startsWith("“")) continue;
            String heading = line.replaceAll("^[#*\\s]+|[*\\s]+$", "");
            if (heading.matches("(?:参考资料|参考|示例|原文|材料|背景)[:：]?")) { reference = true; continue; }
            if (heading.matches("(?:任务|当前任务|需求|目标|执行要求)[:：]?")) { reference = false; continue; }
            if (reference) continue;
            // 引号中的示例或转述不是本次执行指令，不能为同段的真正开发目标补授权。
            line = line.replaceAll("“[^”]*”|\"[^\"]*\"", "");
            for (String clause : line.split("[。；;\\n]")) {
                String current = clause.replaceFirst("^[-*\\d.、\\s]+", "").strip();
                if (current.matches("^(?:请|本次|现在)?(?:不要|禁止|无需|不必|暂不|并非|以后|未来|如果|若|假如|参考|例如|原文).*")) continue;
                lines.add(current);
            }
        }
        return String.join("。", lines);
    }
}
