package com.promptoptimizer.textpolish.service;

import com.promptoptimizer.textpolish.domain.TextPolishModels.Input;
import com.promptoptimizer.textpolish.domain.TextPolishModels.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 受控文章Skill装配入口；本地选择正文语言，全文用途及表达问题交由单次编辑判断。
 * 资源只来自固定类路径，显式要求原样随Input发送，并优先于资源默认指导。
 * @author QingNiao
 * @since 0.1.0
 */
public final class TextPolishSkills {
    public static final String AUTO_VERSION = "text-polish-auto-v1";
    private static final String SELECTOR_VERSION = "source-language-v1";
    private static final Pattern NON_PROSE = Pattern.compile("(?s)```.*?```|`[^`]*`|https?://\\S+|\\b[A-Za-z][A-Za-z0-9]*[_/][\\w/.-]*");
    private static final Pattern WORD = Pattern.compile("\\p{L}+(?:['’]\\p{L}+)?");
    private static final Pattern ENGLISH_WORD = Pattern.compile("[A-Za-z]+(?:['’][A-Za-z]+)?");
    private static final Set<String> ENGLISH_MARKERS = Set.of("the", "this", "that", "these", "those", "we", "our", "you", "your", "is", "are", "was", "were", "have", "has", "will", "would", "should", "must", "with", "for", "from", "and", "but", "because", "until", "unless", "of", "to", "it", "i");
    private static final Map<Language, String> IDS = Map.of(
            Language.ZH, "humanizer-zh-adapted-v1", Language.EN, "humanizer-en-adapted-v1",
            Language.MIXED, "humanizer-mixed-v1", Language.UNKNOWN, "general-v1");
    private static final Map<String, String> BODIES = loadResources();

    /**
     * 冻结最终指导和资源身份。旧策略继续经过原装配入口，不改变其字符串。
     * 自动选择仅采用正文证据，中文界面、读者和要求不参与原文语言计数。
     */
    public static PreparedTextPolish prepare(Input input, String strategyVersion) {
        if (TextPolishInstructions.PARAGRAPH_VERSION.equals(strategyVersion)) return prepareParagraph(input);
        if (!AUTO_VERSION.equals(strategyVersion)) {
            String instruction = TextPolishInstructions.system(input, strategyVersion);
            var resources = TextPolishInstructions.VERSION.equals(strategyVersion)
                    ? List.of(new Resource(strategyVersion, strategyVersion, hash(instruction))) : List.<Resource>of();
            return new PreparedTextPolish(instruction, null, resources);
        }
        Language language = detectLanguage(input.sourceText());
        String id = IDS.get(language);
        String common = BODIES.get("common-v1");
        String editing = BODIES.get(id);
        String style = switch (input.style()) {
            case FORMAL -> "正式清晰 / formal and clear. Use precise, readable written language appropriate to the purpose.";
            case NATURAL -> "自然流畅 / natural and fluent. Preserve the author's voice and use smooth, varied sentences.";
            case CONVERSATIONAL -> "轻松交流 / conversational. Explain directly to the reader with accessible words, preserving technical meaning.";
        };
        String depth = switch (input.depth()) {
            case LIGHT -> "轻度润色 / light editing. Preserve paragraph order and format. Rewrite full sentences, split long sentences and merge genuine repetition within paragraphs.";
            case RESTRUCTURE -> "允许调整句式和段落 / restructure. Reorganize sentences and paragraphs around their meaning, preserving logical relationships and all independent content.";
        };
        String instruction = common + "\n" + editing + "\nTarget style: " + style + "\nEditing depth: " + depth
                + "\nExplicit requirements in the user JSON override these defaults, including language, title, order and paragraph count.\n";
        String reason = language == Language.UNKNOWN ? "INSUFFICIENT_OR_UNSUPPORTED_LANGUAGE" : "SOURCE_LANGUAGE_EVIDENCE";
        return new PreparedTextPolish(instruction, new Selection(SELECTOR_VERSION, language, "GENERAL", reason),
                List.of(new Resource("common-v1", "common-v1", hash(common)), new Resource(id, id, hash(editing))));
    }

    /**
     * 候选按正文语言和目标文体选一份示范。混合或未知语言使用通用指导，
     * 避免单语示范改变原文语言分布。幅度只控制组织范围，完整显式要求优先。
     */
    private static PreparedTextPolish prepareParagraph(Input input) {
        String version = TextPolishInstructions.PARAGRAPH_VERSION;
        Language language = detectLanguage(input.sourceText());
        String locale = switch (language) {
            case ZH -> "zh";
            case EN -> "en";
            case MIXED, UNKNOWN -> null;
        };
        String style = switch (input.style()) {
            case FORMAL -> "FORMAL. Lead with the relevant finding, action or condition when the source contains one. Use precise written language.";
            case NATURAL -> "NATURAL. Follow the author's perspective and existing observations. Let sentence length and transitions follow the meaning.";
            case CONVERSATIONAL -> "CONVERSATIONAL. Explain directly in accessible language. Keep necessary terms and the author's relationship to the reader.";
        };
        String depth = switch (input.depth()) {
            case LIGHT -> "LIGHT. Keep paragraph order and format. Within each paragraph, rewrite whole sentences, split long sentences or merge genuine repetition.";
            case RESTRUCTURE -> "RESTRUCTURE. Split or combine paragraphs and reorder independent information where helpful. Preserve chronology, causal direction and the action governed by each condition.";
        };
        String core = BODIES.get(version);
        List<Resource> resources = new ArrayList<>();
        resources.add(new Resource(version, version, hash(core)));
        StringBuilder instruction = new StringBuilder(core);
        if (locale != null) {
            String id = paragraphExampleId(locale, input.style());
            String example = BODIES.get(id);
            instruction.append("\n").append(example);
            resources.add(new Resource(id, version, hash(example)));
        } else if (language == Language.MIXED) {
            instruction.append("\nMixed-language source: preserve the language of each passage and meaningful language switches by default.\n");
        }
        instruction.append("\nTarget style: ").append(style).append("\nEditing depth: ").append(depth)
                .append("\nExplicit requirements in the user JSON override style, depth and language defaults within the meaning and quotation safeguards.\n");
        String reason = switch (language) {
            case ZH, EN -> "SOURCE_LANGUAGE_EVIDENCE";
            case MIXED -> "MIXED_LANGUAGE_GENERAL_GUIDANCE";
            case UNKNOWN -> "INSUFFICIENT_OR_UNSUPPORTED_LANGUAGE";
        };
        return new PreparedTextPolish(instruction.toString(),
                new Selection(SELECTOR_VERSION, language, "PARAGRAPH_EDITING", reason), resources);
    }

    /** 资源编号只由固定语言和文体组合生成，用户文本不参与路径拼接。 */
    private static String paragraphExampleId(String locale, Style style) {
        return TextPolishInstructions.PARAGRAPH_VERSION + "-" + locale + "-" + style.name().toLowerCase(Locale.ROOT);
    }

    /**
     * 使用文字与英语功能词的共同证据，拉丁字母本身不足以判英语。
     * 代码/URL仅从分类副本剔除；完整原文始终由Provider发送。阈值是有限启发式，未知语言回退通用资源。
     */
    private static Language detectLanguage(String source) {
        String prose = NON_PROSE.matcher(source).replaceAll(" ");
        long han = prose.codePoints().filter(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN).count();
        long letters = prose.codePoints().filter(Character::isLetter).count();
        long unsupported = prose.codePoints().filter(Character::isLetter).filter(cp -> {
            var script = Character.UnicodeScript.of(cp);
            return script != Character.UnicodeScript.HAN && script != Character.UnicodeScript.LATIN;
        }).count();
        if (letters == 0 || unsupported * 10 > letters) return Language.UNKNOWN;
        var matcher = WORD.matcher(prose);
        int words = 0;
        Set<String> markers = new HashSet<>();
        while (matcher.find()) {
            String word = matcher.group();
            if (!ENGLISH_WORD.matcher(word).matches() || word.equals(word.toUpperCase(Locale.ROOT))) continue;
            words++;
            String lower = word.toLowerCase(Locale.ROOT);
            if (ENGLISH_MARKERS.contains(lower)) markers.add(lower);
        }
        boolean english = words >= 6 && markers.size() >= 3;
        // 汉字与英文单词按阅读单位比较，避免几个英文产品名把中文正文挤到混合分支。
        if (han >= 8 && english && words >= 8 && words >= han / 5.0 && han >= words / 5.0) return Language.MIXED;
        if (han >= 4 && han >= letters * 0.35) return Language.ZH;
        if (english && han < 8) return Language.EN;
        return Language.UNKNOWN;
    }

    /** 一次加载固定白名单；所有分支在首次装配或配置校验时一起检查，损坏即失败。 */
    private static Map<String, String> loadResources() {
        Map<String, String> bodies = new HashMap<>();
        Set<String> ids = new HashSet<>(IDS.values());
        ids.add("common-v1");
        ids.add(TextPolishInstructions.PARAGRAPH_VERSION);
        for (String locale : List.of("zh", "en")) {
            for (Style style : Style.values()) ids.add(paragraphExampleId(locale, style));
        }
        for (String id : ids) bodies.put(id, load(id));
        return Map.copyOf(bodies);
    }

    /** 规范化换行且保留正文尾换行，YAML仅作受控包装，不进入模型消息。 */
    private static String load(String id) {
        String path = "/textpolish/skills/" + id + "/SKILL.md";
        try (var stream = TextPolishSkills.class.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("缺少文章Skill资源：" + id);
            String content = new String(stream.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
            int end = content.indexOf("\n---\n", 4);
            if (!content.startsWith("---\nname: " + id + "\n") || end < 0 || content.substring(end + 5).isBlank())
                throw new IllegalStateException("文章Skill资源格式无效：" + id);
            return content.substring(end + 5);
        } catch (IOException failure) {
            throw new IllegalStateException("读取文章Skill失败：" + id, failure);
        }
    }

    /** 哈希只用于资源身份，不把资源正文或文章写入技术日志。 */
    private static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("缺少SHA-256算法", failure);
        }
    }

    private TextPolishSkills() { }
}
