package com.promptoptimizer.enhancement.domain;

import java.util.List;

/**
 * 服务端记录的自然增强处理事实，保存于优化记录的result_metadata.processing。
 * 只含已完成阶段及生成时正文与原文是否一致；不含来源原句，不评价补充价值或语义质量。
 * 旧记录缺失时保持未知，不能根据供应商、耗时或正文相同推断执行过哪些阶段。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record NaturalProcessing(List<Stage> completedStages, boolean originalPreserved) {
    /** 阶段按实际调用路径记录，不按模型返回的分类或自检声明推断。 */
    public enum Stage {
        /** 完成动作与回应范围识别，尚不表示正文生成或需求充分。 */ SCOPE_ANALYSIS,
        /** 完成正文生成及应用层校验；不等于获得了实质增强收益。 */ BODY_GENERATION,
        /** 53完成原要求来源登记；没有执行动作/回应分类，不代表语义理解或正文已通过。 */ REQUIREMENT_PREPARATION,
        /** 正文先行候选固定正文后完成来源/覆盖登记和原有形式校验，不代表语义质量评审通过。 */ RECORD_LINKING
    }

    public NaturalProcessing {
        completedStages = List.copyOf(completedStages);
        if (completedStages.isEmpty() || completedStages.stream().distinct().count() != completedStages.size()) {
            throw new IllegalArgumentException("自然处理阶段为空或重复");
        }
    }

    /** 最终组装后比较文本；只去首尾空白，不把不同文本自动判断为实质提升。 */
    public static NaturalProcessing completed(List<Stage> stages, String raw, String body) {
        return stages == null ? null : new NaturalProcessing(stages, raw.strip().equals(body.strip()));
    }
}
