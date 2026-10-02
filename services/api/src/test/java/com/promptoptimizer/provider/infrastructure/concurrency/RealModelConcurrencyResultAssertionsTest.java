package com.promptoptimizer.provider.infrastructure.concurrency;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证付费验收的合成资料断言，防止排版差异误报或规则内容丢失被误判为成功；不访问模型。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class RealModelConcurrencyResultAssertionsTest {
    private static final String PROJECT_RULE = "RESERVATION_WINDOW_8D";
    private static final String DOCUMENT_RULE = "WORKSHOP_CHECKIN_20M";

    @Test
    void acceptsLiteralAndMarkdownEscapedMarkersOnlyWhenLinkedMeaningIsPresent() {
        var literal = RealModelConcurrencyAcceptanceTest.ruleEvidence(
                "规则 RESERVATION_WINDOW_8D：只允许提前八天以内预约。", PROJECT_RULE);
        assertThat(literal.literalMarkerPresent()).isTrue();
        assertThat(literal.presentationMarkerPresent()).isTrue();
        assertThat(literal.linkedBusinessRulePresent()).isTrue();

        var escaped = RealModelConcurrencyAcceptanceTest.ruleEvidence(
                "规则 **RESERVATION\\_WINDOW\\_8D**：最多提前 **8 天**预约。", PROJECT_RULE);
        assertThat(escaped.literalMarkerPresent()).isFalse();
        assertThat(escaped.presentationMarkerPresent()).isTrue();
        assertThat(escaped.linkedBusinessRulePresent()).isTrue();
        assertThat(RealModelConcurrencyAcceptanceTest.ruleEvidence(
                "__RESERVATION_WINDOW_8D__：预约窗口为 8 天。", PROJECT_RULE).linkedBusinessRulePresent()).isTrue();
        for (String separator : new String[]{"\n", "。", "\n\n"}) {
            assertThat(RealModelConcurrencyAcceptanceTest.ruleEvidence(
                    "RESERVATION_WINDOW_8D" + separator + "最多提前八天预约。", PROJECT_RULE)
                    .linkedBusinessRulePresent()).isTrue();
        }
    }

    @Test
    void rejectsMissingMarkerOrWrongReservationWindow() {
        var missing = RealModelConcurrencyAcceptanceTest.ruleEvidence("最多提前八天预约。", PROJECT_RULE);
        assertThat(missing.presentationMarkerPresent()).isFalse();
        assertThat(missing.linkedBusinessRulePresent()).isFalse();
        assertThat(RealModelConcurrencyAcceptanceTest.ruleEvidence(
                "RESERVATION_WINDOW_8D：最多提前九天预约。", PROJECT_RULE).linkedBusinessRulePresent()).isFalse();
        assertThat(RealModelConcurrencyAcceptanceTest.ruleEvidence(
                "RESERVATION_WINDOW_8D：最多提前18天预约。", PROJECT_RULE).linkedBusinessRulePresent()).isFalse();
        assertThat(RealModelConcurrencyAcceptanceTest.ruleEvidence(
                "RESERVATION_WINDOW_8D：最多提前十八天预约。", PROJECT_RULE).linkedBusinessRulePresent()).isFalse();
        assertThat(RealModelConcurrencyAcceptanceTest.ruleEvidence(
                "RESERVATION_WINDOW_8D：最多提前九天预约。其他项目最多提前八天预约。",
                PROJECT_RULE).linkedBusinessRulePresent()).isFalse();
        assertThat(RealModelConcurrencyAcceptanceTest.ruleEvidence(
                "RESERVATION_WINDOW_8D：保留规则代号，其他细节待定。", PROJECT_RULE).linkedBusinessRulePresent()).isFalse();
    }

    @Test
    void rejectsMarkerSubstringOrMeaningInAnUnrelatedParagraph() {
        assertThat(RealModelConcurrencyAcceptanceTest.ruleEvidence(
                "RESERVATION_WINDOW_8D_OLD：最多提前八天预约。", PROJECT_RULE).presentationMarkerPresent()).isFalse();
        assertThat(RealModelConcurrencyAcceptanceTest.ruleEvidence(
                "RESERVATION_WINDOW_8D\n\n其他项目最多提前八天预约。", PROJECT_RULE).linkedBusinessRulePresent()).isFalse();
        assertThat(RealModelConcurrencyAcceptanceTest.ruleEvidence(
                "RESERVATION_WINDOW_8D\r\n\r\n其他项目最多提前八天预约。", PROJECT_RULE).linkedBusinessRulePresent()).isFalse();
    }

    @Test
    void requiresCheckInOpeningTwentyMinutesBeforeTheEvent() {
        var valid = RealModelConcurrencyAcceptanceTest.ruleEvidence(
                "`WORKSHOP_CHECKIN_20M`：开场前二十分钟开放签到。", DOCUMENT_RULE);
        assertThat(valid.presentationMarkerPresent()).isTrue();
        assertThat(valid.linkedBusinessRulePresent()).isTrue();
        assertThat(RealModelConcurrencyAcceptanceTest.ruleEvidence(
                "WORKSHOP_CHECKIN_20M\n开场前二十分钟开始签到。", DOCUMENT_RULE).linkedBusinessRulePresent()).isTrue();
        assertThat(RealModelConcurrencyAcceptanceTest.ruleEvidence(
                "WORKSHOP_CHECKIN_20M\n\n签到开放时间为活动开始前20分钟。", DOCUMENT_RULE).linkedBusinessRulePresent()).isTrue();
        assertThat(RealModelConcurrencyAcceptanceTest.ruleEvidence(
                "WORKSHOP_CHECKIN_20M：开场前三十分钟开放签到。", DOCUMENT_RULE).linkedBusinessRulePresent()).isFalse();
        assertThat(RealModelConcurrencyAcceptanceTest.ruleEvidence(
                "WORKSHOP_CHECKIN_20M：开场前三十分钟开放签到。其他活动开场前二十分钟开放签到。",
                DOCUMENT_RULE).linkedBusinessRulePresent()).isFalse();
        assertThat(RealModelConcurrencyAcceptanceTest.ruleEvidence(
                "WORKSHOP_CHECKIN_20M：开场后二十分钟开放签到。", DOCUMENT_RULE).linkedBusinessRulePresent()).isFalse();
        assertThat(RealModelConcurrencyAcceptanceTest.ruleEvidence(
                "WORKSHOP_CHECKIN_20M：开场前二十分钟发放茶歇。", DOCUMENT_RULE).linkedBusinessRulePresent()).isFalse();
    }

    @Test
    void rejectsClosingCheckInInsteadOfOpeningIt() {
        assertThat(RealModelConcurrencyAcceptanceTest.ruleEvidence(
                "WORKSHOP_CHECKIN_20M：活动开始前二十分钟结束签到。", DOCUMENT_RULE).linkedBusinessRulePresent()).isFalse();
    }

    @Test
    void rejectsBorrowingAnUnrelatedCacheDuration() {
        assertThat(RealModelConcurrencyAcceptanceTest.ruleEvidence(
                "RESERVATION_WINDOW_8D：预约窗口待确认。缓存最多保存八天。", PROJECT_RULE).linkedBusinessRulePresent()).isFalse();
    }

    @Test
    void rejectsNegatedRulesAndConflictingOccurrences() {
        for (String negation : new String[]{"不采用", "不应采用"}) {
            assertThat(RealModelConcurrencyAcceptanceTest.ruleEvidence(
                    "RESERVATION_WINDOW_8D：" + negation + "最多提前八天预约。", PROJECT_RULE)
                    .linkedBusinessRulePresent()).isFalse();
        }
        var conflict = RealModelConcurrencyAcceptanceTest.ruleEvidence(
                "RESERVATION_WINDOW_8D：最多提前九天预约。\n\nRESERVATION_WINDOW_8D：最多提前八天预约。", PROJECT_RULE);
        assertThat(conflict.linkedBusinessRulePresent()).isFalse();
        assertThat(conflict.conflictingRulePresent()).isTrue();
    }

    @Test
    void acceptsArchivedDirectEnhancementWithBothAllowedAndRejectedBoundaries() {
        // 真实合成 smoke 0e1c1451ed70452594e0500cc60927ab / be589d89-bd1b-4c63-b2f1-ff22e7beafed 的最小充分片段。
        String output = "- docs/reservation-requirements.md：规则代号 RESERVATION_WINDOW_8D 表示最多提前八天预约\n\n"
                + "- 规则边界：提前八天以内允许预约，超过八天抛出 `RESERVATION_WINDOW_8D`，预期与现有测试 rejectsDayNine、acceptsDayEight 一致。";
        var evidence = RealModelConcurrencyAcceptanceTest.ruleEvidence(output, PROJECT_RULE);
        assertThat(evidence.linkedBusinessRulePresent()).isTrue();
        assertThat(evidence.conflictingRulePresent()).isFalse();
    }

    @Test
    void acceptsArchivedPlanConfirmationWithTheNinthDayRejectedAndIdentifierReferences() {
        // 同轮真实合成 smoke / 910b37b0-2d1f-4537-a442-98105038fc94 的最小充分片段。
        String output = "- [BUSINESS_RULE/PROJECT_DOCUMENT] 首次已读；来源：docs/reservation-requirements.md；证据：规则代号 RESERVATION_WINDOW_8D 表示最多提前八天预约\n\n"
                + "- 输入：提前 8 天以内的日期。操作：提交预约。预期：通过校验并创建（来源：`ReservationServiceTest.acceptsDayEight`、`RESERVATION_WINDOW_8D`）。\n\n"
                + "- 输入：提前第 9 天。操作：提交预约。预期：拒绝并返回 `RESERVATION_WINDOW_8D` 对应错误（来源：`ReservationServiceTest.rejectsDayNine`）。\n\n"
                + "- 来自资料：技术栈、现有代码行为、`RESERVATION_WINDOW_8D` 八天约束、场景 1–12 描述、现有测试覆盖范围。";
        var evidence = RealModelConcurrencyAcceptanceTest.ruleEvidence(output, PROJECT_RULE);
        assertThat(evidence.linkedBusinessRulePresent()).isTrue();
        assertThat(evidence.conflictingRulePresent()).isFalse();
    }

    @Test
    void distinguishesCancellingAReservationFromCancellingItsRule() {
        var allowed = RealModelConcurrencyAcceptanceTest.ruleEvidence(
                "RESERVATION_WINDOW_8D：最多提前八天预约，并允许取消预约。\n\n"
                        + "保留 RESERVATION_WINDOW_8D 八天规则并允许取消预约。", PROJECT_RULE);
        assertThat(allowed.linkedBusinessRulePresent()).isTrue();
        assertThat(allowed.conflictingRulePresent()).isFalse();
        var cancelledRule = RealModelConcurrencyAcceptanceTest.ruleEvidence(
                "取消 RESERVATION_WINDOW_8D 规则，最多提前八天预约。", PROJECT_RULE);
        assertThat(cancelledRule.linkedBusinessRulePresent()).isFalse();
        assertThat(cancelledRule.conflictingRulePresent()).isTrue();
    }
}
