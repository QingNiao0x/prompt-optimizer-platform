package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.template.domain.TaskDeliveryProfile;
import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 将明确的新闻正文篇幅转换为可复制的写作规划，不改变用户的硬性字数和格式。
 * 仅处理可核对的整数范围；多个范围、未支持单位或未知长度保留原文，不推断新要求。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class NewsLengthContract {
    private static final String NUMBER = "[0-9零〇一二两三四五六七八九十百千万]+";
    private static final Pattern RANGE = Pattern.compile("(?<![0-9-])(" + NUMBER + ")\\s*(?:至|到|[-–—~～])\\s*(" + NUMBER
            + ")\\s*(?:个)?\\s*(中文字符|汉字|字)");
    private static final Pattern FIXED_STRUCTURE = Pattern.compile(NUMBER + "\\s*(?:个)?(?:段|小节|章节|要点)");

    private NewsLengthContract() { }

    /** 返回兼容既有边界的组织建议；不适用时不追加任何正文。 */
    static String guidance(String rawPrompt) {
        return guidance(rawPrompt, false);
    }

    /** 充分原文只补简短的篇幅组织与核对建议，不追加第二份写作流程。 */
    static String conciseGuidance(String rawPrompt) {
        return guidance(rawPrompt, true);
    }

    private static String guidance(String rawPrompt, boolean concise) {
        String raw = Normalizer.normalize(rawPrompt == null ? "" : rawPrompt, Normalizer.Form.NFKC);
        if (TaskDeliveryProfile.identify(raw) != TaskDeliveryProfile.NEWS_RELEASE
                || !raw.contains("正文") || raw.matches("(?is).*(?:纯\\s*JSON|(?:只|仅)(?:输出|返回|提供|给出)\\s*JSON).*")) return "";
        Set<LengthRange> ranges = new LinkedHashSet<>();
        String unit = "";
        var matches = RANGE.matcher(raw);
        while (matches.find()) {
            int lower = integer(matches.group(1));
            int upper = integer(matches.group(2));
            // 只在相邻正文说明中确认计数范围，标题、附表或引文的长度不能借用。
            String before = raw.substring(Math.max(0, matches.start() - 20), matches.start());
            String after = raw.substring(matches.end(), Math.min(raw.length(), matches.end() + 14));
            int bodyPosition = before.lastIndexOf("正文");
            if (java.util.stream.Stream.of("标题", "附表", "附录", "摘要").anyMatch(scope -> before.lastIndexOf(scope) > bodyPosition)
                    || before.matches("(?s).*(?:例如|示例|假设)[^。；;\\n]*")
                    || !before.contains("正文") && !after.matches("^(?:的)?(?:新闻稿)?正文.*")) continue;
            if (lower <= 0 || upper < lower || upper > 100_000) return "";
            // “中文字符”和“字”在同一范围内均保留用户原文，不能悄悄改变计数口径。
            ranges.add(new LengthRange(lower, upper));
            if (unit.isEmpty()) unit = matches.group(3);
        }
        if (ranges.size() != 1) return "";
        LengthRange range = ranges.iterator().next();
        // 模型常把计划字数当作含标题的总长度；在原范围内留出叙述余量，不增加下限或改计数单位。
        int target = range.lower() + (range.upper() - range.lower()) * 3 / 4;
        String layout = "";
        if (range.lower() >= 400 && range.upper() <= 2000 && !FIXED_STRUCTURE.matcher(raw).find()) {
            int paragraphs = Math.max(4, Math.min(10, (target + 119) / 120));
            int perParagraph = Math.max(1, target / paragraphs);
            layout = "未指定固定结构时，先按约" + paragraphs + "个短段起草，每段约" + perParagraph
                    + unit + "。每段围绕一项资料支持的事实，展开其背景、含义或适用边界，不把全部要点压进一个总括段。"
                    + "再按内容调整段数和详略；段数和每段字数仅是组织建议，不增加新的硬性验收条目。";
            if (concise) layout = "可按约" + paragraphs + "个短段、每段约" + perParagraph + unit
                    + "组织发布信息、三项价值、受众场景、能力边界与反馈方式；每段展开资料支持的具体说明，"
                    + "不要只列一句要点。段数和段长只是组织建议，最终以原有正文总长度验收。";
        }
        if (concise) return "正文篇幅：保留" + range.lower() + "–" + range.upper() + unit
                + "及原有标题/附件计数边界，以约" + target + unit + "起草。" + layout
                + "完成后核对正文，未达到上下限时只调整资料支持的叙述，不编造或重复凑字；只交付成品，不附自报字数或检查过程。";
        return "篇幅执行（仅针对下游新闻稿）：保留用户原有正文" + range.lower() + "–" + range.upper()
                + unit + "的范围及标题/附件边界，在原范围内以约" + target + unit + "正文为起草目标。" + layout
                + "围绕已提供的发布信息、主要价值、适用对象、能力边界和反馈方式分配内容。"
                + "正文须保留已提供的当前阶段和发布性质，不能只留在提示词背景或标题中；未提供的日期、渠道或能力不补造。"
                + "完成后核对实际正文是否满足原有上下限；不足时展开资料支持的说明，不编造事实、重复凑字或用标题计入正文。"
                + "只输出成品时不附规划过程、自报字数或检查报告。";
    }

    /** 解析阿拉伯或中文整数；非法组合返回负值，不补出未经提供的长度。 */
    private static int integer(String text) {
        if (text.matches("[0-9]+")) {
            try { return Integer.parseInt(text); } catch (NumberFormatException exception) { return -1; }
        }
        int total = 0;
        int section = 0;
        int digit = 0;
        boolean hadDigit = false;
        int previousUnit = 10_000;
        for (char value : text.toCharArray()) {
            int next = switch (value) {
                case '零', '〇' -> 0;
                case '一' -> 1; case '二', '两' -> 2; case '三' -> 3; case '四' -> 4; case '五' -> 5;
                case '六' -> 6; case '七' -> 7; case '八' -> 8; case '九' -> 9; default -> -1;
            };
            if (next >= 0) {
                if (hadDigit && (digit != 0 || next == 0)) return -1;
                digit = next;
                hadDigit = true;
            } else if (value == '万') {
                if (total != 0 || section + digit <= 0) return -1;
                total += (section + digit) * 10_000;
                section = 0;
                digit = 0;
                hadDigit = false;
                previousUnit = 10_000;
            } else {
                int unit = switch (value) { case '十' -> 10; case '百' -> 100; case '千' -> 1000; default -> -1; };
                if (unit < 0 || unit >= previousUnit || hadDigit && digit == 0
                        || !hadDigit && (unit != 10 || section != 0)) return -1;
                section += (hadDigit ? digit : 1) * unit;
                previousUnit = unit;
                digit = 0;
                hadDigit = false;
            }
        }
        return total + section + digit;
    }

    private record LengthRange(int lower, int upper) { }
}
