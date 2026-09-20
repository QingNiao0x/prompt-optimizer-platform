package com.promptoptimizer.provider.infrastructure;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.api.ConversationMessage;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.provider.application.PromptEnhancementProvider;
import com.promptoptimizer.provider.domain.EnhancementProviderRequest;
import com.promptoptimizer.provider.domain.EnhancementProviderResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 无需 API Key 的确定性增强实现，用于本地开发、自动化测试和接口联调。
 *
 * <p>该实现不会假装拥有大模型推理能力，只依据已识别上下文和规则生成可审查的结构化结果。</p>
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Component
@ConditionalOnProperty(prefix = "app.provider", name = "mode", havingValue = "mock", matchIfMissing = true)
public class MockPromptEnhancementProvider implements PromptEnhancementProvider {

    /**
     * 根据上下文、模板和约束生成确定性的结构化提示词。
     */
    @Override
    public EnhancementProviderResponse enhance(EnhancementProviderRequest request) {
        List<PromptSection> sections = new ArrayList<>();
        sections.add(new PromptSection(PromptSectionType.BACKGROUND, "背景", buildBackground(request)));
        sections.add(new PromptSection(PromptSectionType.TASK, "任务目标", buildTask(request)));
        sections.add(new PromptSection(PromptSectionType.OUTPUT, "期望输出", request.template().outputGuidance()));
        sections.add(new PromptSection(PromptSectionType.CONSTRAINTS, "约束条件", toMarkdownList(request.constraints())));

        if (!request.ambiguities().isEmpty()) {
            sections.add(new PromptSection(
                    PromptSectionType.CLARIFICATIONS,
                    "待确认项",
                    toMarkdownList(request.ambiguities())
            ));
        }
        sections.add(new PromptSection(
                PromptSectionType.ACCEPTANCE,
                "验收标准",
                request.template().acceptanceGuidance()
        ));
        if (Boolean.TRUE.equals(request.options().includeExamples())) {
            sections.add(new PromptSection(
                    PromptSectionType.EXAMPLES,
                    "示例参考",
                    request.template().exampleGuidance()
            ));
        }
        return new EnhancementProviderResponse(sections, "mock", "deterministic-enhancer-v1", true,
                request.ambiguities());
    }

    /**
     * 从项目描述、技术栈、目录和会话历史组装背景段落。
     */
    private String buildBackground(EnhancementProviderRequest request) {
        ContextSnapshot context = request.context();
        List<String> parts = new ArrayList<>();
        if (!context.customDescription().isBlank()) {
            parts.add(context.customDescription());
        }
        if (!context.technologyStack().isEmpty()) {
            String stack = context.technologyStack().stream()
                    .map(item -> item.name())
                    .collect(Collectors.joining("、"));
            parts.add("已识别技术栈：" + stack + "。");
        }
        if (!context.directoryTree().isEmpty()) {
            String files = context.directoryTree().stream()
                    .filter(path -> !path.endsWith("/"))
                    .limit(8)
                    .collect(Collectors.joining("、"));
            if (!files.isBlank()) {
                parts.add("相关资料或项目文件：" + files + "。");
            }
        }
        if (Boolean.TRUE.equals(request.options().includeConversationHistory())
                && !request.conversationHistory().isEmpty()) {
            parts.add("当前会话补充：" + summarizeConversation(request.conversationHistory()));
        }
        if (parts.isEmpty()) {
            return "未提供额外上下文；不得臆造研究口径、业务规则、技术栈或现有文件。";
        }
        return String.join("\n", parts);
    }

    /**
     * 组装纯净的任务目标段落；待确认项由独立的 CLARIFICATIONS 段承载。
     */
    private String buildTask(EnhancementProviderRequest request) {
        return "将以下原始需求落实为具体、可执行且可验证的任务说明：\n"
                + request.rawPrompt().trim();
    }

    /**
     * 将最近会话消息压缩为一段文本。
     */
    private String summarizeConversation(List<ConversationMessage> messages) {
        return messages.stream()
                .limit(5)
                .map(message -> message.role() + "：" + message.content().trim())
                .collect(Collectors.joining("；"));
    }

    /**
     * 将字符串列表渲染为 Markdown 列表。
     */
    private String toMarkdownList(List<String> values) {
        return values.stream()
                .map(value -> "- " + value)
                .collect(Collectors.joining("\n"));
    }
}
