package com.promptoptimizer.imageprompt.service;

import com.promptoptimizer.imageprompt.domain.ImagePromptModels.*;
import com.promptoptimizer.imageprompt.domain.ImagePromptMediaModels.MaterialSource;
import java.util.List;

/**
 * 图像业务模型端口；实际图片只由授权资产服务提供，最终生成可以复用服务端分析而不重复识图。
 * @author QingNiao
 * @since 0.1.0
 */
public interface ImagePromptProvider {
    /** 一次业务阶段调用，共用有限协议修复预算，不在接口内保存历史。 */
    ProviderResult invoke(Call call);
    record ImageData(ImageSource source, String mimeType, byte[] bytes) { }
    record Call(Stage stage, Input input, List<ImageData> images, List<Observation> analysis,
                List<Question> questions, List<Answer> answers, String previousPrompt, String revisionInstruction,
                List<MaterialSource> materialSources, int maxAttempts, List<String> evidenceWarnings) {
        /** 所有识别、Plan、生成与协议修复共享应用服务分配的有限预算。 */
        public Call {
            materialSources = materialSources == null ? List.of() : List.copyOf(materialSources);
            evidenceWarnings = evidenceWarnings == null ? List.of() : List.copyOf(evidenceWarnings);
            if (maxAttempts < 1 || maxAttempts > 2) throw new IllegalArgumentException("单阶段模型调用上限无效");
        }
        /** 兼容未单独提供来源风险的旧阶段调用。 */
        public Call(Stage stage, Input input, List<ImageData> images, List<Observation> analysis,
                    List<Question> questions, List<Answer> answers, String previousPrompt, String revisionInstruction,
                    List<MaterialSource> materialSources, int maxAttempts) {
            this(stage, input, images, analysis, questions, answers, previousPrompt, revisionInstruction, materialSources, maxAttempts, List.of());
        }
        public Call(Stage stage, Input input, List<ImageData> images, List<Observation> analysis,
                    List<Question> questions, List<Answer> answers, String previousPrompt, String revisionInstruction) {
            this(stage, input, images, analysis, questions, answers, previousPrompt, revisionInstruction, List.of(), 2);
        }
    }
}
