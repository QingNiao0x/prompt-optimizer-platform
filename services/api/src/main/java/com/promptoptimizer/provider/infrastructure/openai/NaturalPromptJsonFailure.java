package com.promptoptimizer.provider.infrastructure.openai;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonStreamContext;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * 为自然阶段的解析失败提供固定原因和安全字段位置；原始响应只在本次调用内检查。
 * 保留严格拒绝行为，不从重复字段中挑选值，也不把异常消息中的模型文字发送到日志。
 * @author QingNiao
 * @since 0.1.0
 */
final class NaturalPromptJsonFailure {
    private static final Set<String> FIELDS = Set.of("task", "requirements", "additions", "openDecisions",
            "goal", "deliverable", "inputReadiness", "text", "dimensions", "evidence", "sourceId", "quote",
            "bodyQuotes", "gap", "benefit", "issue", "impact", "kind", "optimizedPrompt", "framework",
            "actions", "response", "timing", "condition", "userEvidence", "detailEvidence", "basis",
            "description", "summary", "questions", "unaskedDecisions", "id", "question", "hint", "type",
            "options", "label", "answer", "recommended", "recommendationReason", "examples",
            "allowCustomAnswer", "decisionBasis", "whyAsk", "answerDifference");

    private NaturalPromptJsonFailure() { }

    /** DTO拒绝未知字段或错误类型时只提取白名单路径，保留原cause且不回显失败值。 */
    static ProviderResponseValidationException mapping(JsonMappingException cause) {
        var parts = new ArrayList<String>();
        for (var reference : cause.getPath()) {
            if (reference.getFieldName() != null) {
                if (!FIELDS.contains(reference.getFieldName())) {
                    return new ProviderResponseValidationException(
                            ProviderResponseValidationException.Reason.NATURAL_FIELD_INVALID, "response", cause);
                }
                parts.add(reference.getFieldName());
            } else if (reference.getIndex() >= 0 && reference.getIndex() <= 9999) {
                parts.add("[" + reference.getIndex() + "]");
            }
        }
        String field = String.join(".", parts).replace(".[", "[");
        return new ProviderResponseValidationException(ProviderResponseValidationException.Reason.NATURAL_FIELD_INVALID,
                field.isEmpty() || field.length() > 120 ? "response" : field, cause);
    }

    /** 只在原解析失败后扫描，完整对象的重复键优先定位，其他语法错误使用固定根路径。 */
    static ProviderResponseValidationException diagnose(ObjectMapper json, String content, Throwable cause) {
        var fields = new IdentityHashMap<JsonStreamContext, Set<String>>();
        try (var parser = json.getFactory().createParser(content)) {
            parser.disable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            while (parser.nextToken() != null) {
                if (parser.currentToken() == JsonToken.FIELD_NAME) {
                    var context = parser.getParsingContext();
                    if (!fields.computeIfAbsent(context, ignored -> new HashSet<>()).add(parser.currentName())) {
                        return new ProviderResponseValidationException(
                                ProviderResponseValidationException.Reason.NATURAL_DUPLICATE_FIELD, path(context), cause);
                    }
                } else if (parser.currentToken() == JsonToken.END_OBJECT) {
                    fields.keySet().removeIf(context -> context.getParent() == parser.getParsingContext());
                }
            }
        } catch (IOException ignored) {
            // cause保留首个严格解析错误；扫描失败不输出解析器消息或猜测缺失结构。
        }
        return new ProviderResponseValidationException(
                ProviderResponseValidationException.Reason.NATURAL_JSON_INVALID, "response", cause);
    }

    /** 路径仅由契约字段及有限数组位置组成，未知字段整体降为根路径，防止字段名携带敏感值。 */
    private static String path(JsonStreamContext context) {
        var parts = new ArrayList<String>();
        for (var current = context; current != null && !current.inRoot(); current = current.getParent()) {
            if (current.inArray()) {
                if (current.getCurrentIndex() > 9999) return "response";
                parts.add("[" + current.getCurrentIndex() + "]");
            } else if (current.getCurrentName() != null) {
                if (!FIELDS.contains(current.getCurrentName())) return "response";
                parts.add(current.getCurrentName());
            }
        }
        Collections.reverse(parts);
        String result = String.join(".", parts).replace(".[", "[");
        return result.isEmpty() || result.length() > 120 ? "response" : result;
    }
}
