package com.promptoptimizer.provider.domain;

import com.promptoptimizer.common.security.SensitiveValueScanner;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 独立识别自然正文中的保真问题，锁定修订阶段可处理的来源和首稿范围。
 * effect只说明模型判断的交付影响，不构成新来源，也不证明问题或修订在语义上成立。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record NaturalPromptFidelityFindings(List<Finding> issues) {
    /** 影响说明的UTF-16长度上限，与既有保真片段容量一致，不截断模型记录。 */
    private static final int MAX_EFFECT_LENGTH = 20_000;
    private static final SensitiveValueScanner SENSITIVE = new SensitiveValueScanner();

    /** 防御性复制识别列表；缺省和空条目由validate按统一协议异常拒绝。 */
    public NaturalPromptFidelityFindings {
        issues = issues == null ? null : Collections.unmodifiableList(new ArrayList<>(issues));
    }

    /**
     * 校验识别记录的容量、凭据、来源及首稿锚点；空列表合法，但不能省略列表。
     * 以原片段替换自身复用既有来源、唯一性、非重叠和覆盖保护，不将识别记录升为事实。
     */
    public void validate(NaturalPromptInput input, NaturalPromptDraft draft) {
        if (issues == null || input == null || draft == null) {
            throw invalid("保真识别记录不完整");
        }
        var identityPatches = new ArrayList<NaturalPromptFidelityReview.Issue>(issues.size());
        for (Finding finding : issues) {
            if (finding == null || finding.effect() == null || finding.effect().isBlank()
                    || finding.effect().length() > MAX_EFFECT_LENGTH || SENSITIVE.containsCredential(finding.effect())) {
                throw invalid("保真识别影响说明无效");
            }
            identityPatches.add(new NaturalPromptFidelityReview.Issue(finding.type(), finding.sourceId(),
                    finding.sourceQuote(), finding.bodyQuote(), finding.bodyQuote()));
        }
        // 无问题保持既有noissues协议；有问题时使用原任务记录，验证不会借识别阶段重写正文或来源。
        new NaturalPromptFidelityReview(identityPatches, issues.isEmpty() ? null : draft.taskUnderstanding()).apply(input, draft);
    }

    /**
     * 仅允许修订已识别的同序问题，类型、来源及首稿片段必须逐字相同。
     * 修订阶段只提供replacement及更新后的任务记录，最终仍执行既有来源、安全与覆盖校验。
     */
    public NaturalPromptDraft applyRepair(NaturalPromptInput input, NaturalPromptDraft draft,
            NaturalPromptFidelityReview review) {
        // 不假定调用方此前已针对同一输入和首稿校验，避免将另一份识别结果用于当前正文。
        validate(input, draft);
        if (review == null || review.issues() == null || review.issues().size() != issues.size()) {
            throw invalid("保真修订与识别问题不一致");
        }
        for (int index = 0; index < issues.size(); index++) {
            Finding finding = issues.get(index);
            NaturalPromptFidelityReview.Issue repair = review.issues().get(index);
            if (repair == null || finding.type() != repair.type()
                    || !Objects.equals(finding.sourceId(), repair.sourceId())
                    || !Objects.equals(finding.sourceQuote(), repair.sourceQuote())
                    || !Objects.equals(finding.bodyQuote(), repair.bodyQuote())) {
                throw invalid("保真修订改变了已识别问题的范围");
            }
        }
        return review.apply(input, draft);
    }

    /** 来源和正文引文沿用既有保真问题协议；effect记录实质交付影响，不提供替换正文。 */
    public record Finding(NaturalPromptFidelityReview.Type type, String sourceId, String sourceQuote,
            String bodyQuote, String effect) { }

    /** 协议异常不包含模型原文、用户来源或凭据。 */
    private static ProviderException invalid(String message) {
        return new ProviderException(ProviderFailureType.INVALID_RESPONSE, message, false);
    }
}
