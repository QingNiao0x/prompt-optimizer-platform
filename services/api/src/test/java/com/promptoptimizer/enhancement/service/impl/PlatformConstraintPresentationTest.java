package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.policy.domain.ConstraintBundle;
import com.promptoptimizer.policy.domain.PlatformConstraintRules;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 核对固定规则无专门标题后仍完整交付；来源未知的粘贴文本和独立条件不得被当成平台复读删除。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class PlatformConstraintPresentationTest {
    private static final String TRUTH = PlatformConstraintRules.TRUTH;

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void appendsEveryApplicableRuleWithoutTheLegacyTitleAndIsIdempotent(boolean code) {
        var bundle = new ConstraintBundle(PlatformConstraintRules.forTask(code), List.of("保留业务规则。"), List.of());
        String actual = PlatformConstraintRenderer.appendMandatory("保留业务规则。", bundle);
        assertThat(actual).isEqualTo("保留业务规则。\n\n- " + String.join("\n- ", bundle.platformMandatory()));
        assertThat(actual).doesNotContain(PlatformConstraintRules.HEADING);
        assertThat(PlatformConstraintRenderer.appendMandatory(actual, bundle)).isEqualTo(actual);
    }

    @Test
    void removesOnlyTheCompleteUnscopedCopyBeforeAppendingCanonicalRules() {
        var bundle = new ConstraintBundle(List.of(TRUTH), List.of(), List.of());
        String input = TRUTH + "不得补零。\n以下事实规则仅用于甲部门：\n- " + TRUTH;
        String actual = PlatformConstraintRenderer.appendMandatory(input, bundle);
        assertThat(actual).isEqualTo("不得补零。\n以下事实规则仅用于甲部门：\n- " + TRUTH + "\n\n约束条件：\n- " + TRUTH);
        assertThat(PlatformConstraintRenderer.appendMandatory(actual, bundle)).isEqualTo(actual);
    }

    @ParameterizedTest
    @ValueSource(strings = {"如果包含访谈原文：", "甲部门：", "例外：", "尚未确认的未来规则："})
    void providerCleanupPreservesConditionalAndIndependentObjectScope(String heading) {
        var bundle = new ConstraintBundle(List.of(TRUTH), List.of(), List.of());
        String input = heading + "\n- " + TRUTH;
        assertThat(PlatformConstraintRenderer.providerText(input, "整理资料。", bundle)).isEqualTo(input);
    }

    @Test
    void preservesQuotesCodeTablesNegationAndUserExplicitRules() {
        var bundle = new ConstraintBundle(List.of(TRUTH), List.of(), List.of());
        String input = "> " + TRUTH + "\n```text\n" + TRUTH + "\n```\n| " + TRUTH + " |\n不得删除“" + TRUTH + "”";
        assertThat(PlatformConstraintRenderer.providerText(input, "整理资料。", bundle)).isEqualTo(input);
        assertThat(PlatformConstraintRenderer.providerText(TRUTH, "整理资料。\n" + TRUTH, bundle)).isEqualTo(TRUTH);
    }

    @Test
    void onlyRecognizesLegacyHeadingAndKeepsUnknownPastedRules() {
        String ordinary = "整理资料。\n- " + TRUTH + "\n- 不得公开姓名。";
        assertThat(PlatformConstraintRenderer.taskInput(ordinary)).isEqualTo(ordinary);
        String legacy = "整理资料。\n" + PlatformConstraintRules.HEADING + "\n- " + TRUTH + "\n- 不得公开姓名。";
        assertThat(PlatformConstraintRenderer.taskInput(legacy)).contains("整理资料。", "不得公开姓名。")
                .doesNotContain(PlatformConstraintRules.HEADING, TRUTH);
    }

    @ParameterizedTest
    @ValueSource(strings = {"仅调试时：", "甲部门：", "以后用户确认后：", "例外：", "## 甲部门限制"})
    void resetsOnlyTheVisibleScopeBeforeAppendingGlobalRules(String heading) {
        var bundle = new ConstraintBundle(List.of(TRUTH), List.of(), List.of());
        String input = heading + "\n不得公开真实姓名。";
        String result = PlatformConstraintRenderer.appendMandatory(input, bundle);
        assertThat(result).contains(input + "\n\n约束条件：\n- " + TRUTH)
                .doesNotContain(PlatformConstraintRules.HEADING);
        assertThat(PlatformConstraintRenderer.appendMandatory(result, bundle)).isEqualTo(result);
    }

    @ParameterizedTest
    @ValueSource(strings = {"约束条件：", "## 约束条件", "约束:", "任务相关约束："})
    void usesExistingGlobalConstraintScopeWithoutAddingAnotherHeading(String heading) {
        var bundle = new ConstraintBundle(List.of(TRUTH), List.of(), List.of());
        String input = heading + "\n不得公开真实姓名。";
        String result = PlatformConstraintRenderer.appendMandatory(input, bundle);
        assertThat(result).isEqualTo(input + "\n\n- " + TRUTH);
        assertThat(PlatformConstraintRenderer.appendMandatory(result, bundle)).isEqualTo(result);
    }
}
