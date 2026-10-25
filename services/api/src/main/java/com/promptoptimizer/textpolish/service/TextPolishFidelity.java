package com.promptoptimizer.textpolish.service;

import com.promptoptimizer.common.security.SensitiveValueScanner;
import com.promptoptimizer.textpolish.domain.TextPolishModels.*;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 只强制可确定的锁定要求；数值差异是审阅线索，不宣称已证明语义保真。
 * 不按文风词黑名单删文章，也不根据改动比例决定质量。
 * @author QingNiao
 * @since 0.1.0
 */
@Component
public class TextPolishFidelity {
    // 仅用于定位数字片段变化；不把数字相同推断为对象、单位、否定或条件相同。
    private static final Pattern NUMBER = Pattern.compile("[+-]?\\d+(?:[.,]\\d+)*(?:%|％)?");
    private final SensitiveValueScanner scanner = new SensitiveValueScanner();
    /** 输入保留原有换行和空格，锁定短语必须真实属于这份原文。 */
    public void validateInput(Input input) {
        if (input.sourceText() == null || input.sourceText().isBlank() || input.sourceText().length() > 12000)
            throw TextPolishException.invalid("请输入正文，最多 12000 字符；超长文章请分次处理。");
        safe(input.sourceText()); safe(input.audience()); safe(input.requirements());
        if (input.lockedTexts().size() > 20) throw TextPolishException.invalid("最多保护20段文字。");
        for (String locked : input.lockedTexts()) {
            if (locked == null || locked.isBlank() || locked.length() > 1000 || !input.sourceText().contains(locked))
                throw TextPolishException.invalid("每段保护内容须实际出现在本次原文中，且不超过1000字符。");
        }
    }
    /** 锁定原句及其出现次数均须不变，拒绝遗漏或额外重复；其他语义仍需结合原文审阅。 */
    public List<Check> check(Input input, String output) {
        if (output == null || output.isBlank() || output.length() > 24000) throw TextPolishException.invalid("润色正文为空或超过24000字符。");
        safe(output);
        if (input.lockedTexts().stream().anyMatch(t -> count(output, t) != count(input.sourceText(), t)))
            throw new TextPolishException(422, "TEXT_POLISH_LOCK_CHANGED", "本次结果改动了需要保持原样的内容，已保留原文和上一稿，请调整要求后重新润色。");
        List<Check> checks = new ArrayList<>();
        if (!input.lockedTexts().isEmpty()) checks.add(new Check("LOCKS_PRESERVED", "指定保护的文字已按原样保留。", "", ""));
        if (input.sourceText().equals(output)) checks.add(new Check("UNCHANGED", "本次保留原文，未作文字改动。", "", ""));
        var before = NUMBER.matcher(input.sourceText()).results().map(m -> m.group()).toList();
        var after = NUMBER.matcher(output).results().map(m -> m.group()).toList();
        if (!before.equals(after)) checks.add(new Check("NUMBERS_CHANGED", "数字片段或出现顺序发生变化，请结合对象、单位和条件核对。", preview(before.toString()), preview(after.toString())));
        return List.copyOf(checks);
    }
    /** 凭据不得进入上游、持久化或可复制正文；文章命令仍只是待处理材料。 */
    public void safe(String text) {
        if (scanner.containsCredential(text)) throw TextPolishException.invalid("文本包含疑似敏感凭据，请脱敏后重试。");
    }
    /** 按不重叠的完整原句计数，避免模型通过复制锁定句增加原文没有的重复陈述。 */
    private int count(String text, String part) { int total = 0, at = 0; while ((at = text.indexOf(part, at)) >= 0) { total++; at += part.length(); } return total; }
    private String preview(String text) { return text.length() <= 220 ? text : text.substring(0, 220) + "…"; }
}
