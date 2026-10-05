package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.provider.domain.EnhancementProviderResponse;
import com.promptoptimizer.template.domain.PromptTemplate;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 核对新闻成品必须交代的明确状态随复制正文交付，不把背景、示例或未决情况当作成品事实。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class NewsBodyFactContractTest {
    @Test
    void explicitlyRequiresTheActualBodyToCarryTheProvidedCurrentStageInBothModes() {
        var response = new EnhancementProviderResponse(List.of(
                new PromptSection(PromptSectionType.BACKGROUND, "背景", "清笺尚处于本地MVP阶段。"),
                new PromptSection(PromptSectionType.TASK, "任务", "撰写内测招募新闻稿"),
                new PromptSection(PromptSectionType.OUTPUT, "输出", "两个标题和正文600–800字"),
                new PromptSection(PromptSectionType.CONSTRAINTS, "约束", "不得虚构经营数据")
        ), "fixture", "fixture", false, List.of());
        String raw = "请为清笺撰写内测招募新闻稿。清笺尚处于本地MVP阶段，不直接执行用户任务。"
                + "正文600至800字，两个标题另计。";
        for (boolean confirmed : List.of(false, true)) {
            var result = new OptimizationResultAssembler().assemble(response,
                    new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "v1"),
                    new PromptTemplate(TemplateCode.GENERAL, "输出", "满足实际交付", "示例"),
                    List.of(), List.of(), confirmed, List.of("不得虚构事实"), false, 1, raw);
            assertThat(result.sections()).filteredOn(s -> s.type() == PromptSectionType.OUTPUT)
                    .allSatisfy(s -> assertThat(s.content()).contains("正文必须交代", "清笺尚处于本地MVP阶段"));
            assertThat(result.optimizedPrompt()).contains("正文必须交代");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "撰写新闻稿正文600至800字。例如公司目前正式商用，不可将示例写成事实。",
            "撰写新闻稿正文600至800字。假设产品目前正式上线，这不是本题事实。",
            "撰写新闻稿正文600至800字。产品当前是否已经上线尚未核实。",
            "请只输出译文：产品当前处于MVP阶段。",
            "开发新闻稿接口，产品当前处于MVP阶段。只输出代码。"
    })
    void doesNotPromoteExamplesUnknownFactsOrOtherTaskProfiles(String raw) {
        assertThat(NewsBodyFactContract.guidance(raw)).isEmpty();
    }

    @Test
    void keepsNegationAndTheOriginalSentenceWithoutInferringACapability() {
        assertThat(NewsBodyFactContract.guidance("请为清笺写新闻稿。该项目目前尚未正式发布，不承诺自动诊断。"))
                .contains("目前尚未正式发布", "不承诺自动诊断").doesNotContain("已经正式发布");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "请为清笺写新闻稿。编辑工作目前已完成，内部审核目前已通过。",
            "请为清笺写新闻稿，只输出两个标题，不写正文。清笺目前处于MVP阶段。",
            "请为清笺写新闻稿。另一个产品目前处于MVP阶段。",
            "请为清笺写新闻稿。清笺目前正式发布的说法证据不足。",
            "请为清笺写新闻稿。若清笺目前已正式商用，请按该假设构思标题。",
            "请为清笺写新闻稿。\n## 示例\n清笺目前已正式商用。",
            "请为清笺写新闻稿。另一个产品叫白笺。该产品目前处于MVP阶段。",
            "请为清笺写新闻稿。只返回 JSON。清笺目前处于MVP阶段。",
            "请为清笺写新闻稿。清笺目前处于MVP阶段，但不要披露该阶段。",
            "请为清笺写新闻稿。清笺目前处于内测，联系人手机号为12345678901，仅供内部。",
            "请为清笺写新闻稿。清笺目前已发布，password=example-fixture-secret123。"
    })
    void doesNotTurnEditorWorkOtherObjectsOrExcludedSectionsIntoBodyFacts(String raw) {
        assertThat(NewsBodyFactContract.guidance(raw)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "请为清笺撰写新闻稿标题。只输出两个标题，不生成正文。清笺目前处于MVP阶段。",
            "请为清笺撰写新闻稿标题。只提供两条标题，无需生成正文。清笺目前处于内测阶段。",
            "请为清笺撰写新闻稿标题。仅交付两个标题，不产出新闻正文。清笺目前尚未正式发布。"
    })
    void doesNotAddBodyRequirementsWhenBodyWordsOnlyAppearInAnExplicitExclusion(String raw) {
        assertThat(NewsBodyFactContract.guidance(raw)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "请为清笺撰写新闻稿正文。\n## 示例\n### 产品阶段\n清笺目前已正式商用。",
            "请为清笺撰写新闻稿正文。\n## 假设资料\n### 背景\n#### 当前阶段\n清笺目前处于内测阶段。",
            "请为清笺撰写新闻稿正文。\n## 反例\n### 状态说明\n清笺目前已正式发布。"
    })
    void keepsTheExcludedParentScopeWhenAChildHeadingIsEncountered(String raw) {
        assertThat(NewsBodyFactContract.guidance(raw)).isEmpty();
    }

    @Test
    void resumesPublicFactsAtASiblingHeadingWithoutPromotingThePreviousExample() {
        String raw = "请为清笺撰写新闻稿正文。\n## 示例\n### 产品阶段\n清笺目前已正式商用。"
                + "\n## 可公开事实\n清笺目前处于本地MVP阶段。";
        assertThat(NewsBodyFactContract.guidance(raw))
                .contains("清笺目前处于本地MVP阶段")
                .doesNotContain("清笺目前已正式商用");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "请为清笺撰写新闻稿正文，内容只介绍清笺。清笺的竞品材料记录：蓝岑目前已正式商用。",
            "请为清笺撰写新闻稿正文。清笺团队目前正在校核内测发布稿。",
            "请为清笺撰写新闻稿正文。有关清笺的资料提到蓝岑目前已正式上线。",
            "请为清笺撰写新闻稿正文。清笺的旧版对照表记载其他平台目前处于公测阶段。"
    })
    void requiresTheCurrentStateSubjectToBeTheNewsTargetRatherThanAMention(String raw) {
        assertThat(NewsBodyFactContract.guidance(raw)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "请为清笺撰写新闻稿正文。\n仅供内部参考：\n清笺目前处于MVP阶段。",
            "请为清笺撰写新闻稿正文。\n## 内部资料\n### 产品阶段\n清笺目前处于内测阶段。",
            "请为清笺撰写新闻稿正文。\n不披露以下资料：\n清笺目前尚未正式发布。"
    })
    void carriesInternalOrNonDisclosureScopesToTheFollowingFacts(String raw) {
        assertThat(NewsBodyFactContract.guidance(raw)).isEmpty();
    }

    @Test
    void returnsToAPublicSiblingScopeWithoutPublishingTheInternalStage() {
        String raw = "请为清笺撰写新闻稿正文。\n## 内部资料\n### 产品阶段\n清笺目前处于内测阶段。"
                + "\n## 可公开事实\n清笺目前处于本地MVP阶段。";
        assertThat(NewsBodyFactContract.guidance(raw))
                .contains("清笺目前处于本地MVP阶段")
                .doesNotContain("清笺目前处于内测阶段");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "请为清笺撰写新闻稿正文。清笺目前处于本地MVP阶段。",
            "请为清笺撰写新闻稿正文。产品清笺目前处于内测阶段。",
            "请为清笺撰写新闻稿正文。清笺目前尚未正式发布。该产品不承诺自动诊断。"
    })
    void keepsOrdinaryPublicTargetFactsAndTheirOriginalNegation(String raw) {
        assertThat(NewsBodyFactContract.guidance(raw)).contains("正文必须交代", "清笺");
    }

    @Test
    void permitsAnExplicitlyApprovedNamedProductStageInOtherwiseInternalMaterials() {
        String raw = "请为清笺撰写新闻稿正文。\n## 内部资料"
                + "\n已确认允许在本次新闻稿正文披露清笺的产品阶段。"
                + "\n清笺目前处于MVP阶段。";
        assertThat(NewsBodyFactContract.guidance(raw)).contains("清笺目前处于MVP阶段");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "请为清笺撰写新闻稿正文。\n## 内部资料\n已确认允许在本次新闻稿正文披露蓝岑的产品阶段。\n清笺目前处于内测阶段。",
            "请为清笺撰写新闻稿正文。\n## 内部资料\n已确认允许在本次新闻稿正文披露清笺的招募日期。\n清笺目前处于内测阶段。"
    })
    void doesNotExtendDisclosureApprovalToOtherObjectsOrOtherProperties(String raw) {
        assertThat(NewsBodyFactContract.guidance(raw)).isEmpty();
    }

    @Test
    void doesNotUseDisclosureApprovalToBypassCredentialOrPersonalDataProtection() {
        String approval = "请为清笺撰写新闻稿正文。\n## 内部资料"
                + "\n已确认允许在本次新闻稿正文披露清笺的产品阶段。\n";
        assertThat(NewsBodyFactContract.guidance(approval
                + "清笺目前处于MVP阶段，password=example-fixture-secret123。")).isEmpty();
        assertThat(NewsBodyFactContract.guidance(approval
                + "清笺目前处于MVP阶段，联系人手机号为12345678901。")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "请为清笺撰写新闻稿正文。\n## 示例\n### 可公开事实\n清笺目前已正式商用。",
            "请为清笺撰写新闻稿正文。\n## 内部资料\n### 可公开事实\n清笺目前处于内测阶段。",
            "请为清笺撰写新闻稿正文。\n## 内部资料\n可公开事实：\n清笺目前处于内测阶段。",
            "请为清笺撰写新闻稿正文。\n## 示例\n已确认允许在本次新闻稿正文披露清笺的产品阶段。\n清笺目前处于MVP阶段。",
            "请为清笺撰写新闻稿正文。\n仅供内部参考：\n### 产品阶段\n清笺目前处于MVP阶段。",
            "请为清笺撰写新闻稿正文。\n仅供内部参考：\n### 可公开事实\n清笺目前处于MVP阶段。"
    })
    void cannotReclassifyAnExcludedParentWithAChildPublicLabelOrExamplePermission(String raw) {
        assertThat(NewsBodyFactContract.guidance(raw)).isEmpty();
    }

    @Test
    void doesNotAttachAnotherProductsStateToTheNamedProductsStateInOneSentence() {
        assertThat(NewsBodyFactContract.guidance("请为清笺撰写新闻稿正文，内容只介绍清笺。"
                + "清笺目前处于MVP阶段，蓝岑目前已正式商用。"))
                .doesNotContain("蓝岑");
    }

    @Test
    void keepsAnImmediatelyElidedStateOfTheSameProductWithoutChangingItsNegation() {
        assertThat(NewsBodyFactContract.guidance("请为清笺撰写新闻稿正文。"
                + "清笺目前处于MVP阶段，仍未正式商用，不直接执行用户任务。"))
                .contains("清笺目前处于MVP阶段", "仍未正式商用", "不直接执行用户任务");
    }

    @Test
    void doesNotApplyAnotherProductsPublicationRestrictionToThePublicTarget() {
        assertThat(NewsBodyFactContract.guidance("请为清笺撰写新闻稿正文。不得披露蓝岑的产品阶段。"
                + "清笺目前处于MVP阶段。"))
                .contains("清笺目前处于MVP阶段");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "请为清笺撰写新闻稿正文。不得披露清笺的产品阶段。清笺目前处于MVP阶段。",
            "请为清笺撰写新闻稿正文。不得披露本项目清笺的当前阶段。清笺目前处于内测阶段。",
            "请为清笺撰写新闻稿正文。清笺目前处于MVP阶段，不要披露其阶段。"
    })
    void keepsAnExplicitStageDenialBoundToTheCurrentTargetAndItsAliases(String raw) {
        assertThat(NewsBodyFactContract.guidance(raw)).isEmpty();
    }

    @Test
    void doesNotPublishAnotherInternalAttributeThroughAProductStageApproval() {
        String raw = "请为清笺撰写新闻稿正文。\n## 内部资料"
                + "\n已确认允许在本次新闻稿正文披露清笺的产品阶段。"
                + "\n清笺目前处于MVP阶段，不承诺商业补偿。";
        assertThat(NewsBodyFactContract.guidance(raw)).doesNotContain("不承诺商业补偿");
    }

    @Test
    void bindsAnImmediatelyFollowingPronounToTheExplicitlyApprovedStageTarget() {
        String raw = "请为清笺撰写新闻稿正文。\n## 内部资料"
                + "\n已确认允许在本次新闻稿正文披露清笺的产品阶段。"
                + "\n该产品目前处于MVP阶段。";
        assertThat(NewsBodyFactContract.guidance(raw)).contains("该产品目前处于MVP阶段");
    }
}
