package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.context.domain.FileSnippet;
import com.promptoptimizer.context.dto.ContextAnalysisRequest;
import com.promptoptimizer.context.dto.PlanningContextRequest;
import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.PlanQuestionType;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.OptimizationPlanRequest;
import com.promptoptimizer.enhancement.dto.PermissionPolicyInput;
import com.promptoptimizer.enhancement.dto.PlanningContextReference;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.identity.support.TestActors;
import com.promptoptimizer.policy.service.impl.ProtectedContextFilterImpl;
import com.promptoptimizer.provider.domain.EnhancementProviderResponse;
import com.promptoptimizer.provider.domain.PlanningProviderResponse;
import com.promptoptimizer.template.domain.PromptTemplate;
import com.promptoptimizer.template.service.impl.PromptTemplateRegistryImpl;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实联合对应题与直接提醒的重复反例；新增审批、年份、对象和条件不得因同字段被删除。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class NamedIdentifierReminderCoverageTest {
    private static final String RAW = "为甲院和乙院2025年普通门诊制定候诊时间比较方案，交付清洗步骤和指标表。";
    private static final String MATERIAL = "CSV字段包含hospital_id，hospital_id只能为A或B。";
    private static final String JOINT = "甲院和乙院分别对应的hospital_id取值（A或B）尚未确认，影响两院独立计算和比较的医院分组。";

    @Test
    void actualJointCodeQuestionsAreCoveredByTwoIndependentRelationQuestions() {
        var relation = contract();
        for (String text : List.of("数据中的医院代码 A、B 分别对应甲院还是乙院？",
                "数据中的 hospital_id 为 A 和 B，请确认 A、B 分别对应哪家医院？",
                "数据中的 hospital_id 为 A 和 B，请分别说明它们对应甲院还是乙院？",
                "数据中的hospital_id与甲院、乙院的对应关系是什么？",
                "数据中的医院代码A和B分别对应甲院和乙院中的哪一家？")) {
            assertThat(relation.coveredQuestion(question(text))).as(text).isTrue();
        }
    }

    @Test
    void actualPlanningFlowReplacesPureJointQuestionButKeepsANewApprovalDecision() {
        var clock = Clock.fixed(Instant.parse("2026-10-07T00:00:00Z"), ZoneOffset.UTC);
        var sessions = new PlanningSessionServiceImpl(new InMemoryPlanningSessionStore(clock), request -> context(),
                new ProtectedContextFilterImpl(), TestActors.currentActor(), clock);
        var prepared = sessions.prepareContext(new PlanningContextRequest(RAW, new ContextAnalysisRequest("", List.of()),
                PermissionPolicyInput.empty()));
        var planning = new OptimizationPlanningServiceImpl(request -> new PlanningProviderResponse("确认真实缺口", List.of(
                question("数据中的 hospital_id 为 A 和 B，请确认 A、B 分别对应哪家医院？"),
                new PlanQuestion("approval", "新增跨院共享由谁审批？", "", PlanQuestionType.FREE_TEXT,
                        List.of(), List.of(), true)), "mock", "planner", true), new PromptTemplateRegistryImpl(), sessions, clock);
        var plan = planning.plan(new OptimizationPlanRequest(RAW, "", List.of(),
                new PlanningContextReference(prepared.contextId(), prepared.version())));
        assertThat(plan.questions()).extracting(PlanQuestion::id)
                .containsExactly("entity-id-relation-1", "entity-id-relation-2", "approval");
    }

    @Test
    void directModelJointReminderIsRepresentedOncePerIndependentInstitution() {
        for (String statement : List.of(JOINT,
                "甲院与乙院的 hospital_id 对应关系（哪个代码对应甲院、哪个对应乙院）尚无证据，"
                        + "影响两院独立计算与比较结果的归属，需确认。",
                "甲院和乙院分别对应的hospital_id取值（A或B）未确定，影响两院独立计算和比较的医院维度分组。",
                "甲院与乙院分别对应哪个 hospital_id（A 或 B）？当前无对应证据，正文、表格及伪代码中未知项用"
                        + "“医院代码待确认”占位；仅依赖该对应的步骤需等待，其他清洗与指标模板继续交付。",
                "甲院与乙院在数据说明中仅以hospital_id取值A或B出现，未提供A/B与甲院乙院的对应关系；"
                        + "该对应未确认前，涉及具体院区名称的清洗、分组与比较步骤需等待，其他清洗与指标模板可继续交付。")) {
            var result = assemble(List.of(statement));
            assertThat(result.ambiguities()).as(statement).hasSize(2);
            assertThat(result.optimizedPrompt()).doesNotContain(statement);
            assertThat(result.ambiguities()).anyMatch(value -> value.startsWith("甲院与 hospital_id"))
                    .anyMatch(value -> value.startsWith("乙院与 hospital_id"));
            assertThat(result.sections()).extracting(PromptSection::type)
                    .contains(PromptSectionType.BACKGROUND, PromptSectionType.TASK, PromptSectionType.OUTPUT, PromptSectionType.CONSTRAINTS);
        }
    }

    @Test
    void aRelationReminderCannotConsumeNewApprovalYearObjectOrConditionalChoices() {
        for (String statement : List.of("甲院与hospital_id的对应关系未核实，新增跨院共享还需审批。",
                "甲院2026年与hospital_id的对应关系未核实。", "丙院与hospital_id的对应关系未核实。",
                "如果以后确认甲院与hospital_id的对应关系，是否重新核实历史数据？",
                "甲院与hospital_id的对应关系未核实，建议选择A还是B？",
                "甲院与hospital_id的对应关系尚无证据，另需决定绩效评分规则。")) {
            assertThat(assemble(List.of(statement)).ambiguities()).as(statement).contains(statement);
        }
    }

    @Test
    void actualBoundCodeConsequencesDoNotAddTwoMoreHospitalDecisions() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        try (var input = getClass().getResourceAsStream("/enhancement/bound-window-actual.json")) {
            var fixture = mapper.readTree(input);
            var answers = mapper.convertValue(fixture.get("answers"),
                    new com.fasterxml.jackson.core.type.TypeReference<List<PlanAnswer>>() { });
            for (String consequence : List.of("无法确定A/B代码分别对应哪家医院。", "影响按医院分组和比较。")) {
                var findings = List.of("甲院与hospital_id的对应关系尚未核实，" + consequence,
                        "乙院与hospital_id的对应关系尚未核实，" + consequence);
                var result = assemble(findings, fixture.get("rawPrompt").asText(), answers);
                assertThat(result.ambiguities()).as(consequence).hasSize(5);
                assertThat(result.optimizedPrompt()).doesNotContain(findings.getFirst(), findings.getLast())
                        .contains("不能用另一院排除推定", "不能用另一院的窗口替代", "90分钟");
            }
        }
    }

    @Test
    void anInverseCodeConsequenceDoesNotConsumeAnAdditionalDecisionOrConfirmedRelation() {
        String repeated = "甲院与hospital_id的对应关系尚未核实，无法确定A/B代码分别对应哪家医院";
        assertThat(assemble(List.of(repeated + "。")).ambiguities()).hasSize(2);
        for (String added : List.of("；丙院的医院代码尚未确定。", "；另需确认绩效评分分母。",
                "；科室代码的对应关系尚未核实。", "；2026年的关系需要重新核实。",
                "；如果以后确认，还需审批共享权限。", "；两院使用相同代码是否属于合法重复尚未确定。")) {
            assertThat(assemble(List.of(repeated + added)).ambiguities()).as(added).contains(repeated + added);
        }
        var confirmed = NamedIdentifierContract.from(RAW, context(), List.of(),
                ConfirmedDecisionSet.from(List.of(new PlanAnswer("jia", "甲院与hospital_id的对应关系是什么？", "A")))
                        .decisions());
        assertThat(confirmed.coveredPendingStatement(repeated + "。")).isFalse();
    }

    @Test
    void aMappingQuestionWithAnotherFieldOrConditionIsNotCovered() {
        for (String text : List.of("甲院与hospital_id的对应关系是什么，科室代码又是什么？",
                "甲院2026年与hospital_id的对应关系是什么？", "甲院的医院代码来源是否已获授权？",
                "两院医院代码校验失败后怎样处理？", "如果以后确认甲院与hospital_id的对应关系，如何迁移？")) {
            assertThat(contract().coveredQuestion(question(text))).as(text).isFalse();
        }
    }

    @Test
    void aNewScopeInTheHintIsNotLostWhenTheQuestionUsesTheKnownField() {
        for (String hint : List.of("请核对2026年的对应关系。", "新增跨院共享还需独立审批。", "丙院也使用该字段。")) {
            var question = new PlanQuestion("mapping", "甲院与hospital_id的对应关系是什么？", hint,
                    PlanQuestionType.FREE_TEXT, List.of(), List.of(), true);
            assertThat(contract().coveredQuestion(question)).as(hint).isFalse();
        }
    }

    @Test
    void anExistingIndependentReminderRetainsItsExplanationWithoutAnotherShortCopy() {
        String first = "甲院的 hospital_id 对应关系尚无证据，无法确定甲院对应 A 还是 B；"
                + "影响按医院分组、两院比较及所有依赖医院标识的统计步骤。";
        String second = first.replace("甲院", "乙院");
        var result = assemble(List.of(first, second));
        assertThat(result.ambiguities()).containsExactly(first, second);
        assertThat(result.optimizedPrompt()).contains(first, second);
    }

    @Test
    void jointCoverageKeepsAllNewConditionsAndDoesNotEraseTheOtherOwnersState() {
        String joint = "甲院与乙院分别对应的hospital_id取值（A或B）尚未确认，影响两院独立计算和比较的标识映射。";
        assertThat(assemble(List.of(joint)).ambiguities()).containsExactly(joint);
        String approval = "甲院的hospital_id对应关系尚无证据，新增跨院共享还需独立审批。";
        assertThat(assemble(List.of(approval)).ambiguities()).contains(approval,
                "乙院与 hospital_id 的对应关系尚未确定。").hasSize(2);
        String anotherYear = "甲院2026年的hospital_id对应关系尚无证据。";
        assertThat(assemble(List.of(anotherYear)).ambiguities()).contains(anotherYear,
                "甲院与 hospital_id 的对应关系尚未确定。", "乙院与 hospital_id 的对应关系尚未确定。");
    }

    @Test
    void anInverseFieldDeclarationPreservesItsCompleteExplanationWithoutShortCopies() {
        String actual = "医院代码与甲院/乙院的对应关系未确认：数据中hospital_id只有A和B，"
                + "但未说明A对应甲院还是乙院。这会影响两院独立计算和比较的归属，需用户确认对应关系。";
        assertThat(assemble(List.of(actual)).ambiguities()).containsExactly(actual);
        String anotherField = "科室代码与甲院/乙院的对应关系未确认。";
        assertThat(assemble(List.of(anotherField)).ambiguities()).contains(anotherField,
                "甲院与 hospital_id 的对应关系尚未确定。", "乙院与 hospital_id 的对应关系尚未确定。");
    }

    @Test
    void relationCoverageUsesTheNamedObjectsFieldAndUnknownStateInsteadOfWordOrder() {
        for (String actual : List.of("甲院与乙院在 hospital_id 上的对应关系尚未确认（无对应证据）。"
                + "该对应关系影响按医院分组、两院独立计算及比较结果，需确认甲院和乙院分别对应 A 还是 B。",
                "hospital_id 中 A/B 与甲院/乙院的对应关系未在资料中说明，影响两院独立计算和比较。")) {
            assertThat(assemble(List.of(actual)).ambiguities()).as(actual).containsExactly(actual);
        }
        for (String actual : List.of("甲院与乙院在科室代码上的对应关系尚未确认。",
                "甲院与乙院2026年在hospital_id上的对应关系尚未确认。",
                "如果以后确认，甲院与乙院在hospital_id上的对应关系将不再未确认。")) {
            assertThat(assemble(List.of(actual)).ambiguities()).as(actual).contains(actual,
                    "甲院与 hospital_id 的对应关系尚未确定。", "乙院与 hospital_id 的对应关系尚未确定。");
        }
    }

    @Test
    void discardedPureMappingOptionsDoNotSpendAnAdditionalProviderRepairCall() {
        var clock = Clock.fixed(Instant.parse("2026-10-07T00:00:00Z"), ZoneOffset.UTC);
        var sessions = new PlanningSessionServiceImpl(new InMemoryPlanningSessionStore(clock), request -> context(),
                new ProtectedContextFilterImpl(), TestActors.currentActor(), clock);
        var prepared = sessions.prepareContext(new PlanningContextRequest(RAW, new ContextAnalysisRequest("", List.of()),
                PermissionPolicyInput.empty()));
        var calls = new AtomicInteger();
        var planning = new OptimizationPlanningServiceImpl(request -> {
            calls.incrementAndGet();
            return new PlanningProviderResponse("确认对应", List.of(new PlanQuestion("joint",
                    "数据中的医院代码 A、B 分别对应甲院还是乙院？", "甲院（hospital_id=A），乙院（hospital_id=B）。",
                    PlanQuestionType.FREE_TEXT, List.of(), List.of(), true)), "mock", "planner", true);
        }, new PromptTemplateRegistryImpl(), sessions, clock);
        var plan = planning.plan(new OptimizationPlanRequest(RAW, "", List.of(),
                new PlanningContextReference(prepared.contextId(), prepared.version())));
        assertThat(calls).hasValue(1);
        assertThat(plan.questions()).hasSize(2).allSatisfy(question -> assertThat(question.hint()).doesNotContain("hospital_id=A"));
    }

    private static NamedIdentifierContract contract() {
        return NamedIdentifierContract.from(RAW, context(), List.of(), List.of());
    }

    private static PlanQuestion question(String text) {
        return new PlanQuestion("model-mapping", text, "", PlanQuestionType.FREE_TEXT, List.of(), List.of(), true);
    }

    private static com.promptoptimizer.enhancement.domain.OptimizationResult assemble(List<String> findings) {
        return assemble(findings, RAW, List.of());
    }

    private static com.promptoptimizer.enhancement.domain.OptimizationResult assemble(
            List<String> findings, String raw, List<PlanAnswer> answers) {
        var response = new EnhancementProviderResponse(List.of(
                new PromptSection(PromptSectionType.BACKGROUND, "背景", "两院普通门诊合成资料。"),
                new PromptSection(PromptSectionType.TASK, "任务", "制定候诊时间比较方案。"),
                new PromptSection(PromptSectionType.OUTPUT, "输出", "交付清洗步骤和指标表，不自行选定未知关系。"),
                new PromptSection(PromptSectionType.CONSTRAINTS, "约束", "不编造数据，不输出诊疗建议。")),
                "test", "test", false, findings);
        return new OptimizationResultAssembler().assemble(response, context(),
                new PromptTemplate(TemplateCode.RESEARCH_ANALYSIS, "交付指标表。", "范围一致。", "示例"),
                List.of(), answers, !answers.isEmpty(), List.of(), false, 1, raw, List.of(), List.of());
    }

    private static ContextSnapshot context() {
        return new ContextSnapshot("", List.of(), List.of(), List.of(),
                List.of(new FileSnippet("docs/两院数据说明.txt", "text", MATERIAL, "字段说明", false)),
                List.of(), List.of(), "v1");
    }
}
