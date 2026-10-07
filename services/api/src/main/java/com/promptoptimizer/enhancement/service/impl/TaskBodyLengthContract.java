package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.template.domain.TaskDeliveryProfile;
import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.regex.Pattern;

/**
 * 为非新闻作品的明确正文长度提供简短规划，保持用户的计数单位、固定结构和附表范围。
 * 只处理单一阿拉伯整数范围；多范围、假设、未知或纯JSON保持原要求，不猜测验收口径。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class TaskBodyLengthContract {
    private static final Pattern RANGE = Pattern.compile("(?<![0-9])([0-9]{1,6})\\s*(?:至|到|[-–—~～])\\s*([0-9]{1,6})\\s*(中文字符|汉字|字)");

    private TaskBodyLengthContract() { }

    /** 组织建议不替代原字数验收，也不强制额外章节、说明或计数报告。 */
    static String guidance(String rawPrompt) {
        String raw = Normalizer.normalize(rawPrompt == null ? "" : rawPrompt, Normalizer.Form.NFKC);
        TaskDeliveryProfile profile = TaskDeliveryProfile.identify(raw);
        // 译文中的长度描述是源内容；软件交付和新闻分别沿用其自身契约，不能被通用正文规划覆盖。
        if (profile == TaskDeliveryProfile.TRANSLATION || profile == TaskDeliveryProfile.SOFTWARE_IMPLEMENTATION
                || profile == TaskDeliveryProfile.NEWS_RELEASE) return "";
        if (raw.matches("(?is).*(?:纯\\s*JSON|(?:只|仅)(?:输出|返回|提供|给出)\\s*JSON).*")) return "";
        var ranges = new LinkedHashSet<BodyRange>();
        for (String clause : raw.split("[。；;\\r\\n]+")) {
            var matches = RANGE.matcher(clause);
            while (matches.find()) {
                String before = clause.substring(0, matches.start());
                String after = clause.substring(matches.end());
                int body = before.lastIndexOf("正文");
                if (java.util.stream.Stream.of("标题", "附表", "附录", "摘要").anyMatch(scope -> before.lastIndexOf(scope) > body)
                        || body < 0 && !after.matches("^(?:的)?正文.*")) continue;
                if (before.matches("(?s).*(?:不要|不必|无需|不得|不需要|不要求)(?:输出|写|撰写|生成|提供|交付|要求)?正文.*")) return "";
                if (before.matches("(?s).*(?:若|如果|假如|假设|例如|示例).*")) return "";
                int lower = Integer.parseInt(matches.group(1)), upper = Integer.parseInt(matches.group(2));
                if (lower <= 0 || upper < lower || upper > 100_000) return "";
                ranges.add(new BodyRange(lower, upper, matches.group(3)));
            }
        }
        if (ranges.size() != 1) return "";
        BodyRange range = ranges.iterator().next();
        int target = range.lower() + (range.upper() - range.lower()) / 2;
        String layout = "";
        // 混合表格和伪代码任务先分配共同预算，不按全文目标另写足量叙述，避免组织建议扩大成品。
        if (range.lower() >= 600 && range.upper() <= 5000
                && !Pattern.compile("指标表|规则表|伪代码").matcher(raw).find()
                && !Pattern.compile("[0-9零〇一二两三四五六七八九十百]+\\s*(?:个)?(?:段|小节|章节|要点)").matcher(raw).find()) {
            int paragraphs = Math.max(4, Math.min(10, (target + 249) / 250));
            layout = "未指定固定结构时，可按约" + paragraphs + "个自然段、每段约" + target / paragraphs + range.unit()
                    + "分配叙述，再按应计入正文的表格和伪代码缩减文字部分；这是组织建议，不新增固定段数验收。";
        }
        return "正文篇幅：保留" + range.lower() + "–" + range.upper() + range.unit() + "，在范围内以约" + target
                + range.unit() + "起草，沿用用户原定标题、附表和代码的计数边界。只有用户明确另计的部分才移出该预算，不能写满正文后再附应计入的长表格或代码。"
                + layout + "按既定内容分配篇幅，避免在任务、规则和未决清单间重复说明。"
                + "完成后核对实际正文，超长时合并同一完整说明，过短时只展开资料支持的信息，不编造或重复凑字；"
                + "表格、必要步骤、条件和未决参数不能因缩减篇幅漏交。用户要求只输出成品时，不附自报字数或检查过程。";
    }

    private record BodyRange(int lower, int upper, String unit) { }
}
