package com.promptoptimizer.enhancement.application;

import com.promptoptimizer.context.application.ContextAnalyzer;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.api.ConversationMessage;
import com.promptoptimizer.enhancement.api.OptimizationRequest;
import com.promptoptimizer.enhancement.domain.OptimizationResult;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.ProviderMetadata;
import com.promptoptimizer.policy.application.ConstraintCompleter;
import com.promptoptimizer.provider.application.PromptEnhancementProvider;
import com.promptoptimizer.provider.domain.EnhancementProviderRequest;
import com.promptoptimizer.provider.domain.EnhancementProviderResponse;
import com.promptoptimizer.template.application.PromptTemplateRegistry;
import com.promptoptimizer.template.domain.PromptTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 默认提示词增强编排器。
 *
 * <p>按“上下文分析 → 模糊点识别 → 模板选择 → 约束补全 → Provider 生成”的顺序执行，
 * 确保真实模型和 Mock 模型共享同一套业务规则。</p>
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Service
public class DefaultEnhancementOrchestrator implements EnhancementOrchestrator {

    private final ContextAnalyzer contextAnalyzer;
    private final AmbiguityDetector ambiguityDetector;
    private final PromptTemplateRegistry templateRegistry;
    private final ConstraintCompleter constraintCompleter;
    private final PromptEnhancementProvider enhancementProvider;
    private final Clock clock;

    @Autowired
    public DefaultEnhancementOrchestrator(
            ContextAnalyzer contextAnalyzer,
            AmbiguityDetector ambiguityDetector,
            PromptTemplateRegistry templateRegistry,
            ConstraintCompleter constraintCompleter,
            PromptEnhancementProvider enhancementProvider
    ) {
        this(
                contextAnalyzer,
                ambiguityDetector,
                templateRegistry,
                constraintCompleter,
                enhancementProvider,
                Clock.systemUTC()
        );
    }

    DefaultEnhancementOrchestrator(
            ContextAnalyzer contextAnalyzer,
            AmbiguityDetector ambiguityDetector,
            PromptTemplateRegistry templateRegistry,
            ConstraintCompleter constraintCompleter,
            PromptEnhancementProvider enhancementProvider,
            Clock clock
    ) {
        this.contextAnalyzer = contextAnalyzer;
        this.ambiguityDetector = ambiguityDetector;
        this.templateRegistry = templateRegistry;
        this.constraintCompleter = constraintCompleter;
        this.enhancementProvider = enhancementProvider;
        this.clock = clock;
    }

    /**
     * 按固定顺序完成上下文分析、模糊点识别、模板选择、约束补全和模型生成。
     */
    @Override
    public OptimizationResult optimize(OptimizationRequest request) {
        long startedAt = clock.millis();
        ContextSnapshot context = contextAnalyzer.analyze(request.context(), request.rawPrompt());
        List<String> ambiguities = ambiguityDetector.detect(request.rawPrompt());
        PromptTemplate template = templateRegistry.resolve(
                request.enhancement().templateCode(),
                request.rawPrompt()
        );
        List<String> constraints = constraintCompleter.complete(
                context,
                request.permissionPolicy(),
                Boolean.TRUE.equals(request.enhancement().includePermissionBoundaries())
        );
        List<ConversationMessage> conversation = Boolean.TRUE.equals(request.enhancement().includeConversationHistory())
                ? request.conversationHistory()
                : List.of();

        EnhancementProviderResponse providerResponse = enhancementProvider.enhance(new EnhancementProviderRequest(
                request.rawPrompt(),
                context,
                template,
                ambiguities,
                constraints,
                conversation,
                request.enhancement()
        ));

        return new OptimizationResult(
                renderPrompt(providerResponse.sections()),
                providerResponse.sections(),
                context,
                ambiguities,
                constraints,
                template.code(),
                new ProviderMetadata(
                        providerResponse.provider(),
                        providerResponse.model(),
                        providerResponse.mock()
                ),
                Math.max(0, clock.millis() - startedAt)
        );
    }

    /**
     * 将结构化段落渲染为可直接阅读的 Markdown 提示词。
     */
    private String renderPrompt(List<PromptSection> sections) {
        return sections.stream()
                .filter(section -> section.type() != com.promptoptimizer.enhancement.domain.PromptSectionType.CLARIFICATIONS)
                .map(section -> "## " + section.title() + "\n" + section.content())
                .collect(Collectors.joining("\n\n"));
    }
}
