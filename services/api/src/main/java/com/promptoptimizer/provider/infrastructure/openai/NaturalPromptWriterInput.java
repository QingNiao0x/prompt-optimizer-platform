package com.promptoptimizer.provider.infrastructure.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.provider.domain.NaturalPromptBodyFirstInstructions;
import com.promptoptimizer.provider.domain.ProviderException;
import com.promptoptimizer.provider.domain.ProviderFailureType;

/**
 * 70只精简正文写作不需要的内部编号和重复证据，原始需求、上下文、对话、真实题答与权限保持完整。
 * 准备、Plan、固定首稿登记和修复使用完整输入；此投影不改变来源目录、持久化记录或正文接受条件。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class NaturalPromptWriterInput {
    private NaturalPromptWriterInput() { }

    /** 按服务端版本与实际阶段选择，不按任务内容、职业或问题关键词过滤；不修改调用方的完整树。 */
    static JsonNode project(String version, String operation, boolean preparation, boolean hasFixedDraft, JsonNode payload) {
        if (!NaturalPromptBodyFirstInstructions.WRITER_INPUT_VERSION.equals(version)
                || !"prompt.optimize".equals(operation) || preparation || hasFixedDraft) return payload;
        if (!(payload instanceof ObjectNode original)) throw invalid();
        ObjectNode body = original.deepCopy();
        if (!body.path("preparedRequirements").isArray() || body.path("preparedRequirements").isEmpty()
                || !body.path("taskScope").isObject() || !body.path("taskScope").path("actions").isArray()
                || !body.path("taskScope").path("response").isObject()
                || !body.path("completionSuggestions").path("additions").isArray()) throw invalid();

        // 写作只返回正文，不填写来源引用；编号目录在原input与后续记录调用中仍完整存在。
        body.remove("taskSources");
        var current = body.arrayNode();
        for (JsonNode requirement : body.path("preparedRequirements")) {
            if (!requirement.path("text").isTextual()) throw invalid();
            current.addObject().set("text", requirement.get("text"));
        }
        body.set("preparedRequirements", current);
        ObjectNode scope = (ObjectNode) body.get("taskScope");
        for (JsonNode action : scope.path("actions")) {
            if (!(action instanceof ObjectNode item)) throw invalid();
            item.remove("evidence");
        }
        ((ObjectNode) scope.get("response")).remove("evidence");
        // 不再次传建议记录中的整份原要求、出处与尚未执行的review；提案正文、缺口与用途逐项保留。
        ObjectNode suggestions = body.objectNode();
        suggestions.set("additions", body.path("completionSuggestions").get("additions"));
        body.set("completionSuggestions", suggestions);
        return body;
    }

    /** 准备结构异常时明确失败，不用空清单或原文透传伪造完成。 */
    private static ProviderException invalid() {
        return new ProviderException(ProviderFailureType.INVALID_RESPONSE, "正文输入缺少完整的已核验准备", false);
    }
}
