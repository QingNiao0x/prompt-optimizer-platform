package com.promptoptimizer.provider.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 模型对待确认文本的可选问题关联；属于不可信输出，不能自行证明问题已解决。
 * message 非空且最多 500 字符，questionId 为 1—64 个英文、数字、下划线或连字符。
 * 无效关联可被丢弃，但不代表提醒正文无效；应用层仍须核对绑定问题及新增业务条件。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record AmbiguityReference(String message, String questionId) {
    public static final int MAX_REFERENCES = 8;
    private static final Pattern QUESTION_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    /**
     * 仅修复首尾空白并接收确实对应提醒正文的关联；不猜测 ID、不修改正文、不降低正文校验。
     * 超量关联整体弃用；同一正文关联多个问题时也弃用该组，避免任意选择导致误合并。
     * 调用方按丢弃数量记录安全诊断，不得记录不可信的关联正文或 ID。
     */
    public static List<AmbiguityReference> normalize(List<AmbiguityReference> references, List<String> findings) {
        if (references == null || references.size() > MAX_REFERENCES || findings == null) return List.of();
        List<AmbiguityReference> accepted = new ArrayList<>();
        for (AmbiguityReference reference : references) {
            if (reference == null || reference.message() == null || reference.questionId() == null) continue;
            String message = reference.message().strip();
            String questionId = reference.questionId().strip();
            if (message.isEmpty() || message.length() > 500 || !QUESTION_ID.matcher(questionId).matches()) continue;
            // 返回实际正文的规范化文本，保持后续关联与结果组装使用同一份提醒。
            findings.stream().filter(value -> value != null && value.strip().equals(message)).findFirst()
                    .ifPresent(value -> accepted.add(new AmbiguityReference(value.trim(), questionId)));
        }
        return accepted.stream().distinct().filter(reference -> accepted.stream()
                .noneMatch(other -> other.message().equals(reference.message())
                        && !other.questionId().equals(reference.questionId()))).toList();
    }
}
