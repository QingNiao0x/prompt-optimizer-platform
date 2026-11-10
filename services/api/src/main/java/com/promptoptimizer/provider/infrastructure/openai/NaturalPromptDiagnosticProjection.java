package com.promptoptimizer.provider.infrastructure.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.common.security.SensitiveValueScanner;
import com.promptoptimizer.provider.domain.*;

/** 候选的无权威诊断投影，不改变正文、来源、实际回答或覆盖记录。 */
final class NaturalPromptDiagnosticProjection {
    /**
     * 已知诊断只接受有界安全标量，未知值记为未评估；对象、数组及凭据仍拒绝。
     * 旧版本不调用本入口，未评估状态不参与生成门控或质量通过判定。
     */
    static JsonNode normalize(JsonNode body) {
        ObjectNode result = body.deepCopy();
        scalar(result.get("framework"));
        result.remove("framework");
        if (result.get("task") instanceof ObjectNode task) {
            scalar(task.get("inputReadiness"));
            task.put("inputReadiness", "NOT_ASSESSED");
        }
        return result;
    }

    private static void scalar(JsonNode value) {
        if (value != null && (!value.isValueNode() || value.asText().length() > 128
                || new SensitiveValueScanner().containsCredential(value.asText()))) {
            throw new ProviderException(ProviderFailureType.INVALID_RESPONSE, "诊断字段类型或内容无效", false);
        }
    }

    private NaturalPromptDiagnosticProjection() { }
}
