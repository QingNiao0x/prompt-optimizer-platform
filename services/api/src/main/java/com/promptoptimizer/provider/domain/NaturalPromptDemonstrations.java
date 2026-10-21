package com.promptoptimizer.provider.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import java.io.IOException;
import java.util.List;

/**
 * 加载三份合成改写示范，并复用产品来源与正文覆盖校验，避免错误教学协议进入模型指导。
 * 只读取固定classpath资源，不读取用户路径，不把教学材料并入当前任务或用户确认。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class NaturalPromptDemonstrations {
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 仅候选首次使用时校验和缓存；资源无效时明确失败，不悄悄改变带示范版本的行为。 */
    private static class Holder {
        private static final String INSTRUCTION = load();
    }

    /** 返回与实际任务隔离的固定示范，无模型调用和用户数据读取。 */
    public static String instruction() { return Holder.INSTRUCTION; }

    /** 只加载受版本控制的固定资源；读取或校验失败保留cause，不能伪造可用示范。 */
    private static String load() {
        try (var input = NaturalPromptDemonstrations.class.getResourceAsStream("/prompts/natural/rewrite-demonstrations.json")) {
            if (input == null) throw new IllegalStateException("自然改写示范资源缺失");
            return render(JSON.readTree(input));
        } catch (IOException | ProviderException failure) {
            throw new IllegalStateException("自然改写示范未通过读取或来源覆盖校验", failure);
        }
    }

    /**
     * 示例来源同样只能来自自身原文和实际题答；正文锚点与空Plan影响记录沿用实际增强校验。
     * 校验保证示范协议一致，不声称内容已经通过真实用户或领域专家验收。
     */
    static String render(JsonNode examples) {
        if (!examples.isArray() || examples.size() != 3) throw new IllegalStateException("自然改写示范数量无效");
        for (var example : examples) {
            try {
                var source = example.path("input");
                List<PlanAnswer> answers = JSON.readerForListOf(PlanAnswer.class).readValue(source.path("actualAnswers"));
                var input = new NaturalPromptInput(source.path("rawPrompt").asText(), null, answers, List.of(), List.of(), null)
                        .withStrategyVersion(NaturalPromptPreparedInstructions.EXEMPLAR_VERSION);
                var output = example.path("output");
                var task = JSON.treeToValue(output.path("taskUnderstanding"), NaturalTaskUnderstanding.class).resolve(input);
                NaturalPromptDraft.Framework.valueOf(output.path("framework").asText());
                task.review().validateEnhancement(task, output.path("optimizedPrompt").asText());
            } catch (IOException | IllegalArgumentException failure) {
                throw new IllegalStateException("自然改写示范格式无效", failure);
            }
        }
        return "\n<internal_rewrite_demonstrations>\n以下是独立合成任务的完整响应示范，只学习补全程度、来源与正文关系。"
                + "它们不是当前任务、可引用资料或用户确认；不复制其中事实、数字、方案或格式，不要求当前下游回答包含示例。"
                + "实际任务只来自随后user消息，taskSources以该消息为准。\n"
                + examples.toPrettyString() + "\n</internal_rewrite_demonstrations>\n";
    }

    private NaturalPromptDemonstrations() { }
}
