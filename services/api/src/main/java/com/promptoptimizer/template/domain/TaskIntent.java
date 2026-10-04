package com.promptoptimizer.template.domain;

import com.promptoptimizer.enhancement.domain.TemplateCode;
import java.util.List;

/**
 * 本次交付意图的内部只读结果；识别依据不证明材料中的功能已经实现。
 * 不增加公开模板枚举，也不接受客户端声明的确认状态。
 *
 * @param templateCode 最终采用的既有模板代码，不包含 AUTO
 * @param deliveryProfile 本次主交付画像，仅用于内部输出、提问与验收指导
 * @param status 选择来源；DEFAULT 表示缺少可识别目标，不证明任务没有缺口
 * @param auxiliaryProfiles 原始目标中明确的附带交付，不能由附件关键词补造
 * @param engineeringConstraints 主目标或明确辅助交付是否包含软件代码；辅助代码检查不能扩散到报告正文
 * @author QingNiao
 * @since 0.1.0
 */
public record TaskIntent(TemplateCode templateCode, TaskDeliveryProfile deliveryProfile,
                         ResolutionStatus status, List<TaskDeliveryProfile> auxiliaryProfiles,
                         boolean engineeringConstraints) {
    /** 防御性复制附带交付列表，避免调用方在识别后改变约束适用范围。 */
    public TaskIntent {
        auxiliaryProfiles = List.copyOf(auxiliaryProfiles);
    }

    /** EXPLICIT：调用者显式模板；RAW_GOAL：原始目标；USER_CONFIRMED：绑定交付答案；DEFAULT：未识别目标。 */
    public enum ResolutionStatus { EXPLICIT, RAW_GOAL, USER_CONFIRMED, DEFAULT }
}
