package com.promptoptimizer.imageprompt.service;

import com.promptoptimizer.imageprompt.domain.ImagePromptModels.*;
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
                List<Question> questions, List<Answer> answers, String previousPrompt, String revisionInstruction) { }
}
