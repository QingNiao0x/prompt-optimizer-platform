package com.promptoptimizer.provider.domain;

import com.promptoptimizer.enhancement.domain.ProviderMetadata;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * 对没有独立成果要求的当前动作保留原请求，携带已允许的真实材料，避免写作阶段新增成果义务。
 * 适用依据是语义范围记录，不使用职业、领域或动作关键词。模型分类仍须真实评测验证。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class NaturalPreservedRequest {
    private NaturalPreservedRequest() { }

    /**
     * 本路径没有改写首稿；来源和正文直接由同一原件构造，不能声称是模型覆盖证明。
     * 原请求及每份实际题答逐字保留；引用材料只承载背景，不能变成新增任务或用户确认。
     * 已完成权限过滤的当前与Plan快照均保留，重复材料按完整内容归并，实际题答绝不归并。
     */
    public static NaturalPromptDraft create(NaturalPromptInput input, NaturalTaskScope scope, ProviderMetadata metadata) {
        boolean inferredOnly = (NaturalPromptScopedInstructions.CONSERVATIVE_VERSION.equals(input.strategyVersion())
                || NaturalPromptScopedInstructions.GUARDED_FOURTH_VERSION.equals(input.strategyVersion()))
                && scope.response().basis() == NaturalTaskScope.ResponseBasis.INHERENT;
        if (scope.response().basis() != NaturalTaskScope.ResponseBasis.UNSPECIFIED && !inferredOnly) {
            throw new IllegalArgumentException("已有独立回应要求须走正文生成");
        }
        var sources = input.taskSources();
        StringBuilder body = new StringBuilder(input.rawPrompt().strip());
        List<NaturalTaskUnderstanding.Requirement> requirements = new ArrayList<>();
        List<NaturalTaskReview.Coverage> coverage = new ArrayList<>();
        addRequirement(requirements, coverage, sources.stream().filter(source -> source.id().equals("rawPrompt")).findFirst().orElseThrow(),
                "原始请求全文及其独立条件", NaturalTaskUnderstanding.Dimension.GOAL);
        var material = new HashSet<String>();
        for (NaturalTaskSource source : sources) {
            if (source.kind() == NaturalTaskSource.Kind.BOUND_PLAN_ANSWER) {
                body.append("\n\n用户实际题答：\n").append(source.text());
                addRequirement(requirements, coverage, source, "保留本题完整实际回答及其适用问题", NaturalTaskUnderstanding.Dimension.USER_CONFIRMATION);
            } else if (source.kind() == NaturalTaskSource.Kind.USER_CONVERSATION
                    || source.kind() == NaturalTaskSource.Kind.ASSISTANT_CONVERSATION) {
                body.append(source.kind() == NaturalTaskSource.Kind.USER_CONVERSATION ? "\n\n此前用户消息：\n" : "\n\n此前助手讨论或建议：\n")
                        .append(source.text());
            } else if (source.kind() == NaturalTaskSource.Kind.PROJECT_CONTEXT
                    && (source.id().endsWith(".customDescription") || source.id().matches("(?:context|planningContext)\\.fileSnippets\\.\\d+"))
                    && material.add(source.text())) {
                // 不复制可由原片段推出的技术栈、摘要等派生重复项，也不改写片段中的主体、条件和确定程度。
                body.append("\n\n提供的材料：\n").append(source.text());
            }
        }
        var understanding = new NaturalTaskUnderstanding(scope.goal(), "", requirements, List.of(), List.of(),
                new NaturalTaskReview(NaturalTaskReview.InputReadiness.SUFFICIENT, coverage, List.of(), List.of())).resolve(input);
        understanding.review().validateEnhancement(understanding, body.toString());
        return new NaturalPromptDraft(body.toString(), NaturalPromptDraft.Framework.APE, metadata, understanding);
    }

    /** 直接复制的全文用真实原句定位，完整性由逐字构造保证；不伪造模型的逐项语义审查。 */
    private static void addRequirement(List<NaturalTaskUnderstanding.Requirement> requirements, List<NaturalTaskReview.Coverage> coverage,
            NaturalTaskSource source, String text, NaturalTaskUnderstanding.Dimension dimension) {
        int index = requirements.size();
        requirements.add(new NaturalTaskUnderstanding.Requirement(text,
                List.of(new NaturalTaskUnderstanding.Evidence(source.id(), source.text())), List.of(dimension)));
        // 锚点使用与既有覆盖校验相同的换行边界；只归一化锚点，不改变原请求或实际题答正文。
        String quote = java.util.Arrays.stream(source.text().split("\\R")).map(String::strip)
                .filter(line -> !line.isBlank()).findFirst().orElseThrow();
        coverage.add(new NaturalTaskReview.Coverage(index, List.of(quote)));
    }
}
