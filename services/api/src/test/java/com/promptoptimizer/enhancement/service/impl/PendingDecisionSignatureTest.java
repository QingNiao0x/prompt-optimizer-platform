package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.dto.PlanAnswer;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回放真实候选的语序重复，同时保护新对象、版本、限定与补充解释。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class PendingDecisionSignatureTest {
    /** 子项可以继承同题文档范围，维修映射、另一文档或另一复核角色仍保持独立。 */
    @Test
    void mergesPrimaryDocumentFromTheBoundOriginalAfterALongUnknownAnswer() {
        var decisions = ConfirmedDecisionSet.from(List.of(new PlanAnswer("draft",
                "本次律师复核应以哪一版租赁草稿作为主文本？",
                "暂不确定。两版租赁草稿尚未指定主文本；保留各项已知差异与维修条款版本对应未知，不擅自选择。")));
        var result = new PlanAmbiguityMerger(decisions).merge(List.of(
                "两版租赁草稿中哪一份是本次律师复核的主文本尚未确定；在确定前只能制作并列差异清单，不能写最终合同摘要。"), List.of(), List.of());
        assertThat(result.executionPrerequisites()).hasSize(1).singleElement().asString()
                .contains("维修条款版本对应未知", "不能写最终合同摘要");
        var separate = new PlanAmbiguityMerger(decisions).merge(List.of(
                "两版采购草稿中哪一份是本次律师复核的主文本尚未确定。",
                "维修条款的版本对应关系尚未确定。"), List.of(), List.of());
        assertThat(separate.executionPrerequisites()).hasSize(3);
    }

    /** 裸未知状态可以由权威前缀表达，后续事实和否定边界不得随重复题干丢失。 */
    @Test
    void rendersAnExactUnknownAnswerSubjectOnlyOnce() {
        String subject = "两名盲评者的分歧处理及一致性评价方式仍未确定，不预设平均、共识或第三评审。";
        var result = new PlanAmbiguityMerger(ConfirmedDecisionSet.from(List.of(new PlanAnswer("rater",
                "两名盲评者的分歧处理应如何确定？", "暂不确定。" + subject))))
                .merge(List.of(), List.of(), List.of());
        assertThat(result.executionPrerequisites()).singleElement().asString().contains("不预设平均、共识或第三评审");
        assertThat(result.executionPrerequisites().getFirst().split("两名盲评者", -1)).hasSize(2);
    }

    /** 回放两款真实模型的泛指问法，必须由同一任务已明确的完整候选对证明关联。 */
    @Test
    void shouldMergeKnownFocusChoicesAcrossTheObservedQuestionForms() {
        String raw = "请整理为供律师复核的问题清单。复核优先侧重持续履约还是可能提前退出的安排？当前尚未决定。";
        for (String question : List.of("本次律师复核优先侧重哪一类安排？", "本次律师复核应优先侧重哪方面的安排？")) {
            assertThat(PendingDecisionSignature.same("本次复核尚未选定优先侧重持续履约还是可能提前退出", question, raw)).isTrue();
            assertThat(PendingDecisionSignature.same("本次财务复核优先侧重持续履约还是可能提前退出", question, raw)).isFalse();
            assertThat(PendingDecisionSignature.same("本次复核优先侧重跨境持续履约还是可能提前退出", question, raw)).isFalse();
            var merged = new PlanAmbiguityMerger(ConfirmedDecisionSet.from(List.of(new PlanAnswer("focus", question, "暂不确定。"))), raw)
                    .merge(List.of("本次律师复核应优先侧重持续履约还是可能提前退出的安排？该选择影响复核问题的优先级，当前尚未决定。"), List.of(), List.of());
            assertThat(merged.executionPrerequisites()).hasSize(1);
        }
    }

    @Test
    void mergesReorderedPrimaryDocumentWhileRetainingItsEffect() {
        var answers = List.of(new PlanAnswer("draft", "本次律师复核应以哪一版租赁草稿作为主文本？", "暂不确定。"));
        var result = new PlanAmbiguityMerger(ConfirmedDecisionSet.from(answers)).merge(List.of(
                "两版租赁草稿中哪一份是本次律师复核的主文本尚未确定，不得默认补全；该选择影响差异表的基准列。"), List.of(), List.of());
        assertThat(result.executionPrerequisites()).singleElement().asString().contains("不得默认补全", "基准列");
    }

    @Test
    void neverBorrowsThePrimaryDocumentDecisionOfAnotherObjectOrVersion() {
        String question = "本次律师复核应以哪一版租赁草稿作为主文本？";
        assertThat(PendingDecisionSignature.same("两版采购草稿中哪一份是本次律师复核的主文本尚未确定", question, "")).isFalse();
        assertThat(PendingDecisionSignature.same("两版租赁草稿中哪一份是本次财务复核的主文本尚未确定", question, "")).isFalse();
        assertThat(PendingDecisionSignature.same("两版租赁草稿A中哪一份是本次律师复核的主文本尚未确定", question, "")).isFalse();
    }

    @Test
    void matchesNamedFocusOnlyWhenTheRawRequirementAlreadyNamesThatChoice() {
        String question = "律师复核应优先侧重哪类安排？";
        String candidate = "律师复核应优先侧重持续履约还是可能提前退出的安排？";
        String raw = "复核优先侧重持续履约还是可能提前退出的安排？当前尚未决定。";
        assertThat(PendingDecisionSignature.same(candidate, question, raw)).isTrue();
        assertThat(PendingDecisionSignature.same(candidate, question, "资料整理。" )).isFalse();
        assertThat(PendingDecisionSignature.same(candidate.replace("持续履约", "跨境持续履约"), question, raw)).isFalse();
        assertThat(PendingDecisionSignature.same(candidate.replace("律师", "财务"), question, raw)).isFalse();
    }

    @Test
    void mergesReorderedStatisticalUnitButKeepsAnotherPopulationAndNewQualifier() {
        String question = "比较渠道时，主统计单位应使用预约事件还是去重患者？";
        String candidate = "比较渠道时使用预约事件还是去重患者作为主统计单位？";
        assertThat(PendingDecisionSignature.same(candidate, question, "")).isTrue();
        assertThat(PendingDecisionSignature.same(candidate.replace("预约事件", "住院事件"), question, "")).isFalse();
        assertThat(PendingDecisionSignature.same(candidate.replace("比较渠道", "比较医院"), question, "")).isFalse();
        assertThat(PendingDecisionSignature.same(candidate.replace("去重患者", "本月去重患者"), question, "")).isFalse();
    }

    @Test
    void keepsNewPendingDetailEvenAfterTheSameMainDocumentHeading() {
        var result = new PlanAmbiguityMerger(ConfirmedDecisionSet.from(List.of(new PlanAnswer(
                "draft", "本次律师复核应以哪一版租赁草稿作为主文本？", "暂不确定。")))).merge(List.of(
                "两版租赁草稿中哪一份是本次律师复核的主文本尚未确定。授权签字人尚未确定。"), List.of(), List.of());
        assertThat(result.executionPrerequisites()).hasSize(2).anyMatch(text -> text.contains("授权签字人"));
    }

    @Test
    void keepsBothResearchAttributesWithoutBorrowingAnotherGroupsLimits() {
        assertThat(PendingDecisionSignature.same("交互式计划确认的最大提问轮次与终止规则尚未确定",
                "计划确认组在预注册中应设定怎样的最大提问轮次与终止规则？", "")).isTrue();
        assertThat(PendingDecisionSignature.same("其他研究组的最大提问轮次与终止规则尚未确定",
                "计划确认组在预注册中应设定怎样的最大提问轮次与终止规则？", "")).isFalse();
        assertThat(PendingDecisionSignature.same("交互式计划确认的最大提问轮次与终止规则在失访时尚未确定",
                "计划确认组在预注册中应设定怎样的最大提问轮次与终止规则？", "")).isFalse();
    }
}
