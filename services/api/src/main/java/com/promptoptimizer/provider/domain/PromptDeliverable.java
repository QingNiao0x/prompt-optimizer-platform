package com.promptoptimizer.provider.domain;

import com.promptoptimizer.enhancement.domain.ConfirmedPlanDecision;
import com.promptoptimizer.template.domain.TaskIntentResolver;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 两条增强链共用的提示词交付依据。只识别可定位的当前动作与直接宾语，无法确认的句式留给正文理解。
 * 这里识别的是下游交付；平台生成的优化提示词本身不能作为交付依据。
 */
public record PromptDeliverable(String evidence) {
    public static final String GUIDANCE = """
            区分当前动作、动作对象、讨论主题和交付物。用户询问、比较或介绍某个流程时，保留实际要回答的问题或原定作品。
            只有原始需求或适用的用户确认明确要求编写、改写或交付提示词时，才把提示词列为下游成果。
            引用、待处理材料、其他人的操作和后续打算不建立本轮交付。明确要求多个当前成果时逐项保留。
            """;

    private static final String LEAD = "(?:(?:请帮我|请你|请|帮我|本轮|本次|最终|然后|接着|并|再|同时|另外|另|还|先|仅|只|需要|希望|务必|要)\\s*){0,8}";
    private static final String PURPOSE = "(?:(?:为|给|用于)[^：:，,。；;!?！？\\r\\n]{1,40}?)?";
    private static final String EDIT = "(?:优化|增强|改写|重写|修改|润色|整理)";
    private static final String CREATE = "(?:准备|提供|生成|编写|撰写|设计|拟定|交付|输出|转写|写(?:出)?|给我|给出)";
    private static final String PROMPT = "提示词(?:文本|文档|模板|草稿)?";
    private static final String END = "(?=\\s*(?:$|[.!！:：]|用于|供(?:我|用户|下游)|以便|来(?:比较|说明|完成|实现)"
            + "|(?:和|及|以及|并附)(?:一|两|另)))";
    private static final String NUMBER = "(?:(?:一|两|三|几|多|[0-9]+)(?:份|条|套|段|个)(?:可直接使用的|可复用的)?|可直接使用的|可复用的)";
    private static final String MODIFIERS = "[^，,。；;!?！？：:\\r\\n]{0,36}?";
    private static final Pattern CHINESE = Pattern.compile("^" + LEAD + PURPOSE + "(?:"
            + CREATE + "\\s*(?:" + NUMBER + MODIFIERS + "|"
            // 无量词时只接直接名词短语，避免“写一篇介绍提示词优化平台的文案”横跨另一交付物。
            + "(?:(?!介绍|比较|讲解|说明|讨论|评估|分析|流程|文案|报告|一篇).){0,28}?"
            + ")|" + EDIT + "\\s*(?:(?:下面|以下|上面|之前|这|那)(?:一)?(?:份|条|段|个)?(?:的)?|"
            + NUMBER + ")?(?:原始|原有|已有|现有)?"
            + ")" + PROMPT + END);
    private static final Pattern ENGLISH = Pattern.compile(
            "(?i)^(?:(?:please|then|also|only|and|finally|now)\\s+)*"
            + "(?:(?:write|create|prepare|provide|generate|deliver|output|draft)\\s+"
            + "(?:(?:me|us)\\s+)?(?:an?\\s+|the\\s+|[0-9]+\\s+)?(?:reusable\\s+|structured\\s+|ready-to-use\\s+)*"
            + "|(?:improve|enhance|rewrite|revise|edit)\\s+(?:this|the|my|our|that)\\s+(?:existing\\s+)?)"
            + "prompts?\\b(?:\\s+(?:templates?|drafts?))?(?=\\s*(?:$|[.!:]|for\\b|to\\b|that\\b|so\\s+that\\b))");
    private static final Pattern QUOTE = Pattern.compile("“[^”]*”|‘[^’]*’|\"[^\"]*\"");
    private static final Pattern PREFIX = Pattern.compile("^\\s*(?:(?:#{1,6}|[-*+]|\\d+[.、)])\\s*)?"
            + "(?:(?:任务|交付物|输出要求|输出)[:：]\\s*)?");
    private static final Pattern PROMPT_LABEL = Pattern.compile("^(?:优化后的|增强后的|优化|增强|结构化)?提示词[:：]$");
    private static final Pattern BARE_ARTIFACT = Pattern.compile("^" + NUMBER + MODIFIERS + PROMPT + END);
    private static final Pattern DEPENDENT = Pattern.compile("(?i)^(?:例如|比如|示例|原文|引用|若|如果|假如|待|以后|之后可能|"
            + "后续|将来|相比|相较|对比|比较|评估|讨论|翻译|不|勿|无需|不要|请勿|if\\b|later\\b|for example\\b|translate\\b|do not\\b)");
    private static final Pattern CONDITIONAL = Pattern.compile(
            "(?i)^" + LEAD + "(?:若|如果|假如|待|以后|之后可能|后续|将来|例如|比如|if\\b|later\\b|for example\\b)");
    private static final Pattern COMPARISON = Pattern.compile("(?i)(?:有什么(?:区别|优势|差异)|有(?:何|哪些)(?:区别|优势|差异)|"
            + "(?:有什么|有何|有哪些)?(?:区别|差异|优劣)(?:是|[?？]|$)|是否比|相较于|相比之下|\\bversus\\b|\\bvs\\.?(?:\\s|$))");
    private static final Pattern CHOICE_PREFIX = Pattern.compile("^(?:请)?(?:本轮|本次|最终)?(?:改成|改为|选择|采用|就要|只要|只需|仅需|只做|仅做|只交付|仅交付)\\s*");
    private static final Pattern EXCLUSIVE_NON_PROMPT = Pattern.compile("^(?:本轮|本次|最终)?(?:改成|改为)?(?:只要|只需|仅需|只做|仅做|只交付|仅交付)"
            + "(?:一份|一篇)?[^，,。；;!?！？]{0,24}(?:稿|文案|报告|正文|说明|分析|比较|答复|方案|提纲|表格|幻灯片|PPT)[。.!！\\s]*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DECLINES_PROMPT = Pattern.compile("(?:不要|不需要|无需|不交付|不输出|不写)"
            + "(?:再)?(?:另行)?(?:生成|编写|提供|交付|输出|写)?(?:一份|单独的)?(?:结构化)?提示词(?:文本)?[。.!！\\s]*$");

    /** 只有适用的交付题实际回答可以新增或收窄确认的交付；题干和项目材料不给出授权。 */
    public static PromptDeliverable from(String raw, List<ConfirmedPlanDecision> decisions) {
        var current = new PromptDeliverable(target(raw));
        for (var decision : decisions == null ? List.<ConfirmedPlanDecision>of() : decisions) {
            if ((decision.scope() == ConfirmedPlanDecision.Scope.TARGET || decision.scope() == ConfirmedPlanDecision.Scope.CHOICE)
                    && TaskIntentResolver.overallDeliveryQuestion(decision.question())) {
                current = current.applyChoice(decision.answer());
            }
        }
        return current;
    }

    /** 定向修改采用用户显式变更，关于某个段落的局部反馈不重新定义整体交付。 */
    public PromptDeliverable revise(String feedback) {
        return CHOICE_PREFIX.matcher(feedback.strip()).find() ? applyChoice(feedback) : this;
    }

    /** 保留已有交付，直到有效回答提供明确的新成果或排除旧成果。 */
    private PromptDeliverable applyChoice(String answer) {
        String choice = answer.strip();
        String approved = target(choice);
        if (approved.isBlank()) {
            String option = CHOICE_PREFIX.matcher(choice).replaceFirst("");
            if (option.matches("(?:一份|一条)?(?:可复用的|结构化|写作)?提示词(?:文本)?[。.!！\\s]*$")) approved = choice;
        }
        if (!approved.isBlank()) return new PromptDeliverable(approved);
        if (EXCLUSIVE_NON_PROMPT.matcher(choice).matches() || DECLINES_PROMPT.matcher(choice).find()
                || TaskIntentResolver.requestsWriting(choice)) return new PromptDeliverable("");
        return this;
    }

    /** 明确交付可以写在任一有效段落；每段单独取证，避免把两段碎片拼成新的动作。 */
    public void validate(String task, String output, String taskField, String outputField) {
        boolean inTask = !target(task).isBlank();
        boolean inOutput = !target(output).isBlank();
        if (evidence.isBlank() && (inTask || inOutput)) {
            throw new ProviderResponseValidationException(ProviderResponseValidationException.Reason.DELIVERY_LEVEL_CONFLICT,
                    inTask ? taskField : outputField);
        }
        if (!evidence.isBlank() && !inTask && !inOutput) {
            throw new ProviderResponseValidationException(ProviderResponseValidationException.Reason.PROMPT_DELIVERY_MISSING, taskField);
        }
    }

    /**
     * 仅识别有明确主句动作和提示词宾语的短句。词序、问句和不可确认的结构不产生硬性约束。
     * 不从整段出现某类成果或主题推导允许额外交付。
     */
    private static String target(String content) {
        String instructions = QUOTE.matcher(DraftDeliveryContract.instructionText(content)).replaceAll("⟪引用⟫");
        for (String sentence : instructions.split("[。；;\\r\\n]")) {
            String beginning = PREFIX.matcher(sentence).replaceFirst("").strip();
            // 问句整段作讨论处理，避免比较对象在逗号前独立命中；明确的此前动作可用分句另行表达。
            if (beginning.endsWith("?") || beginning.endsWith("？") || CONDITIONAL.matcher(beginning).find()) continue;
            for (String part : beginning.split("[，,]|(?<=[^\\s])(?=并(?:请)?(?:编写|撰写|提供|交付|生成|给我))")) {
                String clause = PREFIX.matcher(part).replaceFirst("").strip();
                if (DEPENDENT.matcher(clause).find() || PROMPT_LABEL.matcher(clause).matches()) continue;
                // 比较词出现在“写一份…的提示词”的宾语内部时仍是作品内容，只排除动作之后的比较问法。
                var chinese = CHINESE.matcher(clause);
                if (chinese.find() && !COMPARISON.matcher(clause.substring(chinese.end())).find()) return clause;
                if (ENGLISH.matcher(clause).find() || BARE_ARTIFACT.matcher(clause).find()) return clause;
            }
        }
        return "";
    }
}
