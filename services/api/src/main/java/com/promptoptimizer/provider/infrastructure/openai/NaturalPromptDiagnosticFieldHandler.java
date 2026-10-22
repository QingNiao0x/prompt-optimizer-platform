package com.promptoptimizer.provider.infrastructure.openai;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler;
import com.promptoptimizer.common.security.SensitiveValueScanner;
import com.promptoptimizer.provider.domain.NaturalPromptPhasePayloads;
import java.io.IOException;
import java.util.Set;

/**
 * 仅候选24使用的未知诊断兼容边界，不改变已知字段类型、必需内容、来源或正文覆盖校验。
 * 无权威的有界标量可丢弃；阶段控制、来源归属和绑定字段仍拒绝，未知对象/数组不猜测其职责。
 * 诊断既不存入DTO，也不用于生成、权限、日志或历史；21—23和旧版继续使用原严格读取器。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class NaturalPromptDiagnosticFieldHandler extends DeserializationProblemHandler {
    /** 这些字段有阶段或校验职责，出现在未定义位置时不能把它们解释为可忽略诊断。 */
    private static final Set<String> PROTOCOL_FIELDS = Set.of(
            "taskUnderstanding", "review", "coverage", "requirementCoverage", "additionCoverage", "planImpacts",
            "questions", "decisionBasis", "unaskedDecisions", "openDecisions", "optimizedPrompt", "framework",
            "summary", "additions", "bodyQuotes", "evidence", "sourceId", "quote");
    /** 下标、来源类别和题目关联等归属由服务端确定，模型不能通过额外字段伪报。 */
    private static final Set<String> SERVER_FIELDS = Set.of(
            "origin", "index", "questionId", "taskSources", "actualAnswers", "strategyVersion",
            "generationStage", "phase", "sourceKind");

    /** 只有解析器已确认未知的字段才到这里；拒绝后由原FAIL_ON_UNKNOWN_PROPERTIES产生协议失败。 */
    @Override
    public boolean handleUnknownProperty(DeserializationContext context, JsonParser parser,
            JsonDeserializer<?> deserializer, Object beanOrClass, String propertyName) throws IOException {
        if (PROTOCOL_FIELDS.contains(propertyName) || SERVER_FIELDS.contains(propertyName)) return false;
        Class<?> target = beanOrClass instanceof Class<?> type ? type : beanOrClass == null ? null : beanOrClass.getClass();
        // kind在决策条目中是已知语义字段；在来源条目中则会冒充服务端来源类别，两者不能混用。
        if (target == NaturalPromptPhasePayloads.SourceEvidence.class && "kind".equals(propertyName)) return false;
        if (parser.currentToken() == null || !parser.currentToken().isScalarValue()) return false;
        String value = parser.getText();
        var scanner = new SensitiveValueScanner();
        return propertyName.length() <= 128 && value.length() <= 20_000
                && !scanner.containsCredential(propertyName) && !scanner.containsCredential(value);
    }
}
