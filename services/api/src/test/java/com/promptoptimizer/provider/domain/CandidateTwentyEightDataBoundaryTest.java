package com.promptoptimizer.provider.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 回放336／338并列禁令及否定陈述误拒，同时保护新的明确许可、外部对象与来源边界。 */
class CandidateTwentyEightDataBoundaryTest {
    private static final String BAN = "不得使用假设数据。";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void original336ConstraintRemainsAProhibitionAndDoesNotGrantPermission() throws Exception {
        String text = section(336, "CONSTRAINTS");
        assertThatCode(() -> boundary(BAN).validate(text, "sections.CONSTRAINTS")).doesNotThrowAnyException();
        assertThat(RequirementBoundaryContract.prohibitsInventedData(text)).isTrue();
    }

    @Test
    void original338AcceptanceDeniesAppearanceRatherThanPermittingDataUse() throws Exception {
        String text = section(338, "ACCEPTANCE");
        assertThatCode(() -> boundary(BAN).validate(text, "sections.ACCEPTANCE")).doesNotThrowAnyException();
        // 当前事实不是新禁令，不把“没有出现”升级为用户禁止使用的要求。
        assertThat(RequirementBoundaryContract.prohibitsInventedData(text)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "不得编造效应量、P值、参考文献、显著性结论或使用假设数据。",
        "禁止编造效应量、参考文献以及采用虚构数据。",
        "不能虚构P值或使用模拟数据。", "不要编造显著性结论或使用假设或虚构数据。"
    })
    void aCompleteCoordinatedBanAppliesToItsOwnDataAction(String text) {
        assertThat(RequirementBoundaryContract.prohibitsInventedData(text)).isTrue();
        assertThatCode(() -> boundary(BAN).validate(text, "sections.CONSTRAINTS")).doesNotThrowAnyException();
        assertThatThrownBy(() -> boundary(text).validate("验证时可以使用假设数据。", "sections.TASK"))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "未出现编造的效应量、P值、参考文献、显著性结论或假设数据。",
        "没有出现编造的效应量或虚构数据。", "- 未出现编造的P值以及模拟数据。"
    })
    void aNegativeObservationIsNeitherPermissionNorANewUserProhibition(String text) {
        assertThatCode(() -> boundary(BAN).validate(text, "sections.ACCEPTANCE")).doesNotThrowAnyException();
        assertThat(RequirementBoundaryContract.prohibitsInventedData(text)).isFalse();
        assertThat(boundary(text).guidance()).doesNotContain("不得使用假设或虚构数据");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "不得编造效应量，但可以使用假设数据。",
        "不得编造效应量或使用假设数据；测试中可以使用合成数据。",
        "未出现编造的效应量或假设数据，但允许使用虚构数据。",
        "不得编造效应量，示例可以使用假设数据。",
        "未出现编造的效应量或假设数据，随后可使用模拟数据。",
        "不得编造甲院效应量；乙院可以使用假设数据。",
        "禁止编造P值或使用假设数据，演示可以使用虚构数据。",
        "未出现编造的效应量或假设数据。Allow synthetic data for tests.",
        "不允许把数据当真实事实，但可以使用假设数据演示。",
        "不得编造P值但允许使用假设数据。", "禁止编造P值但可以使用虚构数据。",
        "允许但马县采集的假设数据。"
    })
    void aLaterExplicitPermissionIsStillRejectedEvenAfterANegativeClause(String text) {
        assertThatThrownBy(() -> boundary(BAN).validate(text, "sections.TASK"))
                .isInstanceOfSatisfying(ProviderResponseValidationException.class, failure ->
                        assertThat(failure.getReason()).isEqualTo(ProviderResponseValidationException.Reason.DATA_PROHIBITION_CONFLICT));
    }

    @Test
    void quotedAndCodeProhibitionsCannotCreateCurrentUserRules() {
        for (String text : List.of("> 不得编造效应量或使用假设数据。", "```text\n不得编造效应量或使用假设数据。\n```")) {
            assertThat(RequirementBoundaryContract.prohibitsInventedData(text)).isFalse();
        }
        assertThatCode(() -> boundary(BAN).validate("可以使用已授权且可核实的真实示例数据。", "sections.TASK"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> boundary(BAN).validate("禁止使用所有示例数据。", "sections.CONSTRAINTS"))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void aLaterNegativeActionCannotReverseAnEarlierPermissionOrCreateOne() {
        assertThatCode(() -> boundary(BAN).validate("未出现编造的P值但禁止使用假设数据。", "sections.CONSTRAINTS"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> boundary(BAN).validate("允许使用模拟数据但禁止使用假设数据。", "sections.CONSTRAINTS"))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    /** 使用原始用户输入创建契约；不通过模型自报覆盖平台禁令。 */
    private RequirementBoundaryContract boundary(String raw) {
        return RequirementBoundaryContract.from(raw, List.of(), List.of());
    }

    /** 只读新批次公开原件，禁止把修改后的重构响应冒充真实失败。 */
    private String section(int number, String type) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve(".git"))) root = root.getParent();
        if (root == null) throw new IllegalStateException("Repository not found");
        var trace = JSON.readTree(root.resolve("docs/testing/evidence/me00a-candidate27-live-2026-10-17/real/traces/" + number + ".json").toFile());
        var response = JSON.readTree(trace.path("response").path("choices").get(0).path("message").path("content").asText());
        for (var section : response.path("sections")) if (type.equals(section.path("type").asText())) return section.path("content").asText();
        throw new IllegalStateException("Captured section missing");
    }
}
