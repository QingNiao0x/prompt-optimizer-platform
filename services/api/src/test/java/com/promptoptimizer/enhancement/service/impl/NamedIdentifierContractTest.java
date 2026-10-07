package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.context.domain.FileSnippet;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.PlanQuestionType;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.provider.domain.EnhancementProviderResponse;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException;
import com.promptoptimizer.template.domain.PromptTemplate;
import com.promptoptimizer.context.dto.ContextAnalysisRequest;
import com.promptoptimizer.context.dto.ContextFileInput;
import com.promptoptimizer.context.dto.PlanningContextRequest;
import com.promptoptimizer.enhancement.dto.OptimizationPlanRequest;
import com.promptoptimizer.enhancement.dto.PermissionPolicyInput;
import com.promptoptimizer.enhancement.dto.PlanningContextReference;
import com.promptoptimizer.identity.support.TestActors;
import com.promptoptimizer.policy.service.impl.ProtectedContextFilterImpl;
import com.promptoptimizer.provider.domain.PlanningProviderResponse;
import com.promptoptimizer.template.service.impl.PromptTemplateRegistryImpl;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 以医院代码集合误当具名映射的真实失败为反例；集合、机构关系与实际确认分别检查。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class NamedIdentifierContractTest {
    private static final String RAW = "为甲院和乙院2025年普通门诊制定候诊时间比较方案，交付指标表与清洗伪代码。"
            + "两院独立计算，不虚构患者数据，不输出诊疗建议。";
    private static final String MATERIAL = "CSV字段包括visit_id、hospital_id、registered_at、seen_at。"
            + "hospital_id只能为A或B。比较观察窗口与异常等待阈值未确定。";

    @Test
    void directCopyableResultPreservesBothMissingRelationsInsteadOfAssumingOrder() {
        var result = assemble("交付指标表和伪代码，不自行选定未知参数。");
        assertThat(result.optimizedPrompt()).contains("甲院", "乙院", "hospital_id", "对应关系待确认")
                .doesNotContain("甲院（hospital_id=A）", "乙院（hospital_id=B）");
        assertThat(result.ambiguities()).anyMatch(value -> value.contains("甲院") && value.contains("hospital_id"));
        assertThat(result.ambiguities()).anyMatch(value -> value.contains("乙院") && value.contains("hospital_id"));
    }

    @Test
    void assemblerRejectsAConcreteUnconfirmedRelationEvenWhenTheBodyAdmitsUnknowns() {
        assertThatThrownBy(() -> assemble("对应关系待确认。\n| 医院 | hospital_id |\n| --- | --- |\n"
                + "| 甲院 | A |\n| 乙院 | B |"))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void anAllowedCodeSetDoesNotProveEitherMappingOrSupplyARecommendedAnswer() {
        var contract = contract(MATERIAL, List.of());
        assertThat(contract.requiredQuestions()).hasSize(2).allSatisfy(question -> {
            assertThat(question.type()).isEqualTo(PlanQuestionType.FREE_TEXT);
            assertThat(question.options()).isEmpty();
        });
        assertThat(contract.guidance()).contains("甲院 | hospital_id | 对应关系待确认", "乙院 | hospital_id | 对应关系待确认")
                .doesNotContain("甲院 | hospital_id | A |", "乙院 | hospital_id | B |");
    }

    @Test
    void explicitReverseMappingsAreRetainedAsSourceEvidenceNotUserConfirmation() {
        var contract = contract(MATERIAL + "甲院的hospital_id=B。乙院的hospital_id=A。", List.of());
        assertThat(contract.requiredQuestions()).isEmpty();
        assertThat(contract.guidance()).contains("甲院 | hospital_id | B | 资料明确", "乙院 | hospital_id | A | 资料明确")
                .doesNotContain("本次用户明确回答");
        contract.validate("甲院（hospital_id=B），乙院（hospital_id=A）。", "sections.TASK");
    }

    @Test
    void oneExplicitRelationDoesNotProveTheOtherByElimination() {
        var contract = contract(MATERIAL + "甲院对应hospital_id=B。", List.of());
        assertThat(contract.requiredQuestions()).singleElement().satisfies(question -> assertThat(question.question()).contains("乙院"));
        assertThat(contract.guidance()).contains("甲院 | hospital_id | B | 资料明确", "乙院 | hospital_id | 对应关系待确认");
        assertThatThrownBy(() -> contract.validate("乙院（A）", "sections.BACKGROUND"))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void boundShortAnswerConfirmsOnlyItsOwnInstitution() {
        var contract = contract(MATERIAL, List.of(new PlanAnswer("mapping-a", "甲院与 hospital_id 的对应关系是什么？", "B")));
        assertThat(contract.guidance()).contains("甲院 | hospital_id | B | 本次用户明确回答", "乙院 | hospital_id | 对应关系待确认");
        assertThat(contract.requiredQuestions()).hasSize(1);
    }

    @Test
    void aThresholdQuestionCannotGiveInstitutionScopeToABareCodeAnswer() {
        var contract = contract(MATERIAL, List.of(new PlanAnswer("threshold-a", "甲院异常等待阈值是什么？", "B")));
        assertThat(contract.requiredQuestions()).hasSize(2);
    }

    @Test
    void explicitPartialAnswerKeepsIndependentUnknownEvenInOneSentence() {
        var contract = contract(MATERIAL, List.of(new PlanAnswer("mapping-a", "甲院与 hospital_id 的对应关系是什么？",
                "甲院的hospital_id=B，乙院的hospital_id尚未确定。")));
        assertThat(contract.guidance()).contains("甲院 | hospital_id | B | 本次用户明确回答", "乙院 | hospital_id | 对应关系待确认");
    }

    @Test
    void futureConditionalQuotationNegationAndExampleNeverEstablishAMapping() {
        for (String statement : List.of("如果以后确认，甲院的hospital_id=A。", "“甲院的hospital_id=A”。",
                "示例：甲院对应hospital_id=A。", "不要将甲院的hospital_id=A写成已确认。", "草稿：甲院的hospital_id=A。")) {
            var contract = contract(MATERIAL + statement, List.of());
            assertThat(contract.requiredQuestions()).as(statement).hasSize(2);
        }
    }

    @Test
    void futureConditionalPlanAnswerDoesNotBecomeAUserConfirmedMapping() {
        var contract = contract(MATERIAL, List.of(new PlanAnswer("mapping-a", "甲院与 hospital_id 的对应关系是什么？",
                "如果以后确认，甲院的hospital_id=A；目前尚未决定。")));
        assertThat(contract.requiredQuestions()).hasSize(2);
        assertThat(contract.guidance()).doesNotContain("本次用户明确回答");
    }

    @Test
    void anotherYearsEvidenceCannotAuthorizeTheCurrentInstitution() {
        var contract = contract(MATERIAL + "甲院2024年的hospital_id=A。", List.of());
        assertThat(contract.requiredQuestions()).hasSize(2);
        var current = contract(MATERIAL + "甲院2025年的hospital_id=B。", List.of());
        assertThat(current.requiredQuestions()).hasSize(1);
        assertThatThrownBy(() -> current.validate("甲院2026年的hospital_id=B。", "sections.TASK"))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void oldYearHeadingWithoutCurrentLineScopeCannotAuthorizeARelation() {
        var contract = contract(MATERIAL + "\n2024年资料：\n甲院的hospital_id=A。", List.of());
        assertThat(contract.requiredQuestions()).hasSize(2);
    }

    @Test
    void anotherPopulationAndAnotherInstitutionCannotSupplyTheNamedMapping() {
        var contract = contract(MATERIAL + "甲院住院资料的hospital_id=A。丙院的hospital_id=B。", List.of());
        assertThat(contract.requiredQuestions()).hasSize(2);
    }

    @Test
    void contradictorySourcesRemainConflictedWithoutChoosingByFileOrder() {
        var contract = contract(MATERIAL + "甲院的hospital_id=A。甲院的hospital_id=B。", List.of());
        assertThat(contract.guidance()).contains("甲院 | hospital_id | 对应关系待确认 | 资料对应冲突");
        assertThat(contract.requiredQuestions()).hasSize(2);
        assertThatThrownBy(() -> contract.validate("甲院的hospital_id=A。", "sections.TASK"))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void anExplicitBoundAnswerResolvesOnlyTheNamedConflict() {
        var contract = contract(MATERIAL + "甲院的hospital_id=A。甲院的hospital_id=B。", List.of(
                new PlanAnswer("mapping-a", "甲院与 hospital_id 的对应关系是什么？", "甲院的hospital_id=B。")));
        assertThat(contract.guidance()).contains("甲院 | hospital_id | B | 本次用户明确回答", "乙院 | hospital_id | 对应关系待确认");
        contract.validate("甲院的hospital_id=B。", "sections.TASK");
    }

    @Test
    void narrativeCodeAndConcreteCellsCannotBeCancelledByUnrelatedUnknownText() {
        var contract = contract(MATERIAL, List.of());
        for (String statement : List.of("对应关系尚未确定。甲院（hospital_id=A）。", "hospital_map={\"甲院\":\"A\"}",
                "| 医院 | hospital_id | 状态 |\n|---|---|---|\n|甲院|A|签署日期待确认|",
                "| 参数 | 甲院 | 乙院 |\n|---|---|---|\n|hospital_id|A|B|")) {
            assertThatThrownBy(() -> contract.validate(statement, "sections.OUTPUT")).as(statement)
                    .isInstanceOf(ProviderResponseValidationException.class);
        }
    }

    @Test
    void explicitMappingTableIsEvidenceButAnUnknownCellOrAStateExampleIsNot() {
        var contract = contract(MATERIAL + "\n|医院|hospital_id|\n|---|---|\n|甲院|B|\n|乙院|待确认|", List.of());
        assertThat(contract.guidance()).contains("甲院 | hospital_id | B | 资料明确", "乙院 | hospital_id | 对应关系待确认");
        var unknown = contract(MATERIAL + "\n|医院|hospital_id|状态|\n|---|---|---|\n|甲院|A|尚未确认|", List.of());
        assertThat(unknown.requiredQuestions()).hasSize(2);
    }

    @Test
    void fixturesAndGeneratedSamplesCannotBindRealInstitutionMappings() {
        var fixture = new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(
                new FileSnippet("src/test/fixtures/hospital.txt", "text", MATERIAL + "甲院的hospital_id=A。", "", false)),
                List.of(), List.of(), "v1");
        var contract = NamedIdentifierContract.from(RAW + "hospital_id只能为A或B。", fixture, List.of(), List.of());
        assertThat(contract.requiredQuestions()).hasSize(2);
    }

    @Test
    void mappingOnlyQuestionsAreCoveredButNewYearOrAnotherFieldIsPreserved() {
        var contract = contract(MATERIAL, List.of());
        assertThat(contract.coveredQuestion(question("甲院与 hospital_id 的对应关系是什么？"))).isTrue();
        assertThat(contract.coveredQuestion(question("甲院2026年的hospital_id是什么？"))).isFalse();
        assertThat(contract.coveredQuestion(question("甲院的窗口是什么？"))).isFalse();
        assertThat(contract.coveredQuestion(question("医院代码映射的审批状态是什么？"))).isFalse();
    }

    @Test
    void anExistingBoundPendingMappingCoversOnlyItsOwnShortWarning() {
        var contract = contract(MATERIAL, List.of());
        var decisions = ConfirmedDecisionSet.from(List.of(new PlanAnswer("mapping-a",
                "甲院与 hospital_id 的对应关系是什么？", "暂不确定。医院代码待核实。")));
        assertThat(contract.coveredByBoundPending("甲院与 hospital_id 的对应关系尚未确定。", decisions)).isTrue();
        assertThat(contract.coveredByBoundPending("乙院与 hospital_id 的对应关系尚未确定。", decisions)).isFalse();
    }

    @Test
    void translationAndOrdinarySoftwareTasksDoNotAcquireClinicalMappingQuestions() {
        var translation = NamedIdentifierContract.from("只将这段话翻译为英文，不添加说明：\n"
                + "“为甲院和乙院2025年普通门诊制定候诊时间比较方案。”", context(MATERIAL), List.of(), List.of());
        assertThat(translation.requiredQuestions()).isEmpty();
        assertThat(translation.guidance()).isEmpty();
        var software = NamedIdentifierContract.from("使用Java21实现整数排序，交付代码与测试。", context(MATERIAL), List.of(), List.of());
        assertThat(software.requiredQuestions()).isEmpty();
    }

    @Test
    void pendingPrefixSuggestionAndSameInstitutionContradictionAreNeverPartialConfirmation() {
        for (String answer : List.of("暂不确定，甲院的hospital_id=B。", "建议甲院的hospital_id=B，乙院的hospital_id尚未确定。",
                "甲院的hospital_id=B，甲院的hospital_id尚未确定。")) {
            var contract = contract(MATERIAL, List.of(new PlanAnswer("mapping-a", "甲院与 hospital_id 的对应关系是什么？", answer)));
            assertThat(contract.requiredQuestions()).as(answer).hasSize(2);
        }
    }

    @Test
    void multilineCodeDictionaryCannotHideConcreteBindingsBehindQuotedKeys() {
        assertThatThrownBy(() -> contract(MATERIAL, List.of()).validate(
                "```python\nhospital_map = {\n  \"甲院\": \"A\",\n  \"乙院\": \"B\"\n}\n```", "sections.OUTPUT"))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void serverRequiresMappingQuestionsEvenWhenTheProviderReturnsNoQuestions() {
        var plan = preparedPlanning(MATERIAL, new AtomicInteger(), "");
        assertThat(plan.summary()).contains("对应关系还需独立确认");
        assertThat(plan.questions()).hasSize(2).allSatisfy(question -> {
            assertThat(question.question()).contains("hospital_id");
            assertThat(question.options()).isEmpty();
        });
        assertThat(plan.planId()).isNotBlank();
    }

    @Test
    void knownMappingQuestionsAreRemovedWhileAnIndependentNewFieldQuestionRemains() {
        var clock = Clock.fixed(Instant.parse("2026-10-07T00:00:00Z"), ZoneOffset.UTC);
        var sessions = sessions(clock, MATERIAL + "甲院的hospital_id=B。乙院的hospital_id=A。");
        var prepared = sessions.prepareContext(new PlanningContextRequest(RAW,
                new ContextAnalysisRequest("", List.of(new ContextFileInput("docs/两院数据字典.txt", MATERIAL, "text"))),
                PermissionPolicyInput.empty()));
        var planning = new OptimizationPlanningServiceImpl(request -> new PlanningProviderResponse("确认真实未知",
                List.of(question("甲院与 hospital_id 的对应关系是什么？"), new PlanQuestion("approval",
                        "新增汇总的审批负责人尚未明确，由谁核对？", "", PlanQuestionType.FREE_TEXT,
                        List.of(), List.of(), true)), "mock", "planner", true),
                new PromptTemplateRegistryImpl(), sessions, clock);
        var actual = planning.plan(new OptimizationPlanRequest(RAW, "", List.of(),
                new PlanningContextReference(prepared.contextId(), prepared.version())));
        assertThat(actual.questions()).extracting(PlanQuestion::id).containsExactly("approval");
    }

    @Test
    void mappingHintsUseTheSameProviderRepairBudgetInsteadOfInventingABRecommendation() {
        var calls = new AtomicInteger();
        var plan = preparedPlanning(MATERIAL, calls, "甲院（hospital_id=A），乙院（hospital_id=B）。");
        assertThat(calls).hasValue(2);
        assertThat(plan.questions()).allSatisfy(question -> assertThat(question.hint()).doesNotContain("hospital_id=A"));
    }

    @Test
    void nullEmptyAndUnrelatedInputsDoNotCreateAContract() {
        var contract = NamedIdentifierContract.from(null, null, null, null);
        assertThat(contract.guidance()).isEmpty();
        assertThat(contract.pendingStatements()).isEmpty();
        contract.validate("医院A/B代码", "sections.TASK");
    }

    @Test
    void historicalMappingCannotBeReadAsTheCurrentRelation() {
        assertThat(contract(MATERIAL + "此前甲院的hospital_id=A。", List.of()).requiredQuestions()).hasSize(2);
        var corrected = contract(MATERIAL + "此前甲院的hospital_id=A，现在明确甲院的hospital_id=B。", List.of());
        assertThat(corrected.guidance()).contains("甲院 | hospital_id | B | 资料明确");
    }

    private static com.promptoptimizer.enhancement.domain.OptimizationPlan preparedPlanning(String material,
                                                                                           AtomicInteger calls, String invalidHint) {
        var clock = Clock.fixed(Instant.parse("2026-10-07T00:00:00Z"), ZoneOffset.UTC);
        var sessions = sessions(clock, material);
        var prepared = sessions.prepareContext(new PlanningContextRequest(RAW,
                new ContextAnalysisRequest("", List.of(new ContextFileInput("docs/两院数据字典.txt", material, "text"))),
                PermissionPolicyInput.empty()));
        var planning = new OptimizationPlanningServiceImpl(request -> {
            int attempt = calls.incrementAndGet();
            assertThat(request.sourceObjectGuidance()).contains("hospital_id", "对应关系待确认");
            return new PlanningProviderResponse("确认对应关系", invalidHint.isEmpty() ? List.of() : List.of(
                    new PlanQuestion("detail", "还有哪些真实缺口需要说明？", attempt == 1 ? invalidHint : "只补充有依据的资料。",
                            PlanQuestionType.FREE_TEXT, List.of(), List.of(), true)), "mock", "planner", true);
        }, new PromptTemplateRegistryImpl(), sessions, clock);
        return planning.plan(new OptimizationPlanRequest(RAW, "", List.of(),
                new PlanningContextReference(prepared.contextId(), prepared.version())));
    }

    private static PlanningSessionServiceImpl sessions(Clock clock, String material) {
        return new PlanningSessionServiceImpl(new InMemoryPlanningSessionStore(clock), request -> context(material),
                new ProtectedContextFilterImpl(), TestActors.currentActor(), clock);
    }

    private static NamedIdentifierContract contract(String material, List<PlanAnswer> answers) {
        return NamedIdentifierContract.from(RAW, context(material), List.of(),
                ConfirmedDecisionSet.from(answers).decisions());
    }

    private static PlanQuestion question(String text) {
        return new PlanQuestion("question", text, "", PlanQuestionType.FREE_TEXT, List.of(), List.of(), true);
    }

    private static com.promptoptimizer.enhancement.domain.OptimizationResult assemble(String output) {
        var response = new EnhancementProviderResponse(List.of(
                new PromptSection(PromptSectionType.BACKGROUND, "背景", "两院普通门诊合成资料。"),
                new PromptSection(PromptSectionType.TASK, "任务", "制定候诊时间比较方案。"),
                new PromptSection(PromptSectionType.OUTPUT, "输出", output),
                new PromptSection(PromptSectionType.CONSTRAINTS, "约束", "不虚构数据，不输出诊疗建议。")),
                "test", "test", false, List.of());
        return new OptimizationResultAssembler().assemble(response, context(MATERIAL),
                new PromptTemplate(TemplateCode.RESEARCH_ANALYSIS, "交付指标表。", "范围一致。", "示例"),
                List.of(), List.of(), false, List.of(), false, 1, RAW, List.of(), List.of());
    }

    private static ContextSnapshot context(String material) {
        return new ContextSnapshot("", List.of(), List.of(), List.of(),
                List.of(new FileSnippet("docs/两院数据字典.txt", "text", material, "候诊数据字段", false)),
                List.of(), List.of(), "v1");
    }
}
