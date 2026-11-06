package com.promptoptimizer.provider.infrastructure.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.provider.domain.NaturalPromptInput;
import com.promptoptimizer.provider.domain.NaturalPromptPlanContextInstructions;
import com.promptoptimizer.provider.domain.NaturalTaskSource;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * v4准备阶段把服务端来源编号放在对应上下文材料旁，降低长文件列表与独立编号目录错配。
 * 只给模型输入副本添加元数据，不修改来源正文、持久化上下文或最终校验目录。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class NaturalPromptPreparationSourceLabels {
    private NaturalPromptPreparationSourceLabels() { }

    /** 共用v4协议的范围准备调用就近携带来源编号，正文与Plan结果继续使用原输入结构。 */
    static void annotate(String version, boolean scopePreparation, JsonNode payload, NaturalPromptInput input) {
        if (!NaturalPromptPlanContextInstructions.usesProtocol(version) || !scopePreparation
                || !(payload instanceof ObjectNode root)) return;
        Set<String> sourceIds = input.taskSources().stream().map(NaturalTaskSource::id).collect(Collectors.toSet());
        annotateSnapshot(root.get("context"), "context", sourceIds);
        annotateSnapshot(root.get("planningContext"), "planningContext", sourceIds);
    }

    /** 每个可引用的上下文条目就近标注服务端编号；文本和数组顺序保持原值。 */
    private static void annotateSnapshot(JsonNode value, String prefix, Set<String> sourceIds) {
        if (!(value instanceof ObjectNode snapshot)) return;
        String customDescription = prefix + ".customDescription";
        if (sourceIds.contains(customDescription)) snapshot.put("customDescriptionSourceId", customDescription);

        annotateObjectItems(snapshot.path("technologyStack"), prefix + ".technologyStack", sourceIds);
        annotateObjectItems(snapshot.path("dependencies"), prefix + ".dependencies", sourceIds);

        JsonNode directoryTree = snapshot.path("directoryTree");
        if (directoryTree instanceof ArrayNode entries) {
            ArrayNode labels = snapshot.arrayNode();
            for (int i = 0; i < entries.size(); i++) {
                String id = prefix + ".directoryTree." + i;
                labels.add(sourceIds.contains(id) ? id : null);
            }
            snapshot.set("directoryTreeSourceIds", labels);
        }

        JsonNode snippets = snapshot.path("fileSnippets");
        if (snippets instanceof ArrayNode files) {
            for (int i = 0; i < files.size(); i++) {
                if (!(files.get(i) instanceof ObjectNode file)) continue;
                String contentId = prefix + ".fileSnippets." + i;
                if (sourceIds.contains(contentId)) file.put("sourceId", contentId);
                String summaryId = contentId + ".summary";
                if (sourceIds.contains(summaryId)) file.put("summarySourceId", summaryId);
            }
        }
    }

    /** 技术栈和依赖项是对象数组，来源编号可随条目携带而不改变其实际字段。 */
    private static void annotateObjectItems(JsonNode value, String prefix, Set<String> sourceIds) {
        if (!(value instanceof ArrayNode items)) return;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i) instanceof ObjectNode item) {
                String id = prefix + "." + i;
                if (sourceIds.contains(id)) item.put("sourceId", id);
            }
        }
    }
}
