package com.promptoptimizer.enhancement.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.enhancement.domain.PlanOption;
import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.provider.domain.PlanningProviderRequest;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回放真实 Pro 的指南写法追问：忠于材料并按现行界面回退时，不让用户另选一种叙述。
 * 完整候选保留用于校验；同题夹带授权、专业口径、跨设备行为或发布选择时仍须保留。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class RoutineGuideWordingRegressionTest {
    private static final String RESOURCE = "/plan-regression/guide-wording-full-20261005.json";

    @ParameterizedTest
    @MethodSource("actualQuestions")
    void delegatesACompleteActualWordingQuestionWithoutChoosingAnyUnverifiedCandidate(
            String raw, PlanQuestion question) {
        assertThat(RoutineGuideDecision.delegated(question, raw)).as(question.id()).isTrue();
        assertThat(new PlanQuestionFilter().filter(List.of(question),
                new PlanningProviderRequest(raw, "", List.of()))).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("independentDecisionsInEveryField")
    void keepsIndependentDecisionsEvenWhenTheyOnlyAppearInTheLastCandidateOrItsReason(
            String raw, PlanQuestion question, String condition, Field field) {
        assertThat(RoutineGuideDecision.delegated(withCondition(question, condition, field), raw))
                .as("%s / %s / %s", question.id(), field, condition).isFalse();
    }

    @ParameterizedTest
    @MethodSource("explicitFactChecks")
    void keepsAnExplicitUserRequestToConfirmUnknownFactsBeforeWriting(
            String raw, PlanQuestion question, String instruction) {
        assertThat(RoutineGuideDecision.delegated(question, raw + "\n" + instruction)).isFalse();
    }

    @ParameterizedTest
    @MethodSource("missingFallbacks")
    void doesNotDelegateWithoutBothTruthfulMaterialsAndAnInterfaceFallback(
            String raw, PlanQuestion question) {
        assertThat(RoutineGuideDecision.delegated(question, raw)).isFalse();
    }

    @ParameterizedTest
    @MethodSource("otherGoals")
    void doesNotDelegateTheSameWordingForAnotherActualDeliveryGoal(String raw, PlanQuestion question) {
        assertThat(RoutineGuideDecision.delegated(question, raw)).isFalse();
    }

    /** 历史标签虽然为 L，本次真实原文只有 1321 字符；不是 3500 字符以上长需求的验收。 */
    private static Stream<Arguments> actualQuestions() throws IOException {
        Replay replay = replay();
        return replay.questions().stream().map(question -> Arguments.of(replay.raw(), question));
    }

    /** 每个候选字段都独立承载业务风险，不能因选项标题看起来只是写法而跳过详情。 */
    private static Stream<Arguments> independentDecisionsInEveryField() throws IOException {
        Replay replay = replay();
        List<String> conditions = List.of(
                "另外需确认上传资料的授权范围。",
                "删除后还能跨设备同步清除记录。",
                "评分观察窗口为七天，需选定医学统计分母。",
                "恢复时间>=30天时采用另一处理流程。",
                "本次希望重新设计删除后的反馈方式。",
                "本次是否作为正式发布公告，需先决定审批流程。",
                "新增自动恢复历史记录的功能。",
                "模型失败后自动恢复之前所有历史版本。"
        );
        return replay.questions().stream().flatMap(question -> conditions.stream().flatMap(condition ->
                Stream.of(Field.values()).map(field -> Arguments.of(replay.raw(), question, condition, field))));
    }

    /** 用户主动请求核实或选择时不擅自委派，即使其此前已要求不要编造现状。 */
    private static Stream<Arguments> explicitFactChecks() throws IOException {
        Replay replay = replay();
        return replay.questions().stream().flatMap(question -> Stream.of(
                        "请先向我确认材料未说明的产品行为。",
                        "需要由我决定删除后如何提示。",
                        "希望询问我失败重试的具体操作。",
                        "材料未核实的界面表现需要我确认。")
                .map(instruction -> Arguments.of(replay.raw(), question, instruction)));
    }

    /** 缺少材料真实性约束或界面回退时，不把写法题静默视为已经处理。 */
    private static Stream<Arguments> missingFallbacks() throws IOException {
        Replay replay = replay();
        return replay.questions().stream().flatMap(question -> Stream.of(
                        "请编写中文用户指南，按钮名称以当前界面为准。",
                        "请编写中文用户指南，不编造未说明的产品行为。")
                .map(raw -> Arguments.of(raw, question)));
    }

    /** 仅指南写法能委派，开发、科研、医院和法律任务中的同句仍由各自决策链处理。 */
    private static Stream<Arguments> otherGoals() throws IOException {
        Replay replay = replay();
        return replay.questions().stream().flatMap(question -> Stream.of(
                        "请开发历史删除与失败重试功能，不编造现状，以当前界面为准。",
                        "请撰写论文研究方案，不编造实验结果，以当前界面为准。",
                        "请整理医院运营统计分析方案，不编造结果，以当前界面为准。",
                        "请撰写法律材料整理报告，不编造法律意见，以当前界面为准。")
                .map(raw -> Arguments.of(raw, question)));
    }

    /** 只改一个字段并保留其余真实题目元数据，覆盖完整候选检查而非只查题干。 */
    private static PlanQuestion withCondition(PlanQuestion question, String condition, Field field) {
        String text = field == Field.QUESTION
                ? question.question().replaceFirst("[？?]$", "") + "，" + condition + "？" : question.question();
        String hint = field == Field.HINT ? question.hint() + " " + condition : question.hint();
        var examples = new ArrayList<>(question.examples());
        if (field == Field.EXAMPLE) examples.add(condition);
        var options = new ArrayList<>(question.options());
        int last = options.size() - 1;
        PlanOption option = options.get(last);
        options.set(last, new PlanOption(option.id(),
                field == Field.OPTION_LABEL ? option.label() + condition : option.label(),
                field == Field.OPTION_DESCRIPTION ? option.description() + condition : option.description(),
                field == Field.OPTION_ANSWER ? option.answer() + condition : option.answer(),
                option.recommended(),
                field == Field.OPTION_REASON ? option.recommendationReason() + condition : option.recommendationReason()));
        return new PlanQuestion(question.id(), text, hint, question.type(), List.copyOf(options),
                List.copyOf(examples), question.allowCustomAnswer());
    }

    /** 缺少真实失败证据时明确失败，不用空列表冒充去重回放通过。 */
    private static Replay replay() throws IOException {
        try (var stream = RoutineGuideWordingRegressionTest.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) throw new IOException("Missing guide wording replay fixture");
            var mapper = new ObjectMapper();
            var source = mapper.readTree(stream);
            List<PlanQuestion> questions = mapper.convertValue(source.path("questions"), mapper.getTypeFactory()
                    .constructCollectionType(List.class, PlanQuestion.class));
            assertThat(questions).extracting(PlanQuestion::id).containsExactly("history_delete_hint", "retry_method");
            assertThat(source.path("source").path("rawUtf16Length").asInt()).isEqualTo(1321);
            return new Replay(source.path("raw").asText(), List.copyOf(questions));
        }
    }

    private enum Field { QUESTION, HINT, EXAMPLE, OPTION_LABEL, OPTION_DESCRIPTION, OPTION_ANSWER, OPTION_REASON }
    private record Replay(String raw, List<PlanQuestion> questions) { }
}
