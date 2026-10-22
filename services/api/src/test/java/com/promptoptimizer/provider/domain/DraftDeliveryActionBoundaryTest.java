package com.promptoptimizer.provider.domain;

import com.promptoptimizer.enhancement.domain.ConfirmedPlanDecision;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.template.domain.PromptTemplate;
import com.promptoptimizer.template.domain.TaskDeliveryProfile;
import com.promptoptimizer.template.domain.TaskIntentResolver;
import com.promptoptimizer.template.service.impl.PromptTemplateRegistryImpl;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 当前动作和成果要求必须同源；模板、事实描述和未来规则都不能独立新增正文交付。
 * 这些确定性回归不代表任意自然语言的语义理解或自然链真实评测已经通过。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class DraftDeliveryActionBoundaryTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "请先熟悉项目。",
            "请先熟悉项目，前端使用 Vue3 和 TypeScript 进行编写。",
            "请先熟悉项目，前端使用 Vue3 和 TypeScript 进行编写的。",
            "请核对材料，附件是已经撰写的年度报告。",
            "请先理解这些资料，以后撰写报告时再使用。",
            "请先熟悉项目，如果要撰写报告请先确认。",
            "请阅读资料，不需要撰写报告。",
            "请阅读资料。不要编写任何说明文档。",
            "请解释这句要求：‘撰写一份年度报告’。",
            "请阅读项目。\n## 参考资料\n撰写一份用户指南。",
            "请理解任务。\n```text\n撰写一份研究报告。\n```",
            "请阅读项目。\n> 请编写邮件。",
            "Please read the repository, do not write a report."
    })
    void backgroundNegationReferencesAndFutureActionsDoNotAuthorizeProse(String raw) {
        assertThat(TaskIntentResolver.requestsWriting(raw)).as(raw).isFalse();
        var contract = contract(raw, List.of());
        assertThat(contract.proseRequired()).isFalse();
        assertThat(contract.guidance()).isEmpty();
        assertThat(contract.modelGuidance()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "请先熟悉项目，并撰写一份项目说明报告。",
            "请先熟悉项目并撰写一份项目说明报告。",
            "请先核对资料，再编写给客户的邮件。",
            "前端使用 TypeScript 进行编写。请撰写一份项目说明。",
            "请撰写已经核对过资料的研究报告。",
            "请提供一份用户指南。",
            "Please read the repository and write a report."
    })
    void explicitWritingSurvivesUnderstandingActionsAndBackground(String raw) {
        assertThat(TaskIntentResolver.requestsWriting(raw)).as(raw).isTrue();
        var contract = contract(raw, List.of());
        assertThat(contract.active()).isTrue();
        assertThat(contract.proseRequired()).isTrue();
        assertThatThrownBy(() -> contract.validateDelivery("本轮只交付提纲。", "sections.TASK.content"))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void templateSelectionCannotInventWritingPermission() {
        var proseTemplate = new PromptTemplate(TemplateCode.GENERAL, "", "", TaskDeliveryProfile.USER_GUIDE);
        assertThat(DraftDeliveryContract.from("请先阅读这些资料。", proseTemplate, List.of()).proseRequired()).isFalse();
    }

    @Test
    void actualDeliveryAnswerCanAddWritingButCurrentStateAndUnresolvedAnswersCannot() {
        String raw = "请先熟悉项目。";
        var answer = new ConfirmedPlanDecision("delivery", "本次交付什么？", "结果",
                ConfirmedPlanDecision.Scope.CHOICE, "撰写一份项目说明。");
        assertThat(contract(raw, List.of(answer)).active()).isTrue();
        for (var scope : List.of(ConfirmedPlanDecision.Scope.CURRENT_STATE, ConfirmedPlanDecision.Scope.UNRESOLVED)) {
            var state = new ConfirmedPlanDecision("delivery", "本次交付什么？", "结果", scope, answer.answer());
            assertThat(contract(raw, List.of(state)).proseRequired()).isFalse();
        }
    }

    @Test
    void technologyBackgroundDoesNotChangeTheUnderstandingGoal() {
        String raw = "你是一名高级全栈Java开发工程师，请熟悉一下前后端项目；这个是使用低代码平台JeecgBoot构建的系统，"
                + "前端使用vue3和typescript来进行编写的，后端使用spring boot3。"
                + "后端brucellosis为重构后的新模块，oldbrucellosis为旧模块。每次修改时两个模块都要同步。";
        assertThat(TaskIntentResolver.resolve(TemplateCode.AUTO, raw).deliveryProfile())
                .isEqualTo(TaskDeliveryProfile.PROJECT_UNDERSTANDING);
        assertThat(contract(raw, List.of()).proseRequired()).isFalse();
    }

    private DraftDeliveryContract contract(String raw, List<ConfirmedPlanDecision> decisions) {
        return DraftDeliveryContract.from(raw,
                new PromptTemplateRegistryImpl().resolve(TemplateCode.AUTO, raw, decisions), decisions);
    }
}
