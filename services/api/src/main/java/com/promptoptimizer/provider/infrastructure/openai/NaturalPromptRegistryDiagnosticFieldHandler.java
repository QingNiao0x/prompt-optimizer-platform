package com.promptoptimizer.provider.infrastructure.openai;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler;
import com.promptoptimizer.enhancement.domain.ProviderMetadata;
import com.promptoptimizer.provider.domain.*;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 仅51引用正文reader使用的未知字段边界：保护公共自然协议及服务器字段后，委托冻结24的标量兼容。
 * 已知字段仍由原严格reader处理；诊断不进入DTO、来源、权限、正文或历史，准备与Plan不使用本handler。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class NaturalPromptRegistryDiagnosticFieldHandler extends DeserializationProblemHandler {
    private static final Set<String> RESERVED_FIELDS = reservedFields();
    private final NaturalPromptDiagnosticFieldHandler scalarBoundary = new NaturalPromptDiagnosticFieldHandler();

    /**
     * 解析器只在未知位置调用此方法；同名协议字段不能因为放错层级而降为无权威诊断。
     * 其余未知值继续受24的标量类型、名称/值长度及敏感值检查约束，不宽松忽略对象或数组。
     */
    @Override
    public boolean handleUnknownProperty(DeserializationContext context, JsonParser parser,
            JsonDeserializer<?> deserializer, Object beanOrClass, String propertyName) throws IOException {
        if (RESERVED_FIELDS.contains(propertyName)) return false;
        return scalarBoundary.handleUnknownProperty(context, parser, deserializer, beanOrClass, propertyName);
    }

    /**
     * 只在类初始化时读取明确列出的公共schema，形成有限不可变集合，不扫描类路径或模型内容。
     * writer/准备/Plan/canonical的语义字段及服务器归属均保留；派生JSON辅助字段没有record，显式补在此处。
     */
    private static Set<String> reservedFields() {
        Set<String> fields = new HashSet<>(Set.of("taskSources", "preparedRequirements", "taskScope", "inputBoundary",
                "stage", "contextMeaning", "executionResponsibilities", "materialVisibility", "completionSuggestions",
                "fidelityDraft", "fidelityFindings", "fidelityEditTargets", "protocolRepair"));
        for (Class<?> schema : List.of(NaturalPromptRequirementRegistry.class, NaturalPromptReferencedEnhancement.class,
                NaturalPromptPhasePayloads.class, NaturalTaskScope.class, NaturalTaskPreparation.class, MaterialVisibility.class,
                NaturalTaskUnderstanding.class, NaturalTaskReview.class, NaturalPromptInput.class, NaturalPromptDraft.class,
                NaturalTaskSource.class, ProviderMetadata.class)) {
            collectPublicSchemaFields(schema, fields);
        }
        return Set.copyOf(fields);
    }

    /** 字段只来自指定record及其公共嵌套schema；不沿字段类型递归扩张到其他业务模块。 */
    private static void collectPublicSchemaFields(Class<?> schema, Set<String> fields) {
        if (schema.isRecord()) for (var component : schema.getRecordComponents()) fields.add(component.getName());
        for (Class<?> nested : schema.getDeclaredClasses()) {
            if (Modifier.isPublic(nested.getModifiers())) collectPublicSchemaFields(nested, fields);
        }
    }
}
