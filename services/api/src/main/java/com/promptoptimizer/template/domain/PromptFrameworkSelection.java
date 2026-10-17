package com.promptoptimizer.template.domain;

import com.promptoptimizer.enhancement.domain.PromptSectionType;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/**
 * 一次请求的主框架选择及具体组织指导，供生成与最终呈现共享，不保存完整输入材料。
 *
 * @param framework 唯一主框架，不表示效果已优于原始提示词
 * @param reason 基于当前交付目标的简短选择依据
 * @param instruction 当前框架的生成指导及适用边界
 * @param sectionOrder 自然正文的段落排列建议，不能据此创建段落或删除内容
 * @author QingNiao
 * @since 0.1.0
 */
public record PromptFrameworkSelection(PromptFramework framework, String reason, String instruction,
                                       List<PromptSectionType> sectionOrder) {
    /** 顺序不可由调用方修改，且必须保留全部已有段落类型以免呈现时丢失内容。 */
    public PromptFrameworkSelection {
        Objects.requireNonNull(framework, "framework");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(instruction, "instruction");
        sectionOrder = List.copyOf(sectionOrder);
        if (sectionOrder.size() != PromptSectionType.values().length
                || !EnumSet.copyOf(sectionOrder).containsAll(EnumSet.allOf(PromptSectionType.class))) {
            throw new IllegalArgumentException("Framework order must retain each existing section type once");
        }
    }
}
