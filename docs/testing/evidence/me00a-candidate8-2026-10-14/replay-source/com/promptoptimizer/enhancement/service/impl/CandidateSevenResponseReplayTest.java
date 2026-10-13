package com.promptoptimizer.enhancement.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.provider.domain.DraftDeliveryContract;
import com.promptoptimizer.provider.domain.PlanningProviderRequest;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException;
import com.promptoptimizer.provider.domain.TaskEnhancementAssessment;
import com.promptoptimizer.template.domain.PromptTemplate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 用候选七不可改写的真实原件锁定协议、局部交付与评价对象边界，不调用外部模型。
 * @author QingNiao
 * @since 0.1.0
 */
class CandidateSevenResponseReplayTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void shouldKeepTheActualDelegatedReviewerQuestionOutOfRepeatedPlanning() throws Exception {
        var input = fixture("q05-flash/input.json");
        var saved = fixture("q05-flash/plan.json").path("body").path("data");
        var questions = new ArrayList<PlanQuestion>();
        for (var question : saved.path("questions")) questions.add(json.treeToValue(question, PlanQuestion.class));
        var request = new PlanningProviderRequest(input.path("rawPrompt").asText(), "", List.of(), null);
        assertThat(new PlanQuestionFilter().filter(questions, request)).extracting(PlanQuestion::id)
                .doesNotContain("disagreement_rule");
    }

    @Test
    void shouldAcceptMethodProseWithLocalBlankTablesFromTheSecondActualResponse() throws Exception {
        String raw = fixture("q05-flash/input.json").path("rawPrompt").asText();
        String output = section(model(179), "OUTPUT");
        var contract = DraftDeliveryContract.from(raw,
                new PromptTemplate(TemplateCode.RESEARCH_ANALYSIS, "交付方法正文。", "保持研究目标。", "遵守原文。"), List.of());
        assertThat(output).contains("方法部分正文", "方法部分只交付方法与空表模板");
        assertThatCode(() -> contract.validateDelivery(output, "sections.OUTPUT")).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"方法部分只交付空表模板。", "方法部分只交付方法提纲与空表模板。",
            "只交付方案与空表模板。", "方法部分只交付方法与空表模板，不提供正文。"})
    void shouldStillRejectPreparationThatReplacesTheRequestedProse(String restriction) throws Exception {
        String raw = fixture("q05-flash/input.json").path("rawPrompt").asText();
        var contract = DraftDeliveryContract.from(raw,
                new PromptTemplate(TemplateCode.RESEARCH_ANALYSIS, "交付方法正文。", "保持研究目标。", "遵守原文。"), List.of());
        assertThatThrownBy(() -> contract.validateDelivery("交付中文研究设计的方法部分正文。" + restriction,
                "sections.OUTPUT")).isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void shouldRejectTheActualPromptOrWorkEvaluationSubstitution() throws Exception {
        var input = fixture("q05-flash/input.json");
        var task = EffectiveTaskViewFactory.create(input.path("rawPrompt").asText(), TemplateCode.AUTO,
                List.of(), List.of(), false);
        String content = section(model(182), "TASK");
        assertThat(content).contains("提示词或对应产物");
        assertThatThrownBy(() -> task.meaning().validate(content, "sections.TASK"))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void shouldKeepProfessionalGapAndUserQuestionInconsistencyRejected() throws Exception {
        var response = model(180);
        var assessment = json.treeToValue(response.path("assessment"), TaskEnhancementAssessment.class);
        List<String> findings = List.of(json.treeToValue(response.path("ambiguities"), String[].class));
        assertThatThrownBy(() -> assessment.validateModelDecisions(findings))
                .isInstanceOfSatisfying(ProviderResponseValidationException.class, failure ->
                        assertThat(failure.getReason()).isEqualTo(
                                ProviderResponseValidationException.Reason.ASSESSMENT_DECISION_CONFLICT));
    }

    @Test
    void shouldSendSharedStatementsOnceWithoutConfirmingAnyUnknownParameter() throws Exception {
        var input = fixture("q05-flash/input.json");
        var answers = new ArrayList<PlanAnswer>();
        for (var answer : input.path("planConfirmation").path("answers"))
            answers.add(json.treeToValue(answer, PlanAnswer.class));
        var task = EffectiveTaskViewFactory.create(input.path("rawPrompt").asText(), TemplateCode.AUTO,
                answers, List.of(), false);
        var payload = json.valueToTree(task.modelInput());
        assertThat(task.confirmedDecisions()).hasSize(5).allSatisfy(decision ->
                assertThat(decision.scope()).isEqualTo(com.promptoptimizer.enhancement.domain.ConfirmedPlanDecision.Scope.UNRESOLVED));
        assertThat(task.pendingDecisions()).hasSize(5);
        assertThat(payload.path("executionRequirements")).hasSize(4);
        for (var statement : payload.path("executionRequirements"))
            assertThat(statement.path("sourceQuestionIds")).hasSize(5);
        for (var decision : payload.path("pendingDecisions")) {
            assertThat(decision.path("scope").asText()).isEqualTo("UNRESOLVED");
            assertThat(decision.path("designDelegated").asBoolean()).isTrue();
            assertThat(decision.path("answer").asText()).isEqualTo("不确认任何既有参数。");
        }
    }

    /** 原件位置只沿当前项目目录向上查找；测试不能读取私有凭据或改写评测材料。 */
    private JsonNode fixture(String name) throws Exception {
        Path base = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (base != null) {
            Path file = base.resolve("docs/testing/evidence/me00a-candidate7-2026-10-13").resolve(name);
            if (Files.isRegularFile(file)) return json.readTree(Files.readString(file));
            base = base.getParent();
        }
        throw new IllegalStateException("缺少候选七评测原件：" + name);
    }

    /** 从保存的供应商响应读取模型原文，不对失败响应进行格式或语义补写。 */
    private JsonNode model(int attempt) throws Exception {
        var trace = fixture("traces/" + attempt + ".json");
        return json.readTree(trace.path("response").path("choices").get(0).path("message").path("content").asText());
    }

    private String section(JsonNode response, String type) {
        for (var value : response.path("sections")) if (type.equals(value.path("type").asText())) return value.path("content").asText();
        throw new IllegalArgumentException("原件缺少段落：" + type);
    }
}
