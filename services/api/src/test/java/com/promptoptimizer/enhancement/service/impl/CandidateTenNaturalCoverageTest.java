package com.promptoptimizer.enhancement.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** 复用候选九响应中的自然状态与委托表达，并验证相同词句的跨对象/未来反例。
 * @author QingNiao
 * @since 0.1.0
 */
class CandidateTenNaturalCoverageTest {
    @Test
    void shouldKeepTheActualNaturalBackgroundAndTaskWithoutAddingSynonymousAnswerLines() throws Exception {
        var json = new ObjectMapper();
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("docs/testing/evidence/me00a-candidate9-2026-10-14"))) root = root.getParent();
        if (root == null) throw new IllegalStateException("缺少候选九原件");
        Path evidence = root.resolve("docs/testing/evidence/me00a-candidate9-2026-10-14");
        var input = json.readTree(evidence.resolve("q05-flash/input.json").toFile());
        var model = json.readTree(json.readTree(evidence.resolve("traces/205.json").toFile()).path("response")
                .path("choices").get(0).path("message").path("content").asText());
        var sections = new EnumMap<PromptSectionType, PromptSection>(PromptSectionType.class);
        for (var section : model.path("sections")) {
            var value = json.treeToValue(section, PromptSection.class); sections.put(value.type(), value);
        }
        var answers = new ArrayList<PlanAnswer>();
        for (var answer : json.readTree(evidence.resolve("q05-flash/actual-answers.json").toFile()).path("answers"))
            answers.add(json.treeToValue(answer, PlanAnswer.class));
        var task = EffectiveTaskViewFactory.create(input.path("rawPrompt").asText(), TemplateCode.AUTO, answers, List.of());
        String background = sections.get(PromptSectionType.BACKGROUND).content(), body = sections.get(PromptSectionType.TASK).content();
        PlanDecisionIntegrator.integrate(sections, task);
        assertThat(sections.get(PromptSectionType.BACKGROUND).content()).isEqualTo(background);
        assertThat(sections.get(PromptSectionType.TASK).content()).isEqualTo(body);
        assertThat(task.pendingDecisions()).hasSize(answers.size());
    }

    @Test
    void anotherResearchOrFutureProposalCannotCoverThisTasksDelegation() {
        var task = EffectiveTaskViewFactory.create("撰写研究设计的方法部分。", TemplateCode.AUTO,
                List.of(new PlanAnswer("design", "本研究的任务分类是什么？", "请根据原始研究目标提出可开展的推荐设计并说明适用条件；不确认任何既有参数。")), List.of());
        for (String prefix : List.of("如果以后确认，", "引用：", "另一研究：")) {
            var sections = new EnumMap<PromptSectionType, PromptSection>(PromptSectionType.class);
            for (var type : List.of(PromptSectionType.BACKGROUND, PromptSectionType.TASK, PromptSectionType.OUTPUT, PromptSectionType.CONSTRAINTS))
                sections.put(type, new PromptSection(type, type.name(), type == PromptSectionType.TASK
                        ? prefix + "撰写研究设计的方法部分，围绕任务分类这一主题，提出可开展的推荐设计并说明适用条件。" : "保留原要求。"));
            PlanDecisionIntegrator.integrate(sections, task);
            assertThat(sections.get(PromptSectionType.TASK).content()).contains("请根据原始研究目标提出可开展的推荐设计并说明适用条件");
        }
    }
}
