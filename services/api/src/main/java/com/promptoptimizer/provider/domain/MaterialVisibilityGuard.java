package com.promptoptimizer.provider.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 候选43的复制正文边界：待确认留在内部记录，补充必须能对应本次可见对象。
 * 只拒绝能定位的越界写法，不按职业或题目增加禁令，也不改写已经通过的正文。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class MaterialVisibilityGuard {
    /** 这些说法只有用户原句或真实题答里已经出现时，才可以进入要复制的正文。 */
    private static final List<String> CHECKLIST_MARKERS = List.of(
            "尚未确定", "请先补充", "请补充", "待确认", "资料不足", "未提供资料", "统计口径");
    private MaterialVisibilityGuard() { }

    /** 非43版本保持各自冻结契约；43在覆盖校验前执行，失败可走一次协议修复。 */
    public static void enforce(NaturalPromptInput input, NaturalTaskUnderstanding task, String body) {
        if (!NaturalPromptGroundedCompletionInstructions.VERSION.equals(input.strategyVersion())) return;
        if (task == null || body == null || body.isBlank()) throw ungrounded();
        String userText = userText(input);
        for (NaturalTaskUnderstanding.OpenDecision decision : task.openDecisions()) {
            // 模型改写出来的待确认不能靠出现在正文里变成用户要求；用户原句本身含有的未知仍可保留。
            if (decision.issue() != null && body.contains(decision.issue()) && !userText.contains(decision.issue())) {
                throw ungrounded();
            }
        }
        MaterialVisibility visibility = MaterialVisibility.from(input);
        if (visibility.requestMaterials() == MaterialVisibility.State.EXECUTION_ONLY && checklistDominates(body, userText)) {
            throw ungrounded();
        }
        for (NaturalTaskUnderstanding.Addition addition : task.additions()) {
            if (!citesVisibleObject(input, addition.text())) throw ungrounded();
        }
    }

    /** 首句被清单占据，或全文反复出现用户没写过的核对套话，都算待确认进入了正文主体。 */
    private static boolean checklistDominates(String body, String userText) {
        String lead = body.split("。", 2)[0];
        int hits = 0;
        for (String marker : CHECKLIST_MARKERS) {
            if (userText.contains(marker)) continue;
            if (lead.contains(marker)) return true;
            if (body.contains(marker)) hits++;
        }
        return hits >= 2;
    }

    /** 补充至少复用原任务或本次可见材料中的连续两字对象，不能只写一套与对象无关的核对规则。 */
    private static boolean citesVisibleObject(NaturalPromptInput input, String addition) {
        if (addition == null || addition.isBlank()) return false;
        for (NaturalTaskSource source : input.taskSources()) {
            if (source.kind() == NaturalTaskSource.Kind.ASSISTANT_CONVERSATION || source.text() == null) continue;
            String compact = source.text().replaceAll("[^\\p{IsHan}A-Za-z0-9]", "");
            for (int index = 0; index + 2 <= compact.length(); index++) {
                if (addition.contains(compact.substring(index, index + 2))) return true;
            }
        }
        return false;
    }

    private static String userText(NaturalPromptInput input) {
        List<String> parts = new ArrayList<>();
        for (NaturalTaskSource source : input.taskSources()) {
            if (source.kind() == NaturalTaskSource.Kind.ORIGINAL_REQUEST
                    || source.kind() == NaturalTaskSource.Kind.USER_CONVERSATION
                    || source.kind() == NaturalTaskSource.Kind.BOUND_PLAN_ANSWER) {
                parts.add(source.text());
            }
        }
        return String.join("\n", parts);
    }

    private static UngroundedBody ungrounded() { return new UngroundedBody(); }

    /** 修复提示只说明契约，不回传题答、正文或来源原句。 */
    public static final class UngroundedBody extends ProviderException {
        private UngroundedBody() {
            super(ProviderFailureType.INVALID_RESPONSE, "优化正文把未见资料或待确认写成了用户必须先补充的内容", false);
        }

        /** 一次修复仍须返回完整协议；不能靠删掉原要求或真实题答来逃避。 */
        public String repairInstruction() {
            return "\noptimizedPrompt只能写原任务的对象、处理方式和原定结果。materialVisibility为EXECUTION_ONLY时，"
                    + "不要把未见材料写成尚未确定或请用户补充。openDecisions留在内部记录，除非该issue就是用户原句或真实题答。"
                    + "每项additions必须复用原任务或本次可见材料中的对象。";
        }
    }
}
