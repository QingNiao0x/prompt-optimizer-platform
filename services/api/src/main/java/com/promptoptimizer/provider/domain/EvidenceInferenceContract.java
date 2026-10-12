package com.promptoptimizer.provider.domain;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.EffectiveTaskView;
import com.promptoptimizer.template.domain.TaskDeliveryProfile;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 把事实、直接计算与待验证解释分开；相同契约随模型输入及复制正文交付。
 * 收入跨指标校验只覆盖已复现的高风险断言，不把词面匹配宣称为通用事实核验。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record EvidenceInferenceContract(boolean revenueAnalysis, String evidence) {
    public static final String MODEL_GUIDANCE = """
            【推断依据】
            逐项区分资料直接给出的事实、按明确公式可复算的结果、有条件的专业候选与尚不能判断的结论。
            单一指标的变化不证明其他指标或因果机制；没有反面证据不等于已经证明其未下降。
            每个判断只采用同一对象、期间和口径的实际依据。解释候选须在该句先写适用条件或待核查状态，列出能区分候选的证据；不能先作肯定断言，再在文末统称“存在假设”。
            条件成立也只支持它实际蕴含的结论：销量增加不单独证明需求拉动，售价提高不单独证明定价能力；列出判别依据，不把一种可能机制说成唯一原因。没有要求数值演示时，不虚构额外数值来支撑解释。
            没有支持时，在相应约束简要说明“无法从现有资料推断”及所需证据，仍完成已有依据支持的原定任务；不把核查清单变成开始工作前的问卷。
            """;
    private static final Pattern REVENUE = Pattern.compile("收入|营收|revenue", Pattern.CASE_INSENSITIVE);
    private static final Pattern TARGET = Pattern.compile("市场份额|需求(?:端)?|market share|demand", Pattern.CASE_INSENSITIVE);
    private static final Pattern CLAIM = Pattern.compile("(?:市场份额|需求(?:端)?)(?:或需求端)?[^。；;!?！？\\n]{0,18}(?:未出现.{0,6}萎缩|没有下降|未下降|未萎缩|提升|扩大|改善|强劲)|(?:market share|demand)[^.!?\\n]{0,24}(?:did not decline|has not declined|increased|improved)", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUALIFIER = Pattern.compile("不得|不能|无法|不代表|不意味着|并不|不要|禁止|是否|若|如果|可能|待核查|待验证|假设|例如|示例|if\\b|whether|cannot|may\\b|must not", Pattern.CASE_INSENSITIVE);

    /** 只读取本次安全上下文和绑定事实；译文及纯材料转写不增加经营分析。 */
    public static EvidenceInferenceContract from(EffectiveTaskView view, ContextSnapshot context) {
        String facts = view.effectivePrompt() + "\n" + view.executionRequirements().stream()
                .map(EffectiveTaskView.ExecutionRequirement::statement).collect(Collectors.joining("\n"))
                + "\n" + view.facts().stream().map(fact -> fact.evidence()).collect(Collectors.joining("\n"));
        if (context != null) facts += "\n" + context.customDescription() + "\n" + context.fileSnippets().stream()
                .map(file -> file.content() + "\n" + file.summary()).collect(Collectors.joining("\n"));
        boolean analytical = view.intent().deliveryProfile() != TaskDeliveryProfile.TRANSLATION
                && view.effectivePrompt().matches("(?is).*(?:分析|判断|解读|投资|经营|analy[sz]|assess|interpret).*" );
        return new EvidenceInferenceContract(analytical && REVENUE.matcher(facts).find(), facts);
    }

    /** 条件式边界不否定材料中独立存在的证据，也不新增需要用户先回答的问题。 */
    public String guidance() {
        return revenueAnalysis ? "收入及其增速只能直接说明收入变化。若资料只有收入，无法从现有资料推断市场份额、需求或销量走势，亦不能断定增长的原因；判断份额需同口径的同期市场总量，判断需求需销量、价格、订单等独立依据。已有独立证据时分别引用；候选解释在对应句标明条件与判别证据，不用文末免责声明替代。销量或价格变化也不单独证明需求拉动、定价能力或持续性。同一币种不等于已排除汇率影响；须有恒定汇率调整或外币业务等相应证据才能判断。未要求数值演示时，不虚构额外数值作解释依据。先完成可支持的分析。" : "";
    }

    /** 对已经复现的无证据断言拒绝交付；尾段免责声明不能抹掉前句的事实性错误。 */
    public void validate(String text, String field) {
        if (!revenueAnalysis || text == null) return;
        for (String sentence : text.split("[。；;!?！？\\n]")) {
            var claim = CLAIM.matcher(sentence);
            while (claim.find()) {
                String prefix = sentence.substring(0, claim.end());
                if (QUALIFIER.matcher(prefix).find()) continue;
                String target = TARGET.matcher(claim.group()).results().findFirst().map(match -> match.group()).orElse("");
                if (!hasIndependentEvidence(target)) throw new ProviderResponseValidationException(
                        ProviderResponseValidationException.Reason.UNSUPPORTED_INFERENCE, field);
            }
        }
    }

    /** 只允许具名指标自己的肯定资料；未提供、否定、条件、问题和另一指标不能代证。 */
    private boolean hasIndependentEvidence(String target) {
        for (String clause : evidence.split("[。；;!?！？\\n]")) {
            if (!clause.contains(target) || QUALIFIER.matcher(clause).find()
                    || clause.matches(".*(?:未提供|没有提供|未说明|缺少|未知|收入.{0,18}(?:说明|证明|推断)).*")) continue;
            if (clause.matches(".*(?:[0-9].*(?:%|％)|调查显示|调查表明|报告显示).*")) return true;
        }
        return false;
    }
}
