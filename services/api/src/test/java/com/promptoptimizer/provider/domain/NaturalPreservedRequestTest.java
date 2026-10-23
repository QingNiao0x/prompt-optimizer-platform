package com.promptoptimizer.provider.domain;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.ProviderMetadata;
import com.promptoptimizer.enhancement.dto.ConversationMessage;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

/** 验证无成果请求的逐字承载，不能用一个片段冒充全部实际题答或把助手建议当用户确认。 */
class NaturalPreservedRequestTest {
    private static final ProviderMetadata MODEL = new ProviderMetadata("fixture", "fixture", false);

    @ParameterizedTest
    @ValueSource(strings = {"\n", "\r\n", "\u2028", "\u0085"})
    void preservesSourceAcrossSupportedLineBreaksAndOuterWhitespace(String lineBreak) {
        String raw = "  先理解材料" + lineBreak + "后续再讨论。  ";
        var input = new NaturalPromptInput(raw, null, List.of(), List.of(), List.of(), null);
        var scope = new NaturalTaskScope(List.of(new NaturalTaskScope.Action("理解材料", NaturalTaskScope.Timing.CURRENT, "",
                List.of(new NaturalTaskScope.Evidence("rawPrompt", "先理解材料")))),
                new NaturalTaskScope.Response(NaturalTaskScope.ResponseBasis.UNSPECIFIED, "", List.of())).resolve(input);
        var draft = NaturalPreservedRequest.create(input, scope, new ProviderMetadata("fixture", "fixture", false));
        assertThat(draft.optimizedPrompt()).isEqualTo(raw.strip());
        draft.taskUnderstanding().review().validateEnhancement(draft.taskUnderstanding(), draft.optimizedPrompt());
        assertThat(input.strategyVersion()).isEqualTo("31");
    }

    @Test
    void preservesRawRoleConditionsMaterialsAndEveryActualAnswerWithoutAddingDeliverable() {
        String raw = "你是高级开发工程师。\n先熟悉项目。\n之后仅在我明确提出需求时修改。";
        var context = new ContextSnapshot("只有说明片段，未提供运行结果。", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "test");
        var input = new NaturalPromptInput(raw, context,
                List.of(new PlanAnswer("a", "是否已经核对？", "不知道。"), new PlanAnswer("b", "记录是否存在？", "不知道。")),
                List.of(new ConversationMessage("assistant", "建议再写一份说明，用户未确认。")), List.of(), null, context).withStrategyVersion("29");
        var scope = new NaturalTaskScope(List.of(new NaturalTaskScope.Action("熟悉项目", NaturalTaskScope.Timing.CURRENT, "",
                List.of(new NaturalTaskScope.Evidence("rawPrompt", "先熟悉项目")))),
                new NaturalTaskScope.Response(NaturalTaskScope.ResponseBasis.UNSPECIFIED, "", List.of())).resolve(input);
        var draft = NaturalPreservedRequest.create(input, scope, new ProviderMetadata("fixture", "fixture", false));
        assertThat(draft.optimizedPrompt()).startsWith(raw).contains("是否已经核对？\n不知道。", "记录是否存在？\n不知道。")
                .contains("此前助手讨论或建议：\n建议再写一份说明，用户未确认。");
        assertThat(draft.optimizedPrompt().split("只有说明片段", -1)).hasSize(2);
        assertThat(draft.taskUnderstanding().deliverable()).isEmpty();
        assertThat(draft.taskUnderstanding().requirements()).hasSize(3);
        assertThat(draft.taskUnderstanding().requirements().subList(1, 3)).extracting(r -> r.evidence().getFirst().sourceId())
                .containsExactly("actualAnswers.0", "actualAnswers.1");
        draft.taskUnderstanding().review().validateEnhancement(draft.taskUnderstanding(), draft.optimizedPrompt());
    }

    @Test
    void guardedFourthCarrierRetainsItsExactExistingBodyAndSourceOrder() {
        String raw = "  先理解材料。\n有新需求时再操作。  ";
        var context = new ContextSnapshot("未提供运行结果。", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "test");
        var input = new NaturalPromptInput(raw, context,
                List.of(new PlanAnswer("a", "问题甲？", "不知道。"), new PlanAnswer("b", "问题乙？", "不知道。")),
                List.of(new ConversationMessage("user", "只理解。"), new ConversationMessage("assistant", "建议写报告，尚未确认。")),
                List.of(), null, context).withStrategyVersion("31");
        var scope = new NaturalTaskScope(List.of(new NaturalTaskScope.Action("理解材料", NaturalTaskScope.Timing.CURRENT, "",
                List.of(new NaturalTaskScope.Evidence("rawPrompt", "先理解材料")))),
                new NaturalTaskScope.Response(NaturalTaskScope.ResponseBasis.UNSPECIFIED, "", List.of())).resolve(input);
        var draft = NaturalPreservedRequest.create(input, scope, MODEL);
        assertThat(draft.optimizedPrompt()).isEqualTo("先理解材料。\n有新需求时再操作。"
                + "\n\n提供的材料：\n未提供运行结果。"
                + "\n\n此前用户消息：\n只理解。"
                + "\n\n此前助手讨论或建议：\n建议写报告，尚未确认。"
                + "\n\n用户实际题答：\n问题甲？\n不知道。"
                + "\n\n用户实际题答：\n问题乙？\n不知道。");
        assertThat(draft.taskUnderstanding().requirements()).extracting(item -> item.evidence().getFirst().sourceId())
                .containsExactly("rawPrompt", "actualAnswers.0", "actualAnswers.1");
    }

    @Test
    void supplementsOriginalRequestAndEachAnswerWithoutUsingFreeRewriteOrPromotingUnknowns() {
        String raw = "请写一份操作说明。\n只有审批通过才交付；其他设备当天可借。\n已收回不等于可借。";
        var context = new ContextSnapshot("资料没有说明审批结果。", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "test");
        var input = new NaturalPromptInput(raw, context,
                List.of(new PlanAnswer("a", "审批结果？", "不知道。"), new PlanAnswer("b", "名额是否确定？", "不知道。")),
                List.of(new ConversationMessage("user", "没有授权修改规则。"), new ConversationMessage("assistant", "建议把步骤写成表格，用户未确认。")),
                List.of(), null, context).withStrategyVersion("33");
        var additions = List.of(new NaturalTaskUnderstanding.Addition("按条件选择处理分支。\n检查通过才恢复可借。", "便于直接操作", "分支与状态关系需要表达"));
        var original = sourceDraft(input, additions);
        var result = NaturalPreservedRequest.supplement(input, original);
        assertThat(result.optimizedPrompt()).startsWith(raw)
                .contains("用户实际题答：\n审批结果？\n不知道。", "用户实际题答：\n名额是否确定？\n不知道。",
                        "此前用户消息：\n没有授权修改规则。", "此前助手讨论或建议：\n建议把步骤写成表格，用户未确认。",
                        "可参考的补充：", additions.getFirst().text())
                .doesNotContain("只在首稿存在的改写锚点");
        assertThat(result.taskUnderstanding().requirements()).isEqualTo(original.taskUnderstanding().requirements());
        assertThat(result.taskUnderstanding().goal()).isEqualTo(original.taskUnderstanding().goal());
        assertThat(result.taskUnderstanding().deliverable()).isEqualTo(original.taskUnderstanding().deliverable());
        assertThat(result.taskUnderstanding().openDecisions()).isEqualTo(original.taskUnderstanding().openDecisions());
        assertThat(result.taskUnderstanding().additions()).isEqualTo(additions);
        assertThat(result.taskUnderstanding().review().inputReadiness()).isEqualTo(NaturalTaskReview.InputReadiness.NEEDS_COMPLETION);
        assertThat(result.taskUnderstanding().requirements().stream().flatMap(item -> item.evidence().stream())
                .filter(item -> item.origin() == NaturalTaskSource.Kind.BOUND_PLAN_ANSWER).map(NaturalTaskUnderstanding.Evidence::sourceId))
                .containsExactly("actualAnswers.0", "actualAnswers.1");
        assertThat(result.taskUnderstanding().review().requirementCoverage().getFirst().quotes()).containsExactly("请写一份操作说明。");
        assertThat(result.taskUnderstanding().review().additionCoverage().getFirst().quotes()).containsExactly("按条件选择处理分支。");
        result.taskUnderstanding().review().validateEnhancement(result.taskUnderstanding(), result.optimizedPrompt());
    }

    @Test
    void zeroAdditionsKeepsTheOriginalExplicitDeliverableWithoutAddingSupplementHeadings() {
        String raw = "将甲乙分别换行，只输出两行。";
        var input = new NaturalPromptInput(raw, null, List.of(), List.of(), List.of(), null).withStrategyVersion("33");
        var original = sourceDraft(input, List.of());
        var result = NaturalPreservedRequest.supplement(input, original);
        assertThat(result.optimizedPrompt()).isEqualTo(raw);
        assertThat(result.taskUnderstanding().deliverable()).isEqualTo("操作说明正文");
        assertThat(result.taskUnderstanding().review().additionCoverage()).isEmpty();
    }

    @Test
    void referencedDerivedContextIsCopiedAsMaterialAndRetainsItsOriginalRequirement() {
        var context = new ContextSnapshot("只提供项目说明。", List.of(), List.of(), List.of("reports/derived-state.md"),
                List.of(), List.of(), List.of(), "test");
        var input = new NaturalPromptInput("依据材料写操作说明。", context, List.of(), List.of(), List.of(), null).withStrategyVersion("33");
        var original = sourceDraft(input, List.of());
        var result = NaturalPreservedRequest.supplement(input, original);
        assertThat(result.optimizedPrompt()).contains("\n\n提供的材料：\nreports/derived-state.md");
        assertThat(result.taskUnderstanding().requirements()).isEqualTo(original.taskUnderstanding().requirements());
        assertThat(result.taskUnderstanding().requirements().getLast().evidence().getFirst().sourceId()).isEqualTo("context.directoryTree.0");
        assertThat(result.taskUnderstanding().review().requirementCoverage().getLast().quotes()).containsExactly("reports/derived-state.md");
        result.taskUnderstanding().review().validateEnhancement(result.taskUnderstanding(), result.optimizedPrompt());
    }

    @Test
    void repeatedSupplementTextRetainsSeparateValidatedRecordsAndCoverageIndexes() {
        var input = new NaturalPromptInput("请设计操作步骤。", null, List.of(), List.of(), List.of(), null).withStrategyVersion("33");
        String text = "按原条件执行。";
        var additions = List.of(new NaturalTaskUnderstanding.Addition(text, "关联第一个条件", "缺少第一个关系"),
                new NaturalTaskUnderstanding.Addition(text, "关联另一个独立条件", "缺少第二个关系"));
        var result = NaturalPreservedRequest.supplement(input, sourceDraft(input, additions));
        assertThat(result.taskUnderstanding().additions()).isEqualTo(additions);
        assertThat(result.optimizedPrompt().split(text, -1)).hasSize(3);
        assertThat(result.taskUnderstanding().review().additionCoverage()).extracting(NaturalTaskReview.Coverage::index).containsExactly(0, 1);
    }

    /** 首稿有合法出处和实际题答，但首个正文锚点来自自由改写；最终承载必须重新定位而非沿用它。 */
    private NaturalPromptDraft sourceDraft(NaturalPromptInput input, List<NaturalTaskUnderstanding.Addition> additions) {
        StringBuilder body = new StringBuilder("只在首稿存在的改写锚点");
        List<NaturalTaskUnderstanding.Requirement> requirements = new ArrayList<>();
        List<NaturalTaskReview.Coverage> coverage = new ArrayList<>();
        for (var source : input.taskSources()) {
            body.append("\n\n").append(source.text());
            var dimension = source.kind() == NaturalTaskSource.Kind.BOUND_PLAN_ANSWER
                    ? NaturalTaskUnderstanding.Dimension.USER_CONFIRMATION : NaturalTaskUnderstanding.Dimension.GOAL;
            requirements.add(new NaturalTaskUnderstanding.Requirement("保留来源" + source.id(),
                    List.of(new NaturalTaskUnderstanding.Evidence(source.id(), source.text())), List.of(dimension)));
            String quote = requirements.size() == 1 ? "只在首稿存在的改写锚点" : source.text().split("\\R")[0];
            coverage.add(new NaturalTaskReview.Coverage(requirements.size() - 1, List.of(quote)));
        }
        List<NaturalTaskReview.Coverage> additionCoverage = new ArrayList<>();
        for (int index = 0; index < additions.size(); index++) {
            body.append("\n\n").append(additions.get(index).text());
            additionCoverage.add(new NaturalTaskReview.Coverage(index, List.of(additions.get(index).text().split("\\R")[0])));
        }
        var task = new NaturalTaskUnderstanding("撰写操作说明", "操作说明正文", requirements, additions,
                List.of(new NaturalTaskUnderstanding.OpenDecision("审批结果未知", "不能声称已经获批", "", NaturalTaskUnderstanding.DecisionKind.USER_INFORMATION)),
                new NaturalTaskReview(NaturalTaskReview.InputReadiness.NEEDS_COMPLETION, coverage, additionCoverage, List.of())).resolve(input);
        task.review().validateEnhancement(task, body.toString());
        return new NaturalPromptDraft(body.toString(), NaturalPromptDraft.Framework.RISE, MODEL, task);
    }
}
