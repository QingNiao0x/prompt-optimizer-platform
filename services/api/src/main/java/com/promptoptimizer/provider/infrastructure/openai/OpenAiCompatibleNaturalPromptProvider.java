package com.promptoptimizer.provider.infrastructure.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.fasterxml.jackson.core.io.JsonEOFException;
import com.promptoptimizer.common.logging.ModelCallLogger;
import com.promptoptimizer.common.logging.ModelTokenUsageReader;
import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.ProviderMetadata;
import com.promptoptimizer.enhancement.domain.NaturalProcessing;
import com.promptoptimizer.provider.domain.*;
import com.promptoptimizer.provider.service.NaturalPromptProvider;
import com.promptoptimizer.provider.service.PlatformModelCatalog;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import java.io.IOException;
import java.util.*;
import java.util.function.Function;

/**
 * 自然正文与自然 Plan 的独立 OpenAI 兼容适配器。只共用带并发拦截器的 HTTP 客户端、
 * 路由、容量及安全计量，不调用旧增强 Provider、四要素解析或正文组装逻辑。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Component
@ConditionalOnProperty(prefix = "app.provider", name = "mode", havingValue = "openai-compatible")
public class OpenAiCompatibleNaturalPromptProvider implements NaturalPromptProvider {
    private static final int MAX_ATTEMPTS = 2;
    private final RestClient client;
    private final ObjectMapper json;
    private final ObjectReader enhancementPhaseReader;
    private final ObjectReader planningPhaseReader;
    private final ObjectReader diagnosticEnhancementReader;
    private final ObjectReader diagnosticPlanningReader;
    private final ObjectReader scopeReader;
    private final ObjectReader materialPreparationReader;
    private final ObjectReader responseReader;
    private final OpenAiCompatibleProperties properties;
    private final PlatformModelCatalog catalog;

    /** 复用服务端路由和受限HTTP客户端，密钥不进入浏览器或模型业务输入。 */
    @Autowired
    public OpenAiCompatibleNaturalPromptProvider(
            @Qualifier("openAiCompatibleRestClient") RestClient client, ObjectMapper json,
            OpenAiCompatibleProperties properties, ObjectProvider<PlatformModelCatalog> catalog) {
        this(client, json, properties, catalog.getIfAvailable());
    }

    /** 允许受控 HTTP 测试注入本地路由；生产只通过服务端模型目录选择已发布模型。 */
    public OpenAiCompatibleNaturalPromptProvider(RestClient client, ObjectMapper json,
            OpenAiCompatibleProperties properties, PlatformModelCatalog catalog) {
        this.client = client;
        this.json = json;
        // 阶段DTO默认拒绝未知字段和标量类型猜换；局部副本不改变第四版及其它业务的Jackson兼容行为。
        ObjectMapper phases = json.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_IGNORED_PROPERTIES, DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .disable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY,
                        DeserializationFeature.UNWRAP_SINGLE_VALUE_ARRAYS,
                        DeserializationFeature.ACCEPT_EMPTY_ARRAY_AS_NULL_OBJECT,
                        DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT);
        for (LogicalType type : List.of(LogicalType.Textual, LogicalType.Boolean, LogicalType.Enum)) {
            for (CoercionInputShape shape : List.of(CoercionInputShape.Integer, CoercionInputShape.Float,
                    CoercionInputShape.EmptyString)) {
                phases.coercionConfigFor(type).setCoercion(shape, CoercionAction.Fail);
            }
        }
        phases.coercionConfigFor(LogicalType.Textual).setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        phases.coercionConfigFor(LogicalType.Boolean).setCoercion(CoercionInputShape.String, CoercionAction.Fail);
        this.enhancementPhaseReader = phases.readerFor(NaturalPromptPhasePayloads.Enhancement.class);
        this.planningPhaseReader = phases.readerFor(NaturalPromptPhasePayloads.Planning.class);
        // 24只改变无权威标量诊断的处理；复制后注册局部handler，不修改已冻结候选或全局Jackson行为。
        ObjectMapper diagnostics = phases.copy().addHandler(new NaturalPromptDiagnosticFieldHandler());
        this.diagnosticEnhancementReader = diagnostics.readerFor(NaturalPromptPhasePayloads.Enhancement.class);
        this.diagnosticPlanningReader = diagnostics.readerFor(NaturalPromptPhasePayloads.Planning.class);
        this.scopeReader = phases.readerFor(NaturalTaskScope.class);
        this.materialPreparationReader = phases.readerFor(NaturalTaskPreparation.class);
        // 在转为树之前拒绝重复键，避免后一个questions空数组静默覆盖前面真实题目；不改变全局Jackson配置。
        this.responseReader = json.copy().enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readerFor(JsonNode.class);
        this.properties = properties;
        this.catalog = catalog;
    }

    @Override
    public <T> T enhance(NaturalPromptInput input, Function<NaturalPromptDraft, T> validation) {
        if (!NaturalPromptInstructions.supports(input.strategyVersion())) throw invalid("自然链路版本不受支持");
        if (NaturalPromptRoutingRepairInstructions.supports(input.strategyVersion())) {
            var prepared = prepareScope(input, "prompt.optimize");
            // 范围分类不再关闭正文生成。按版本选择正文指导，使42只改变写作方式；完整来源和原覆盖保护继续有效。
            return invoke(input, NaturalPromptInstructions.enhancementInstruction(input.strategyVersion()), "prompt.optimize", null, null, null,
                    false, prepared.scope(), (body, metadata) -> {
                        try {
                            String optimized = requiredText(body, "optimizedPrompt");
                            var task = understanding(body, input);
                            // 正文语义不由关键词或字符重合判定；继续交给原来源、题答和覆盖保护校验。
                            return validation.apply(new NaturalPromptDraft(optimized,
                                    NaturalPromptDraft.Framework.valueOf(requiredText(body, "framework")), metadata, task)
                                    .withStages(List.of(NaturalProcessing.Stage.SCOPE_ANALYSIS, NaturalProcessing.Stage.BODY_GENERATION)));
                        } catch (IllegalArgumentException failure) {
                            throw invalid("自然正文或框架标识无效");
                        }
                    });
        }
        if (usesAdvisoryScope(input.strategyVersion())) {
            var prepared = prepareScope(input, "prompt.optimize");
            // response只描述回应范围，不描述执行就绪度。即使准备错分，也继续依据完整原文写作并校验覆盖。
            // 最终task由正文阶段统一形成，避免把准备误判强制覆盖回正确的成果及要求。
            return invoke(input, NaturalPromptInstructions.enhancementInstruction(input.strategyVersion()), "prompt.optimize", null, null, null,
                    false, prepared.scope(), (body, metadata) -> validation.apply(readEnhancementPhase(body, input, metadata)));
        }
        if (NaturalPromptMaterialInstructions.VERSION.equals(input.strategyVersion())) {
            var prepared = prepareMaterials(input, "prompt.optimize");
            var task = prepared.preparation();
            if (task.taskScope().response().basis() != NaturalTaskScope.ResponseBasis.EXPLICIT) {
                return validation.apply(NaturalPreservedRequest.create(input, task.taskScope(), prepared.metadata()));
            }
            // 35不再递归到丢失准备结果的第四版：同一范围和责任记录随完整原输入传给写作阶段。
            return invoke(input, NaturalPromptMaterialInstructions.ENHANCE, "prompt.optimize", null, null, null,
                    false, task.taskScope(), task, (body, metadata) -> validation.apply(readEnhancementPhase(
                            bindMaterialResponsibilities(body, task, input, false), input, metadata)));
        }
        if (NaturalPromptScopedInstructions.supports(input.strategyVersion())) {
            ScopePreparation prepared = prepareScope(input, "prompt.optimize");
            NaturalTaskScope scope = prepared.scope();
            // 推定的准备就绪反馈不能授权一个新的正文作品；30/31保留原请求中的全部自然回应和明确任务。
            if ((NaturalPromptScopedInstructions.PRESERVED_VERSION.equals(input.strategyVersion())
                    && scope.response().basis() == NaturalTaskScope.ResponseBasis.UNSPECIFIED)
                    || ((NaturalPromptScopedInstructions.CONSERVATIVE_VERSION.equals(input.strategyVersion())
                    || NaturalPromptScopedInstructions.GUARDED_FOURTH_VERSION.equals(input.strategyVersion())
                    || NaturalPromptRelationalInstructions.supports(input.strategyVersion())
                    || NaturalPromptExecutionBoundaryInstructions.VERSION.equals(input.strategyVersion()))
                    && scope.response().basis() != NaturalTaskScope.ResponseBasis.EXPLICIT)) {
                return validation.apply(NaturalPreservedRequest.create(input, scope, prepared.metadata()));
            }
            if (NaturalPromptScopedInstructions.GUARDED_FOURTH_VERSION.equals(input.strategyVersion())) {
                // 明确成果没有新增写作变量：复用冻结4的全部业务输入、system、解析和保护，历史版本由应用层记录31。
                return enhance(input.withStrategyVersion(NaturalPromptInstructions.VERSION), draft -> validation.apply(
                        draft.withStages(List.of(NaturalProcessing.Stage.SCOPE_ANALYSIS, NaturalProcessing.Stage.BODY_GENERATION))));
            }
            if (NaturalPromptRelationalInstructions.supports(input.strategyVersion())
                    || NaturalPromptExecutionBoundaryInstructions.VERSION.equals(input.strategyVersion())) {
                // 32/33共用32指导，34只改变执行资料职责；均沿用4协议，不把范围记录混成用户要求。
                return invoke(input, NaturalPromptInstructions.enhancementInstruction(input.strategyVersion()), "prompt.optimize", null, false,
                        (body, metadata) -> {
                            try {
                                var draft = new NaturalPromptDraft(requiredText(body, "optimizedPrompt"),
                                        NaturalPromptDraft.Framework.valueOf(requiredText(body, "framework")), metadata,
                                        understanding(body, input));
                                T checked = validation.apply(draft);
                                if (!NaturalPromptRelationalInstructions.SUPPLEMENT_VERSION.equals(input.strategyVersion())) return checked;
                                return validation.apply(NaturalPreservedRequest.supplement(input, draft));
                            } catch (IllegalArgumentException failure) {
                                // 框架等协议错误与冻结4一样最多修复一次，不用静默默认值掩盖错误。
                                throw invalid("自然正文或框架标识无效");
                            }
                        });
            }
            return invoke(input, NaturalPromptInstructions.enhancementInstruction(input.strategyVersion()), "prompt.optimize", null, null, null, false, scope,
                    (body, metadata) -> validation.apply(readEnhancementPhase(bindScope(body, scope), input, metadata)));
        }
        NaturalTaskUnderstanding suggestions = null;
        if (NaturalPromptPreparationInstructions.VERSION.equals(input.strategyVersion())
                || NaturalPromptFidelityInstructions.PREPARED_VERSION.equals(input.strategyVersion())) {
            // 独立预算用于内容准备，不是失败后的质量重试。两阶段均保留原始输入并逐次计量。
            suggestions = invoke(input, NaturalPromptPreparationInstructions.PREPARE, "prompt.optimize", null, true, (body, metadata) -> {
                var task = understanding(body, input);
                task.review().validatePreparation();
                return task;
            });
        }
        String instruction = NaturalPromptInstructions.enhancementInstruction(input.strategyVersion());
        if (NaturalPromptStageInstructions.supports(input.strategyVersion())) {
            return invoke(input, instruction, "prompt.optimize", (body, metadata) ->
                    validation.apply(readEnhancementPhase(body, input, metadata)));
        }
        if (NaturalPromptFidelityInstructions.supports(input.strategyVersion())) {
            return enhanceWithFidelity(input, instruction, suggestions, validation);
        }
        return invoke(input, instruction, "prompt.optimize", suggestions, false, (body, metadata) -> {
            try {
                return validation.apply(new NaturalPromptDraft(requiredText(body, "optimizedPrompt"),
                        NaturalPromptDraft.Framework.valueOf(requiredText(body, "framework")), metadata,
                        understanding(body, input)).withStages(List.of(NaturalProcessing.Stage.BODY_GENERATION)));
            } catch (IllegalArgumentException failure) {
                throw invalid("自然正文或框架标识无效");
            }
        });
    }

    /**
     * 先按原回调完整预检首稿，再独立核对来源语义；未通过必要保护的首稿不发送给审阅阶段。
     * 回调契约无副作用，历史仍由应用层最终保存一次；最终再次调用可纳入审阅耗时，不返回预检时的旧计时。
     */
    private <T> T enhanceWithFidelity(NaturalPromptInput input, String instruction,
            NaturalTaskUnderstanding suggestions, Function<NaturalPromptDraft, T> validation) {
        NaturalPromptDraft draft = invoke(input, instruction, "prompt.optimize", suggestions, false, (body, metadata) -> {
            try {
                var candidate = new NaturalPromptDraft(requiredText(body, "optimizedPrompt"),
                        NaturalPromptDraft.Framework.valueOf(requiredText(body, "framework")), metadata, understanding(body, input));
                validation.apply(candidate);
                return candidate;
            } catch (IllegalArgumentException failure) {
                throw invalid("自然正文或框架标识无效");
            }
        });
        if (NaturalPromptFidelityInstructions.SEPARATED_VERSION.equals(input.strategyVersion())
                || NaturalPromptFidelityInstructions.GROUNDED_VERSION.equals(input.strategyVersion())) {
            return inspectThenRepair(input, draft, validation);
        }
        return invoke(input, NaturalPromptFidelityInstructions.REVIEW, "prompt.optimize", null, draft, null, false, (body, metadata) -> {
            try {
                var review = json.treeToValue(body, NaturalPromptFidelityReview.class);
                // 无问题时apply返回原对象；有问题只替换已定位片段，仍经过同一组最终业务保护。
                return validation.apply(review.apply(input, draft));
            } catch (JsonProcessingException | IllegalArgumentException failure) {
                throw invalid("自然保真审阅协议无效");
            }
        });
    }

    /**
     * 独立识别只比较完整来源与正文，避免首稿的自我解释充当事实依据。
     * 无问题保持原稿；有问题才进入一次明确的局部修订阶段，每个阶段的协议修复和用量分别计量。
     */
    private <T> T inspectThenRepair(NaturalPromptInput input, NaturalPromptDraft draft, Function<NaturalPromptDraft, T> validation) {
        boolean grounded = NaturalPromptFidelityInstructions.GROUNDED_VERSION.equals(input.strategyVersion());
        NaturalPromptFidelityFindings findings = invoke(input, grounded ? NaturalPromptFidelityInstructions.GROUNDED_DETECT
                : NaturalPromptFidelityInstructions.DETECT, "prompt.optimize",
                null, draft, null, false, (body, metadata) -> {
                    try {
                        var result = json.treeToValue(body, NaturalPromptFidelityFindings.class);
                        if (grounded) NaturalPromptFidelityTargets.resolve(input, draft, result);
                        else result.validate(input, draft);
                        return result;
                    } catch (JsonProcessingException | IllegalArgumentException failure) {
                        throw invalid("自然保真识别协议无效");
                    }
                });
        if (findings.issues().isEmpty()) return validation.apply(draft);
        if (grounded) {
            return invoke(input, NaturalPromptFidelityInstructions.GROUNDED_PATCH, "prompt.optimize", null, draft, findings, false,
                    (body, metadata) -> {
                        try {
                            var repair = json.treeToValue(body, NaturalPromptFidelityTargets.Repair.class);
                            return validation.apply(NaturalPromptFidelityTargets.applyRepair(input, draft, findings, repair));
                        } catch (JsonProcessingException | IllegalArgumentException failure) {
                            throw invalid("自然保真编辑目标修订协议无效");
                        }
                    });
        }
        return invoke(input, NaturalPromptFidelityInstructions.PATCH, "prompt.optimize", null, draft, findings, false, (body, metadata) -> {
            try {
                var repair = json.treeToValue(body, NaturalPromptFidelityReview.class);
                return validation.apply(findings.applyRepair(input, draft, repair));
            } catch (JsonProcessingException | IllegalArgumentException failure) {
                throw invalid("自然保真局部修订协议无效");
            }
        });
    }

    @Override
    public <T> T plan(NaturalPromptInput input, Function<PlanningProviderResponse, T> validation) {
        if (!NaturalPromptInstructions.supports(input.strategyVersion())) throw invalid("自然链路版本不受支持");
        if (NaturalPromptRoutingRepairInstructions.supports(input.strategyVersion())) {
            var scope = prepareScope(input, "plan.generate").scope();
            return invoke(input, NaturalPromptInstructions.planningInstruction(input.strategyVersion()), "plan.generate", null, null, null, false, scope,
                    (body, metadata) -> {
                        if (!body.path("questions").isArray()) throw invalid("Plan问题列表无效");
                        try {
                            return validation.apply(new PlanningProviderResponse(requiredText(body, "summary"),
                                    json.convertValue(body.get("questions"), json.getTypeFactory().constructCollectionType(List.class, PlanQuestion.class)),
                                    metadata.provider(), metadata.model(), false, understanding(body, input)));
                        } catch (IllegalArgumentException failure) {
                            throw invalid("Plan问题协议无效");
                        }
                    });
        }
        if (usesAdvisoryScope(input.strategyVersion())) {
            var prepared = prepareScope(input, "plan.generate");
            return invoke(input, NaturalPromptInstructions.planningInstruction(input.strategyVersion()), "plan.generate", null, null, null,
                    false, prepared.scope(), (body, metadata) -> validation.apply(readPlanningPhase(body, input, metadata)));
        }
        if (NaturalPromptMaterialInstructions.VERSION.equals(input.strategyVersion())) {
            var task = prepareMaterials(input, "plan.generate").preparation();
            return invoke(input, NaturalPromptMaterialInstructions.PLAN, "plan.generate", null, null, null,
                    false, task.taskScope(), task, (body, metadata) -> validation.apply(readPlanningPhase(
                            bindMaterialResponsibilities(body, task, input, true), input, metadata)));
        }
        if (NaturalPromptScopedInstructions.supports(input.strategyVersion())) {
            NaturalTaskScope scope = prepareScope(input, "plan.generate").scope();
            return invoke(input, NaturalPromptInstructions.planningInstruction(input.strategyVersion()), "plan.generate", null, null, null, false, scope,
                    (body, metadata) -> validation.apply(readPlanningPhase(bindScope(body, scope), input, metadata)));
        }
        String instruction = NaturalPromptInstructions.planningInstruction(input.strategyVersion());
        return invoke(input, instruction, "plan.generate", (body, metadata) -> {
            if (NaturalPromptStageInstructions.supports(input.strategyVersion())) {
                return validation.apply(readPlanningPhase(body, input, metadata));
            }
            if (!body.path("questions").isArray()) throw invalid("Plan问题列表无效");
            try {
                List<PlanQuestion> questions = json.convertValue(body.get("questions"),
                        json.getTypeFactory().constructCollectionType(List.class, PlanQuestion.class));
                return validation.apply(new PlanningProviderResponse(requiredText(body, "summary"),
                        questions, metadata.provider(), metadata.model(), false, understanding(body, input)));
            } catch (IllegalArgumentException failure) {
                throw invalid("Plan问题协议无效");
            }
        });
    }

    /** 36—38只将准备结果作为解释候选，正文始终按完整原始输入生成并校验，不硬绑定准备误判。 */
    private static boolean usesAdvisoryScope(String version) {
        return NaturalPromptPreparationRoutingInstructions.VERSION.equals(version)
                || NaturalPromptIntentCompletionInstructions.VERSION.equals(version)
                || NaturalPromptContentScopeInstructions.VERSION.equals(version);
    }

    /** 独立识别范围并逐次计量；36—38共用冻结准备指导，当前实验只改变后续正文指导。 */
    private ScopePreparation prepareScope(NaturalPromptInput input, String operation) {
        String instruction = usesAdvisoryScope(input.strategyVersion())
                ? NaturalPromptPreparationRoutingInstructions.PREPARE
                : NaturalPromptExecutionBoundaryInstructions.VERSION.equals(input.strategyVersion())
                        ? NaturalPromptExecutionBoundaryInstructions.PREPARE : NaturalPromptScopedInstructions.PREPARE;
        return invoke(input, instruction, operation, (body, metadata) -> {
            try {
                NaturalTaskScope scope = scopeReader.readValue(body);
                return new ScopePreparation((NaturalPromptRoutingRepairInstructions.ADVISORY_TIMING_VERSION.equals(input.strategyVersion())
                        || NaturalPromptRoutingRepairInstructions.OWNED_DECISIONS_VERSION.equals(input.strategyVersion())
                        || NaturalPromptContentQualityInstructions.VERSION.equals(input.strategyVersion())
                        || NaturalPromptGroundedCompletionInstructions.VERSION.equals(input.strategyVersion())
                        || NaturalPromptMultidimensionalInstructions.VERSION.equals(input.strategyVersion())
                        || NaturalPromptContextualRewriteInstructions.VERSION.equals(input.strategyVersion()))
                        ? scope.resolveAdvisory(input) : scope.resolve(input), metadata);
            } catch (IOException | IllegalArgumentException failure) {
                throw invalid("任务动作与回应边界协议无效");
            }
        });
    }

    /** 准备调用的模型身份仍随确定性结果返回；不伪造第二次调用或额外用量。 */
    private record ScopePreparation(NaturalTaskScope scope, ProviderMetadata metadata) { }

    /** 35沿用现有准备次数；新增责任只引用原输入，不加入事实来源或持久化用户资料。 */
    private MaterialPreparation prepareMaterials(NaturalPromptInput input, String operation) {
        return invoke(input, NaturalPromptMaterialInstructions.PREPARE, operation, (body, metadata) -> {
            try {
                NaturalTaskPreparation task = materialPreparationReader.readValue(body);
                return new MaterialPreparation(task.resolve(input), metadata);
            } catch (IOException | IllegalArgumentException failure) {
                throw invalid("任务范围与执行责任协议无效");
            }
        });
    }

    /** 准备阶段身份随保留原文的结果传递，不伪造后续调用。 */
    private record MaterialPreparation(NaturalTaskPreparation preparation, ProviderMetadata metadata) { }

    /**
     * 未决事项只能引用准备中确需用户决定的依赖，执行者工作不能在写作/Plan阶段再转交用户。
     * 只检查责任引用，不凭分类枚举宣称语义正确；正文要求、真实题答及覆盖仍由阶段校验核对。
     */
    private JsonNode bindMaterialResponsibilities(JsonNode body, NaturalTaskPreparation preparation, NaturalPromptInput input, boolean planning) {
        JsonNode bound = bindScope(body, preparation.taskScope());
        JsonNode task = bound.path("task");
        var referenced = new java.util.HashSet<String>();
        validateDecisionReferences(task.path(planning ? "unaskedDecisions" : "openDecisions"), preparation, input, referenced);
        if (planning && bound.path("questions").isArray()) {
            for (JsonNode question : bound.path("questions")) {
                validateDecisionReferences(question.path("decisionBasis"), preparation, input, referenced);
            }
        }
        if (task.has("resolvedDecisions")) {
            if (!task.path("resolvedDecisions").isArray()) throw invalid("责任纠正列表无效");
            var resolved = new java.util.HashSet<String>();
            for (JsonNode decision : task.path("resolvedDecisions")) {
                String id = materialDependencyId(decision);
                // 同一决定不能同时已解决和仍待回答；重复解决也不能掩盖响应记录冲突。
                if (!resolved.add(id) || referenced.contains(id)) throw invalid("责任纠正状态冲突或重复");
                var evidence = correctionEvidence(decision, true);
                preparation.validateResolvedDecision(id, evidence, input);
                referenced.add(id);
            }
            ((com.fasterxml.jackson.databind.node.ObjectNode) task).remove("resolvedDecisions");
        }
        // 已识别决定要保留或以原来源显式纠正；不能仅凭模型首阶段分类锁死后续正确理解。
        if (preparation.dependencies().stream().anyMatch(dependency -> dependency.owner() == NaturalTaskPreparation.Owner.USER_DECISION
                && !referenced.contains(dependency.id()))) throw invalid("用户决定未完整保留");
        return bound;
    }

    /** 校验并消费35专用关联字段，再交给严格旧阶段DTO；未知/执行责任引用不通过静默删字段放行。 */
    private void validateDecisionReferences(JsonNode entries, NaturalTaskPreparation preparation, NaturalPromptInput input,
            java.util.Set<String> referenced) {
        if (!entries.isArray()) throw invalid("未决事项缺少责任列表");
        for (JsonNode entry : entries) {
            if (!(entry instanceof com.fasterxml.jackson.databind.node.ObjectNode object)) throw invalid("未决事项缺少责任依据");
            String id = materialDependencyId(entry);
            if (entry.has("correction")) {
                preparation.validateDecisionCorrection(correctionEvidence(entry.path("correction"), false), input);
                object.remove("correction");
            } else preparation.validateDecisionReference(id);
            referenced.add(id);
            object.remove("dependencyId");
        }
    }

    /** 正常引用与纠正引用使用同一编号边界，不能因纠正路径消费字段而跳过容量及凭据校验。 */
    private String materialDependencyId(JsonNode entry) {
        JsonNode value = entry.path("dependencyId");
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > 128
                || new com.promptoptimizer.common.security.SensitiveValueScanner().containsCredential(value.asText())) {
            throw invalid("执行责任编号无效");
        }
        return value.asText();
    }

    /** 显式纠正必须给出原始用户来源；不能把准备分类当权威，也不接受无依据的新标签。 */
    private java.util.List<NaturalTaskScope.Evidence> correctionEvidence(JsonNode correction, boolean resolved) {
        var allowed = resolved ? java.util.Set.of("dependencyId", "reason", "evidence")
                : java.util.Set.of("reason", "evidence");
        if (!correction.isObject() || correction.size() != allowed.size() || !correction.path("evidence").isArray()) {
            throw invalid("责任纠正依据无效");
        }
        for (var names = correction.fieldNames(); names.hasNext();) {
            if (!allowed.contains(names.next())) throw invalid("责任纠正包含未知字段");
        }
        String reason = requiredText(correction, "reason");
        if (reason.length() > 20_000 || new com.promptoptimizer.common.security.SensitiveValueScanner().containsCredential(reason)) {
            throw invalid("责任纠正说明无效");
        }
        try {
            return json.convertValue(correction.path("evidence"), json.getTypeFactory()
                    .constructCollectionType(java.util.List.class, NaturalTaskScope.Evidence.class));
        } catch (IllegalArgumentException failure) {
            throw invalid("责任纠正出处无效");
        }
    }

    /**
     * 后续阶段只填写正文/提问及其证据，不重复生成目标和成果；服务端带入同一范围记录。
     * 不补写requirements或bodyQuotes，不能因准备阶段通过而假定全部原要求已经覆盖。
     */
    private JsonNode bindScope(JsonNode body, NaturalTaskScope scope) {
        if (!(body.get("task") instanceof com.fasterxml.jackson.databind.node.ObjectNode task)
                || task.has("goal") || task.has("deliverable")) throw invalid("范围复用阶段包含重复目标或缺少任务记录");
        var bound = body.deepCopy();
        var target = (com.fasterxml.jackson.databind.node.ObjectNode) bound.get("task");
        target.put("goal", scope.goal());
        target.put("deliverable", scope.deliverable());
        return bound;
    }

    /** 严格读取增强专用语义字段后生成内部覆盖记录，24仅忽略无权威诊断，不删除非法Plan字段放行。 */
    private NaturalPromptDraft readEnhancementPhase(JsonNode body, NaturalPromptInput input, ProviderMetadata metadata) {
        try {
            ObjectReader reader = NaturalPromptStageInstructions.acceptsDiagnosticFields(input.strategyVersion())
                    ? diagnosticEnhancementReader : enhancementPhaseReader;
            NaturalPromptPhasePayloads.Enhancement payload = reader.readValue(body);
            return payload.toDraft(input, metadata);
        } catch (IOException | IllegalArgumentException failure) {
            throw invalid("自然增强阶段协议无效");
        }
    }

    /** Plan的依据与题目就地绑定；注册、实际回答确认与历史保存仍由现有应用服务负责。 */
    private PlanningProviderResponse readPlanningPhase(JsonNode body, NaturalPromptInput input, ProviderMetadata metadata) {
        try {
            ObjectReader reader = NaturalPromptStageInstructions.acceptsDiagnosticFields(input.strategyVersion())
                    ? diagnosticPlanningReader : planningPhaseReader;
            NaturalPromptPhasePayloads.Planning payload = reader.readValue(body);
            return payload.toResponse(input, metadata);
        } catch (IOException | IllegalArgumentException failure) {
            throw invalid("自然Plan阶段协议无效");
        }
    }

    /** 所有失败尝试均计量；只对响应协议/校验失败补一次，不重试鉴权、超时或网络错误。 */
    private <T> T invoke(NaturalPromptInput input, String instruction, String operation, ResponseReader<T> reader) {
        return invoke(input, instruction, operation, null, false, reader);
    }

    /**
     * 补全提案只进入当前正文调用的私有辅助字段，不加入来源目录或替换原始输入。
     * 使用同一个传输与计量入口，各阶段协议修复仍最多一次；未通过准备校验时不继续生成正文。
     */
    private <T> T invoke(NaturalPromptInput input, String instruction, String operation,
            NaturalTaskUnderstanding suggestions, boolean preparation, ResponseReader<T> reader) {
        return invoke(input, instruction, operation, suggestions, null, null, preparation, reader);
    }

    /** 生成建议与首稿仅为当前阶段的私有辅助材料，不进入来源目录、响应或历史记录。 */
    private <T> T invoke(NaturalPromptInput input, String instruction, String operation,
            NaturalTaskUnderstanding suggestions, NaturalPromptDraft fidelityDraft, NaturalPromptFidelityFindings findings,
            boolean preparation, ResponseReader<T> reader) {
        return invoke(input, instruction, operation, suggestions, fidelityDraft, findings, preparation, null, reader);
    }

    /** 范围只作为当前请求的辅助字段，永不加入来源目录、复制正文或历史中的用户事实。 */
    private <T> T invoke(NaturalPromptInput input, String instruction, String operation,
            NaturalTaskUnderstanding suggestions, NaturalPromptDraft fidelityDraft, NaturalPromptFidelityFindings findings,
            boolean preparation, NaturalTaskScope scope, ResponseReader<T> reader) {
        return invoke(input, instruction, operation, suggestions, fidelityDraft, findings, preparation, scope, null, reader);
    }

    /** 35的执行责任与增强可见范围是独立辅助输入，不替换原始资料或建立新来源。 */
    private <T> T invoke(NaturalPromptInput input, String instruction, String operation,
            NaturalTaskUnderstanding suggestions, NaturalPromptDraft fidelityDraft, NaturalPromptFidelityFindings findings,
            boolean preparation, NaturalTaskScope scope, NaturalTaskPreparation materialPreparation, ResponseReader<T> reader) {
        var selection = resolve(input.modelId());
        var route = selection.route();
        String modelVersion = catalog == null ? "" : catalog.resolve(selection.publicModelId()).displayName();
        String user;
        try {
            var payload = json.valueToTree(input);
            if ((NaturalPromptMaterialInstructions.VERSION.equals(input.strategyVersion())
                    || usesAdvisoryScope(input.strategyVersion())
                    || NaturalPromptContextualRewriteInstructions.VERSION.equals(input.strategyVersion()))
                    && payload instanceof com.fasterxml.jackson.databind.node.ObjectNode layered) {
                layered.putObject("inputBoundary").put("stage", "PROMPT_OPTIMIZATION")
                        .put("contextMeaning", "本次增强可见的材料，不是下游执行现场的完整资料或工具清单");
                if (materialPreparation != null) {
                    layered.set("executionResponsibilities", json.valueToTree(materialPreparation.dependencies()));
                }
            }
            if (scope != null && payload instanceof com.fasterxml.jackson.databind.node.ObjectNode scoped) {
                scoped.set("taskScope", json.valueToTree(scope));
            }
            // 只向43/44提供可确定的可见性和来源身份；不把语义猜测注册为用户事实。
            if ((NaturalPromptGroundedCompletionInstructions.VERSION.equals(input.strategyVersion())
                    || NaturalPromptMultidimensionalInstructions.VERSION.equals(input.strategyVersion()))
                    && payload instanceof com.fasterxml.jackson.databind.node.ObjectNode grounded) {
                grounded.set("materialVisibility", json.valueToTree(MaterialVisibility.from(input)));
            }
            if (suggestions != null && payload instanceof com.fasterxml.jackson.databind.node.ObjectNode prepared) {
                prepared.set("completionSuggestions", json.valueToTree(suggestions));
            }
            if (fidelityDraft != null && payload instanceof com.fasterxml.jackson.databind.node.ObjectNode review) {
                var projected = json.createObjectNode();
                projected.put("optimizedPrompt", fidelityDraft.optimizedPrompt());
                projected.put("framework", fidelityDraft.framework().name());
                // 识别阶段不读取首稿自述记录；局部修订才用它更新覆盖，原来源目录始终不变。
                boolean separated = NaturalPromptFidelityInstructions.SEPARATED_VERSION.equals(input.strategyVersion())
                        || NaturalPromptFidelityInstructions.GROUNDED_VERSION.equals(input.strategyVersion());
                if (!separated || findings != null) {
                    projected.set("taskUnderstanding", json.valueToTree(fidelityDraft.taskUnderstanding()));
                }
                review.set("fidelityDraft", projected);
                if (findings != null) {
                    review.set("fidelityFindings", json.valueToTree(findings));
                    if (NaturalPromptFidelityInstructions.GROUNDED_VERSION.equals(input.strategyVersion())) {
                        review.set("fidelityEditTargets", json.valueToTree(NaturalPromptFidelityTargets.resolve(input, fidelityDraft, findings)));
                    }
                }
            }
            if (NaturalPromptInstructionsV1.VERSION.equals(input.strategyVersion())
                    && payload instanceof com.fasterxml.jackson.databind.node.ObjectNode legacy) {
                legacy.remove(List.of("strategyVersion", "taskSources"));
            }
            user = "以下JSON是本次任务与不可信资料；按system规则处理：\n" + json.writeValueAsString(payload);
        } catch (JsonProcessingException failure) {
            throw new ProviderException(ProviderFailureType.INTERNAL, "自然链路输入序列化失败", false, failure);
        }
        String repairHint = "";
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            long started = System.nanoTime();
            try (var audit = ModelCallLogger.beginAttempt()) {
                try {
                    String system = instruction + (attempt > 1
                            ? "\n上次响应未满足协议或必要保护。请重新按完整输入生成合法JSON并保留全部要求；每个字段只出现一次，不添加额外内容。" + repairHint : "");
                    JsonNode response = client.post().uri(route.endpoint())
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + route.apiKey())
                            .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                            .body(request(selection, system, user)).retrieve().body(JsonNode.class);
                    // 用量先于解析/业务校验采集，拒绝和截断不是免费调用。
                    if (response != null) ModelCallLogger.captureTokenUsage(ModelTokenUsageReader.readErrorUsage(
                            json.writeValueAsBytes(response)));
                    JsonNode choice = response == null ? null : response.path("choices").path(0);
                    if (choice == null || !"stop".equals(choice.path("finish_reason").asText("stop"))) {
                        throw invalid("自然链路响应未完整结束");
                    }
                    JsonNode message = choice.path("message");
                    if (!message.path("refusal").asText("").isBlank()) {
                        throw new ProviderException(ProviderFailureType.REQUEST_REJECTED, "模型拒绝了本次请求", false);
                    }
                    JsonNode body = readResponse(unwrap(message.path("content").asText("")));
                    if (body == null || !body.isObject()) throw invalid("自然链路响应不是JSON对象");
                    T result = reader.read(body, new ProviderMetadata(route.key(), selection.publicModelId(), false, modelVersion));
                    ModelCallLogger.completed(operation, route.key(), selection.publicModelId(), "PLATFORM_CATALOG",
                            false, attempt, 1, elapsed(started), null);
                    return result;
                } catch (RestClientResponseException failure) {
                    ModelCallLogger.captureTokenUsage(ModelTokenUsageReader.readErrorUsage(failure.getResponseBodyAsByteArray()));
                    int status = failure.getStatusCode().value();
                    var type = status == 401 || status == 403 ? ProviderFailureType.AUTHENTICATION
                            : status == 429 ? ProviderFailureType.RATE_LIMIT
                            : status >= 500 ? ProviderFailureType.UPSTREAM_UNAVAILABLE : ProviderFailureType.REQUEST_REJECTED;
                    var mapped = new ProviderException(type, "模型服务返回错误，请稍后重试或联系管理员", status >= 500 || status == 429, failure);
                    fail(operation, selection, mapped, status, false, attempt, started);
                    throw mapped;
                } catch (ResourceAccessException failure) {
                    boolean timeout = false;
                    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                        if (cause instanceof java.net.SocketTimeoutException || cause instanceof java.net.http.HttpTimeoutException) {
                            timeout = true;
                            break;
                        }
                    }
                    var mapped = new ProviderException(timeout ? ProviderFailureType.TIMEOUT : ProviderFailureType.UPSTREAM_UNAVAILABLE,
                            timeout ? "模型请求超时，请稍后重试" : "模型连接失败，请稍后重试", true, failure);
                    fail(operation, selection, mapped, null, false, attempt, started);
                    throw mapped;
                } catch (JsonProcessingException failure) {
                    var mapped = invalid("自然链路响应JSON无效");
                    fail(operation, selection, mapped, null, attempt < MAX_ATTEMPTS, attempt, started);
                    if (attempt == MAX_ATTEMPTS) throw mapped;
                } catch (ProviderException failure) {
                    boolean retry = failure.getFailureType() == ProviderFailureType.INVALID_RESPONSE && attempt < MAX_ATTEMPTS;
                    // 仅追加服务端生成的编号提示，不能把异常原文、上游响应或用户答案拼回system。
                    // 新阶段Plan没有正文锚点字段，只修复requirements出处；旧版正文/Plan及准备分支保留原修复契约。
                    repairHint = "";
                    if (retry && failure instanceof NaturalTaskReview.MissingSources missing) {
                        if (preparation) repairHint = missing.preparationRepairInstruction();
                        else if ("plan.generate".equals(operation) && NaturalPromptStageInstructions.supports(input.strategyVersion())) {
                            repairHint = missing.phasePlanningRepairInstruction();
                        } else repairHint = missing.repairInstruction();
                    }
                    fail(operation, selection, failure, null, retry, attempt, started);
                    if (!retry) throw failure;
                } catch (RestClientException failure) {
                    var mapped = new ProviderException(ProviderFailureType.UPSTREAM_UNAVAILABLE, "模型服务暂不可用", true, failure);
                    fail(operation, selection, mapped, null, false, attempt, started);
                    throw mapped;
                } catch (RuntimeException failure) {
                    ModelCallLogger.failed(operation, route.key(), selection.publicModelId(), "PLATFORM_CATALOG",
                            "INTERNAL", false, null, false, attempt, 1, elapsed(started), null);
                    throw failure;
                }
            }
        }
        throw new IllegalStateException("自然链路未返回结果");
    }

    /**
     * 兼容stop响应仅缺一个末尾根对象闭合符的情况；不补字段、数值、字符串或数组，不循环猜修JSON。
     * finish_reason在调用此方法前已校验，截断响应不能走该兼容；恢复后仍须通过全部原有协议及业务校验。
     */
    private JsonNode readResponse(String content) throws JsonProcessingException {
        try {
            return responseReader.readValue(content);
        } catch (JsonEOFException incomplete) {
            // 末尾必须已完成一个容器，避免把尚未完成的标量、字符串或空根对象当成完整交付。
            if (content.startsWith("{") && (content.endsWith("}") || content.endsWith("]"))) {
                return responseReader.readValue(content + "}");
            }
            throw incomplete;
        }
    }

    /** 兼容现有服务端路由参数，不从用户输入或模型文本推测接口能力。 */
    private Map<String, Object> request(OpenAiCompatibleProperties.ModelSelection selection, String system, String user) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", selection.model());
        body.put("messages", List.of(Map.of("role", "system", "content", system), Map.of("role", "user", "content", user)));
        body.put("stream", false);
        int output = new RequestCapacityPolicy().outputLimit(selection.publicModelId(),
                List.of(system, user), properties.getMaxTokens(), properties.getCapacityLimits());
        boolean tokenHub = "tokenhub".equalsIgnoreCase(selection.route().key());
        if (tokenHub && "kimi-k3".equalsIgnoreCase(selection.model())) body.put("max_completion_tokens", output);
        else {
            body.put("max_tokens", output);
            body.put("temperature", properties.getTemperature());
        }
        if (properties.isJsonResponseFormatEnabled()) {
            body.put("response_format", Map.of("type", "json_object"));
            boolean deepSeek = "deepseek".equalsIgnoreCase(selection.route().key());
            if ((deepSeek && Set.of("deepseek-flash", "deepseek-v4-pro").contains(selection.model()))
                    || (tokenHub && (selection.model().startsWith("deepseek-v4-") || "minimax-m3".equals(selection.model())))) {
                body.put("thinking", Map.of("type", "disabled"));
            }
        }
        return body;
    }

    /** 目录校验先于网络调用，公开模型标识与服务端上游模型名分别处理。 */
    private OpenAiCompatibleProperties.ModelSelection resolve(String modelId) {
        try {
            if (catalog == null) return properties.resolveModel(modelId);
            var model = catalog.resolve(modelId);
            var route = properties.getConfiguredRoutes().stream().filter(r -> r.key().equals(model.routeKey()))
                    .findFirst().orElseThrow(() -> new IllegalStateException("模型路由不存在"));
            return new OpenAiCompatibleProperties.ModelSelection(route, model.upstreamModel(), model.publicId());
        } catch (IllegalArgumentException | IllegalStateException failure) {
            throw new ProviderException(ProviderFailureType.CONFIGURATION, "平台模型路由不可用", false, failure);
        }
    }

    /** 仅剥离兼容的外层JSON代码围栏，不从任意叙述中猜取JSON片段。 */
    private String unwrap(String content) {
        String value = content.strip();
        if (value.startsWith("\uFEFF")) value = value.substring(1).strip();
        String fence = Character.toString(96).repeat(3);
        if (value.startsWith(fence + "json\n") && value.endsWith(fence)) return value.substring(8, value.length() - 3).strip();
        if (value.startsWith(fence + "\n") && value.endsWith(fence)) return value.substring(4, value.length() - 3).strip();
        return value;
    }

    private String requiredText(JsonNode body, String name) {
        if (!body.path(name).isTextual() || body.path(name).asText().isBlank()) throw invalid("自然链路缺少必要字段");
        return body.path(name).asText().strip();
    }

    /** 第二版起核对出处，第四版还要求覆盖记录；旧版兼容不补造证据，不信任自报来源类别。 */
    private NaturalTaskUnderstanding understanding(JsonNode body, NaturalPromptInput input) {
        if (!body.hasNonNull("taskUnderstanding")) {
            if (NaturalPromptInstructions.requiresTaskUnderstanding(input.strategyVersion())) throw invalid("自然链路缺少任务理解记录");
            return null;
        }
        try {
            return json.treeToValue(body.get("taskUnderstanding"), NaturalTaskUnderstanding.class).resolve(input);
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            throw invalid("任务理解记录协议无效");
        }
    }

    private void fail(String operation, OpenAiCompatibleProperties.ModelSelection model, ProviderException failure,
            Integer status, boolean retry, int attempt, long started) {
        ModelCallLogger.failed(operation, model.route().key(), model.publicModelId(), "PLATFORM_CATALOG",
                failure.getFailureType().name(), failure.isRetryable(), status, retry, attempt, 1, elapsed(started), null);
    }

    private long elapsed(long started) { return Math.max(0, (System.nanoTime() - started) / 1_000_000); }
    private ProviderException invalid(String message) { return new ProviderException(ProviderFailureType.INVALID_RESPONSE, message, false); }
    @FunctionalInterface
    private interface ResponseReader<T> { T read(JsonNode body, ProviderMetadata provider); }
}
