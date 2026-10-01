package com.promptoptimizer.provider.domain;

/**
 * 模型对待确认文本的可选问题关联；属于不可信输出，不能自行证明问题已解决。
 * message 非空且最多 500 字符，questionId 为 1—64 个英文、数字、下划线或连字符。
 * 应用层须核对 message 对应的提醒、已绑定的 questionId 及是否存在新增业务条件。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record AmbiguityReference(String message, String questionId) { }
