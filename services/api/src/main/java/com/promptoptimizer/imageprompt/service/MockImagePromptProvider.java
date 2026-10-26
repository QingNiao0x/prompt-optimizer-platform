package com.promptoptimizer.imageprompt.service;

import com.promptoptimizer.common.logging.ModelCallLogger;
import com.promptoptimizer.imageprompt.domain.ImagePromptModels.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.*;

/**
 * 本机演示适配器只验证交互和协议，不声称理解像素；界面、历史与计量都明确标记 Mock。
 * @author QingNiao
 * @since 0.1.0
 */
@Component
@ConditionalOnProperty(prefix = "app.provider", name = "mode", havingValue = "mock", matchIfMissing = true)
public class MockImagePromptProvider implements ImagePromptProvider {
    /** 返回带演示标识的阶段样例；不以文件名或占位说明伪装视觉识别结果。 */
    @Override
    public ProviderResult invoke(Call call) {
        var warnings = List.of("演示模型不具备真实视觉理解能力；本结果仅用于验证交互流程。");
        String output = switch (call.input().outputType()) { case IMAGE -> "图片"; case VIDEO -> "视频"; case PPT -> "PPT"; };
        List<Question> questions = call.stage() == Stage.PLAN && call.input().creationIntent().length() < 80
                ? List.of(new Question("style", QuestionType.SINGLE_CHOICE, "新" + output + "希望采用哪种视觉风格？",
                List.of(new Option("illustration", "柔和插画"), new Option("photo", "自然摄影")), true)) : List.of();
        String prompt = call.stage() == Stage.PLAN || call.stage() == Stage.ANALYZE ? "" : call.input().mode() == Mode.REFERENCE_RECONSTRUCTION
                ? "演示提示词：依据参考图中实际可见的主体、关系、构图和配色整理画面描述；此演示未进行真实识图。"
                : "创作全新的" + output + "：" + call.input().creationIntent() + "。根据用户实际确认安排主体、风格与画面细节。";
        if (call.stage() == Stage.REWRITE) prompt = call.previousPrompt() + "\n表达调整要求：" + call.revisionInstruction();
        var result = new ProviderResult(new ProviderDraft("演示模式的图像提示词任务", prompt, List.of(), questions, warnings),
                call.input().modelId(), "演示模型", true);
        ModelCallLogger.completed(call.stage() == Stage.PLAN ? "image.plan" : call.stage() == Stage.ANALYZE ? "image.analyze" : "image.generate", "mock", call.input().modelId(),
                "PLATFORM_CATALOG", true, 1, call.images().size(), 0, null);
        return result;
    }
}
