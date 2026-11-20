package com.promptoptimizer.textpolish.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.textpolish.domain.TextPolishModels.Input;
import com.promptoptimizer.textpolish.service.TextPolishSemanticReview.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 回放人工标定的判断，验证证据与异常的处置，不用夹具成绩估计模型准确率。 */
class TextPolishSemanticReviewTest {
    private final ObjectMapper json = new ObjectMapper();
    private final TextPolishSemanticReview review = new TextPolishSemanticReview(new TextPolishFidelity(), json);

    static Stream<Arguments> calibrationCases() {
        // 前四对来自归档失败段，其余用于覆盖关系变化、等义和不确定。
        return Stream.of(
                Arguments.of("服务说明遗漏体验目标",
                        "为了进一步保障用户权益，我们将持续不断地优化服务流程，为用户提供更加优质的服务体验。",
                        "我们还会继续优化服务流程，更好地保障用户权益。",
                        List.of(
                                unit(Kind.PURPOSE, Status.PRESERVED, "保障用户权益", "保障用户权益"),
                                unit(Kind.COMMITMENT, Status.PRESERVED, "我们将持续不断地优化服务流程", "我们还会继续优化服务流程"),
                                unit(Kind.PURPOSE, Status.OMITTED, "为用户提供更加优质的服务体验", null)),
                        Outcome.DISCREPANCY),
                Arguments.of("客服期限相同但义务口径发生变化",
                        "客服收到申请后，应在3个工作日内确认材料是否齐全。",
                        "客服收到申请后，会在3个工作日内确认材料是否齐全。",
                        List.of(unit(Kind.OBLIGATION, Status.CHANGED, "应在3个工作日内确认", "会在3个工作日内确认")),
                        Outcome.DISCREPANCY),
                Arguments.of("擅自指定期限起算事件",
                        "只有材料齐全时，才开始计算后续5个工作日的处理期限。",
                        "材料齐全的，自确认之日起计算后续5个工作日的处理期限。",
                        List.of(
                                unit(Kind.CONDITION, Status.PRESERVED, "只有材料齐全时", "材料齐全的"),
                                unit(Kind.CONDITION, Status.ADDED, null, "自确认之日起")),
                        Outcome.DISCREPANCY),
                Arguments.of("自然稿时序倒置",
                        "那天我没急着把闹钟关掉。它响完一遍，屋里又安静了。",
                        "那天闹钟响完一遍，我没急着关掉。屋里又安静了。",
                        List.of(unit(Kind.CHRONOLOGY, Status.CHANGED, "我没急着把闹钟关掉。它响完一遍",
                                "闹钟响完一遍，我没急着关掉")),
                        Outcome.DISCREPANCY),
                Arguments.of("数字照旧但期限被绑到错误动作",
                        "收到申请后3日内确认材料。\n\n确认齐全后5日内处理。",
                        "收到申请后5日内确认材料。\n\n确认齐全后3日内处理。",
                        List.of(new Assessment(Kind.CONDITION, Status.CHANGED,
                                List.of(new Evidence("S1", "3日内确认材料"), new Evidence("S2", "5日内处理")),
                                List.of(new Evidence("D1", "5日内确认材料"), new Evidence("D2", "3日内处理")))),
                        Outcome.DISCREPANCY),
                Arguments.of("未确认的原因变成已确认的因果",
                        "试点期间排队减少，尚不能确定是否由新系统引起。",
                        "试点期间排队减少，这是新系统带来的结果。",
                        List.of(unit(Kind.ATTRIBUTION, Status.CHANGED, "尚不能确定是否由新系统引起",
                                "这是新系统带来的结果")),
                        Outcome.DISCREPANCY),
                Arguments.of("合并表达但两个目的分别有据",
                        "为保障用户权益，我们将持续优化服务流程，为用户提供更好的服务体验。",
                        "我们将持续优化服务流程，保障用户权益并改善服务体验。",
                        List.of(
                                unit(Kind.PURPOSE, Status.PRESERVED, "保障用户权益", "保障用户权益"),
                                unit(Kind.COMMITMENT, Status.PRESERVED, "我们将持续优化服务流程", "我们将持续优化服务流程"),
                                unit(Kind.PURPOSE, Status.PRESERVED, "提供更好的服务体验", "改善服务体验")),
                        Outcome.NO_DISCREPANCY_FOUND),
                Arguments.of("跨段合并，条件和期限保持相连",
                        "只有材料齐全才能计时。\r\n\r\n处理期限为5个工作日。",
                        "材料齐全后才开始计算5个工作日的处理期限。",
                        List.of(new Assessment(Kind.CONDITION, Status.PRESERVED,
                                List.of(new Evidence("S1", "只有材料齐全才能计时"), new Evidence("S2", "处理期限为5个工作日")),
                                List.of(new Evidence("D1", "材料齐全后才开始计算5个工作日的处理期限")))),
                        Outcome.NO_DISCREPANCY_FOUND),
                Arguments.of("不同段落出现相同文字",
                        "资料员核对版本。\n\n资料员核对版本。",
                        "资料员检查版本。\n\n资料员检查版本。",
                        List.of(
                                unit(Kind.FACT, Status.PRESERVED, "资料员核对版本", "资料员检查版本"),
                                new Assessment(Kind.FACT, Status.PRESERVED, List.of(new Evidence("S2", "资料员核对版本")),
                                        List.of(new Evidence("D2", "资料员检查版本")))),
                        Outcome.NO_DISCREPANCY_FOUND),
                Arguments.of("无法确认接收及归档责任归属",
                        "资料员核对版本。如电子图缺页，仍接收纸质图，但缺页补齐前不能办理归档。",
                        "资料员：核对版本。电子图缺页时，仍接收纸质图，补齐前不办理归档。",
                        List.of(unit(Kind.ATTRIBUTION, Status.UNCERTAIN, "仍接收纸质图", "资料员：核对版本。电子图缺页时，仍接收纸质图")),
                        Outcome.INCONCLUSIVE));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("calibrationCases")
    void calibratesDecisionContract(String title, String source, String draft, List<Assessment> units, Outcome expected)
            throws Exception {
        var result = assess(source, draft, units, List.of(), List.of());
        assertThat(result.outcome()).isEqualTo(expected);
        assertThat(result.units()).containsExactlyElementsOf(units);
    }

    @Test
    void rejectsChangedOrDuplicatedLockedQuoteBeforeProducingReviewInput() {
        var original = new Input("原话：“仍需核实。”", null, null, "", "", List.of("“仍需核实。”"), "");
        for (String changed : List.of("原话：“已核实。”", "“仍需核实。”“仍需核实。”")) {
            assertThatThrownBy(() -> review.prepare(original, changed)).isInstanceOfSatisfying(TextPolishException.class,
                    error -> assertThat(error.code()).isEqualTo("TEXT_POLISH_LOCK_CHANGED"));
        }
        assertThat(review.prepare(original, "原话保持为“仍需核实。”").draft()).singleElement()
                .satisfies(p -> assertThat(p.text()).contains("“仍需核实。”"));
    }

    @Test
    void requiresActualEvidenceAtAnUnambiguousLocation() throws Exception {
        for (var evidence : List.of(new Evidence("S1", "找不到"), new Evidence("S1", "甲"), new Evidence("D1", "甲甲"))) {
            var unit = new Assessment(Kind.FACT, Status.PRESERVED, List.of(evidence), List.of(new Evidence("D1", "甲甲")));
            assertThat(assess("甲甲", "甲甲", List.of(unit), List.of(), List.of()).reason()).isEqualTo("INVALID_EVIDENCE");
        }
    }

    @Test
    void distinguishesUncoveredParagraphsFromDeclaredTitles() throws Exception {
        String source = "取件通知\n\n用户须先登记。";
        String draft = "取件通知\n\n请用户先登记。";
        var unit = new Assessment(Kind.OBLIGATION, Status.PRESERVED,
                List.of(new Evidence("S2", "用户须先登记")), List.of(new Evidence("D2", "请用户先登记")));
        assertThat(assess(source, draft, List.of(unit), List.of(), List.of("D1")).reason()).isEqualTo("INCOMPLETE_COVERAGE");
        assertThat(assess(source, draft, List.of(unit), List.of("S1", "S2"), List.of("D1")).reason()).isEqualTo("INCOMPLETE_COVERAGE");
        assertThat(assess(source, draft, List.of(unit), List.of("S1"), List.of("D1")).outcome())
                .isEqualTo(Outcome.NO_DISCREPANCY_FOUND);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"null", "{\"units\":[],\"units\":[]}", "[]", "{\"units\":[]} {}"})
    void malformedReviewCannotPass(String response) {
        var request = review.prepare(input("原稿。"), "成稿。");
        assertThat(review.assess(request, response).outcome()).isEqualTo(Outcome.INCONCLUSIVE);
    }

    private Result assess(String source, String draft, List<Assessment> units, List<String> sourceTitles,
                          List<String> draftTitles) throws Exception {
        return review.assess(review.prepare(input(source), draft),
                json.writeValueAsString(new Report(units, sourceTitles, draftTitles)));
    }

    private static Input input(String source) {
        return new Input(source, null, null, "", "", List.of(), "");
    }

    private static Assessment unit(Kind kind, Status status, String source, String draft) {
        return new Assessment(kind, status, source == null ? List.of() : List.of(new Evidence("S1", source)),
                draft == null ? List.of() : List.of(new Evidence("D1", draft)));
    }
}
