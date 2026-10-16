package com.promptoptimizer.common.security;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 对文本和常见结构化语境进行本地凭据检查；只返回类型与原文位置，不保存敏感值。
 * 编码仅解开一层 ASCII 转义，避免无界解码和把普通变量名当成凭据。
 */
public final class SensitiveValueScanner {
    private static final Pattern ASSIGNMENT = Pattern.compile(
            "(?i)\\b(api[ _-]?(?:key|token)|access[ _-]?(?:token|key)|refresh[ _-]?token|client[ _-]?secret|secret[ _-]?key|password|secret|token)\\b[\"']?\\s*[:=]\\s*"
                    + "(?:\"([^\"\\r\\n]*)\"|'([^'\\r\\n]*)'|(\\$\\{[^}\\r\\n]{1,100}}|<[A-Z_][A-Z0-9_]*>|[^\\s\"',;}&<>]+))");
    private static final Pattern AUTH = Pattern.compile("(?i)\\b(?:bearer|basic)\\s+([^\\s,;\"']{4,})");
    private static final Pattern USERINFO = Pattern.compile("(?i)https?://([^\\s/@]+)@");
    private static final Pattern KEY = Pattern.compile("\\b(sk-[A-Za-z0-9_-]{10,})\\b");
    private static final Pattern PRIVATE_KEY = Pattern.compile(
            "-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?(?:-----END [A-Z ]*PRIVATE KEY-----|$)");
    private static final Pattern ESCAPE = Pattern.compile("%([0-7][0-9A-Fa-f])|\\\\u00([0-7][0-9A-Fa-f])");
    private static final Pattern PLACEHOLDER = Pattern.compile(
            "(?i)(?:\\$\\{[A-Z_][A-Z0-9_]*}|<[A-Z_][A-Z0-9_]*>|\\{\\{[A-Z_][A-Z0-9_]*}}|\\[?REDACTED]?"
                    + "|your[-_](?:api[-_]?key|access[-_]?token|token|password|secret)(?:[-_]placeholder)?"
                    + "|test[-_]placeholder|x{4,})");
    private static final Pattern ENV_REFERENCE = Pattern.compile(
            "(?:System\\.getenv|os\\.getenv|os\\.environ\\.get|getenv)\\(.*|process\\.env\\.[A-Z_][A-Z0-9_]*");

    /** 检查器与上下文过滤器共用同一判据，不把示例词子串当作整值豁免。 */
    public boolean containsCredential(String text) {
        return !findings(text).isEmpty();
    }

    /** 返回原文跨度；所有调用方只获得类型和位置，日志不得记录原文切片。 */
    public List<Finding> findings(String text) {
        if (text == null || text.isBlank()) return List.of();
        var view = decodedView(text);
        List<Finding> findings = new ArrayList<>();
        var assignment = ASSIGNMENT.matcher(view.text());
        while (assignment.find()) {
            int group = assignment.group(2) != null ? 2 : assignment.group(3) != null ? 3 : 4;
            String candidate = assignment.group(group);
            boolean structured = group != 4 || assignment.start(1) > 0
                    && "?&".indexOf(view.text().charAt(assignment.start(1) - 1)) >= 0;
            int minimum = structured || assignment.group(1).equalsIgnoreCase("password") ? 4 : 8;
            if (candidate.length() >= minimum && !placeholder(candidate) && !ENV_REFERENCE.matcher(candidate).matches()) {
                findings.add(view.finding(assignment.start(group), assignment.end(group), "ASSIGNMENT"));
            }
        }
        collect(AUTH, view, "AUTHORIZATION", findings);
        collect(USERINFO, view, "URL_USERINFO", findings);
        collect(KEY, view, "API_KEY", findings);
        var privateKey = PRIVATE_KEY.matcher(view.text());
        while (privateKey.find()) findings.add(view.finding(privateKey.start(), privateKey.end(), "PRIVATE_KEY"));
        return findings.stream().distinct().sorted(Comparator.comparingInt(Finding::start)).toList();
    }

    /** 只替换已识别的值，保留其余原文和编码；不是任意程序的数据流修复器。 */
    public String redact(String text) {
        if (text == null) return null;
        List<Finding> merged = new ArrayList<>();
        for (Finding finding : findings(text)) {
            if (!merged.isEmpty() && finding.start() <= merged.getLast().end()) {
                var previous = merged.removeLast();
                merged.add(new Finding(previous.start(), Math.max(previous.end(), finding.end()), previous.kind()));
            } else merged.add(finding);
        }
        StringBuilder result = new StringBuilder(text);
        for (int i = merged.size() - 1; i >= 0; i--) {
            var finding = merged.get(i);
            result.replace(finding.start(), finding.end(), "REDACTED");
        }
        return result.toString();
    }

    /** 对完整匹配做占位判断；包含 example/test_ 的其他值仍按凭据处理。 */
    private boolean placeholder(String candidate) {
        return PLACEHOLDER.matcher(candidate).matches();
    }

    /** 收集带明确认证或 URL 语境的候选，不扫描任意 Base64 内容。 */
    private void collect(Pattern pattern, View view, String kind, List<Finding> findings) {
        var matcher = pattern.matcher(view.text());
        while (matcher.find()) {
            if (kind.equals("AUTHORIZATION") && matcher.group(1).length() < 12
                    && !view.text().substring(Math.max(0, matcher.start() - 24), matcher.start())
                    .matches("(?is).*Authorization\\s*:\\s*$")) continue;
            if (!placeholder(matcher.group(1))) findings.add(view.finding(matcher.start(1), matcher.end(1), kind));
        }
    }

    /** 单次解码并保存位置映射，使检测与替换使用相同原文跨度。 */
    private View decodedView(String text) {
        var matcher = ESCAPE.matcher(text);
        if (!matcher.find()) return new View(text, null);
        StringBuilder decoded = new StringBuilder(text.length());
        int[] offsets = new int[text.length() + 1];
        int cursor = 0;
        do {
            while (cursor < matcher.start()) {
                offsets[decoded.length()] = cursor;
                decoded.append(text.charAt(cursor++));
            }
            offsets[decoded.length()] = matcher.start();
            decoded.append((char) Integer.parseInt(matcher.group(1) != null ? matcher.group(1) : matcher.group(2), 16));
            cursor = matcher.end();
        } while (matcher.find());
        while (cursor < text.length()) {
            offsets[decoded.length()] = cursor;
            decoded.append(text.charAt(cursor++));
        }
        offsets[decoded.length()] = text.length();
        return new View(decoded.toString(), offsets);
    }

    /** 安全枚举类型及 UTF-16 原文位置，不携带实际值。 */
    public record Finding(int start, int end, String kind) { }

    /** 解码视图只在本次调用中存活，不写入上下文、历史或日志。 */
    private record View(String text, int[] offsets) {
        private Finding finding(int start, int end, String kind) {
            return new Finding(offsets == null ? start : offsets[start], offsets == null ? end : offsets[end], kind);
        }
    }
}
