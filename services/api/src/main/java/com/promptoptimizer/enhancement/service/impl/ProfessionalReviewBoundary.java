package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.EffectiveTaskView;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException;
import com.promptoptimizer.template.domain.TaskDeliveryProfile;
import java.util.List;
import java.util.regex.Pattern;

/**
 * MVP只标识专业使用边界并拒绝新增的专家认证声明，不充当专业真伪判官。
 * 说明走结果warnings，不追加到纯译文、JSON或用户复制正文。
 */
final class ProfessionalReviewBoundary {
    private static final Pattern FALSE_CERTIFICATION = Pattern.compile(
            "(?m)^(?:本(?:结果|内容|方案|报告)|以上(?:内容|方案|报告))(?:已经|已)(?:通过)?"
                    + "(?:专家(?:复核|审核|认证)|临床验证)(?:通过)?[。！.]?$");

    private ProfessionalReviewBoundary() { }

    /** 场景和用途共同限定提示范围，不因缺少专业资料取消用户其余交付。 */
    static List<String> warnings(EffectiveTaskView task) {
        String raw = task.originalPrompt();
        TaskDeliveryProfile profile = task.intent().deliveryProfile();
        boolean highConsequence = profile == TaskDeliveryProfile.LEGAL_MATERIAL
                || profile == TaskDeliveryProfile.MEDICAL_MATERIAL
                || raw.matches("(?s).*(?:患者|病人|个体|心脑血管|YLL|减寿).*(?:诊断|治疗|用药|剂量|死亡率|分析).*")
                || raw.matches("(?s).*(?:投资|金融|财务).*(?:决策|建议|估值|预测).*");
        return highConsequence ? List.of(
                "专业复核状态：尚未复核。本平台仅生成提示词，来源适用性检查不等于专业结论验证；"
                        + "用于诊疗、法律或财务决策前，需由相应专业人员核查依据、版本和适用条件。") : List.of();
    }

    /** 当前没有专家复核服务，翻译或引用原有声明不被当成平台新认证。 */
    static void validate(String text, EffectiveTaskView task) {
        if (task.intent().deliveryProfile() == TaskDeliveryProfile.TRANSLATION) return;
        var matcher = FALSE_CERTIFICATION.matcher(text);
        while (matcher.find()) {
            if (!task.originalPrompt().contains(matcher.group())) {
                throw new ProviderResponseValidationException(
                        ProviderResponseValidationException.Reason.PROFESSIONAL_REVIEW_UNSUPPORTED, "sections");
            }
        }
    }
}
