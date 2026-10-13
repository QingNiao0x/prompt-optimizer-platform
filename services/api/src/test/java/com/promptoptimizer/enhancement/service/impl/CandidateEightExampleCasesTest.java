package com.promptoptimizer.enhancement.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.provider.domain.ExampleContract;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 新批次五份冻结输入先通过真实示例契约与组装路径；开关关闭仍保留原文的明确要求。
 * @author QingNiao
 * @since 0.1.0
 */
class CandidateEightExampleCasesTest {
    @ParameterizedTest
    @ValueSource(strings = {"不得虚构数据；提供给定输入的示例。", "不得虚构数据，提供给定输入的示例。",
            "不要使用虚构示例。提供给定输入的真实示例。", "不要提供说明。提供给定输入的示例。"})
    void shouldNotMoveASeparateProhibitionOntoTheRequestedExamples(String raw) {
        var contract = ExampleContract.from(raw, true);
        assertThat(contract.required()).isTrue();
        if (raw.contains("虚构")) assertThat(contract.fictionalDataAllowed()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldApplyAllFiveSourceAndFormatBoundaries(boolean enabled) throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("AGENTS.md"))) root = root.getParent();
        if (root == null) throw new IllegalStateException("缺少项目根目录。");
        var cases = new ObjectMapper().readTree(Files.readString(root.resolve(
                "docs/testing/evidence/me00a-candidate8-2026-10-14/example-cases.json")));
        assertThat(cases).hasSize(5);
        for (var sample : cases) {
            String id = sample.path("id").asText();
            var contract = ExampleContract.from(sample.path("rawPrompt").asText(), enabled);
            String expected = !enabled && id.equals("EX01") ? "DISABLED"
                    : sample.path("expectedCompliance").path("exampleMode").asText();
            assertThat(contract.mode().name()).as(id + " mode").isEqualTo(expected);
            var sections = new EnumMap<PromptSectionType, PromptSection>(PromptSectionType.class);
            sections.put(PromptSectionType.TASK, new PromptSection(PromptSectionType.TASK, "任务", "按给定输入完成原任务。"));
            sections.put(PromptSectionType.OUTPUT, new PromptSection(PromptSectionType.OUTPUT, "输出", "保持原定输出形式。"));
            ExampleResultIntegrator.integrate(sections, contract);
            if (contract.required()) {
                assertThat(contract.fictionalDataAllowed()).as(id + " data boundary").isFalse();
                assertThat(sections.values().stream().map(PromptSection::content)
                        .collect(java.util.stream.Collectors.joining("\n"))).contains("输入", "预期输出");
                assertThat(contract.guidance()).doesNotContain("可用明确标注为虚构演示");
            } else assertThat(sections).doesNotContainKey(PromptSectionType.EXAMPLES);
        }
    }
}
