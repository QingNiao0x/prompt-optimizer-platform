package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.context.domain.FileSnippet;
import com.promptoptimizer.enhancement.domain.PlanningFactCategory;
import com.promptoptimizer.enhancement.domain.PlanningFactOrigin;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 事实提取和结果保留共用的证据用途、片段相关性策略。只筛选本次已接收的材料，不读取文件。
 * 路径分类是保守的用途标记，不证明材料真实；测试和示例可用于对应任务，不能充当生产事实。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class PlanningEvidencePolicy {
    private static final Pattern WORD = Pattern.compile("[a-zA-Z0-9_]{3,}|[\\p{IsHan}]{2,}");
    private static final Pattern TEST_PATH = Pattern.compile(
            "(?:^|/)(?:test|tests|__tests__|e2e)(?:/|$)|[.](?:test|spec)[.][cm]?[jt]sx?$");
    private static final Pattern JAVA_TEST_FILE = Pattern.compile("(?:Test|Tests|Spec|IT)\\.java$");
    private static final Pattern FIXTURE_PATH = Pattern.compile(
            "(?:^|/)(?:test-fixtures|fixtures|__fixtures__|__mocks__)(?:/|$)");
    private static final Pattern EXAMPLE_PATH = Pattern.compile("(?:^|/)(?:examples?|samples?|demo)(?:/|$)");
    private static final Pattern REPORT_PATH = Pattern.compile(
            "(?:^|/)(?:playwright-report|test-results|surefire-reports|failsafe-reports|coverage)(?:/|$)");
    private static final Pattern SOURCE_PATH = Pattern.compile(
            ".*\\.(?:java|[cm]?[jt]sx?|vue|py|go|rs|cs|cpp|c|h|sql|xml|json|ya?ml|properties|gradle|kt)$");
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$");
    private static final Pattern EXAMPLE_LABEL = Pattern.compile("(?i)(示例|样例|举例|example|sample)");
    private static final Pattern BUILD_WARNING = Pattern.compile(
            "(?i)(非阻断警告|构建仍.*提示|(?:构建|打包|分包|bundle|chunk).*(?:警告|warning)|chunks? are larger)");
    private static final Set<String> GENERIC_TERMS = Set.of(
            "开发", "实现", "分析", "生成", "处理", "添加", "项目", "方案", "文件", "内容", "用户", "接口", "需求",
            "设计", "系统", "功能", "需要", "要求", "输出", "输入", "数据", "使用", "支持", "优化", "完善",
            "当前", "已有", "进行", "相关", "包括", "任务", "提供", "根据", "模块", "测试", "代码",
            "the", "and", "for", "with", "from", "this", "file", "project", "test", "tests");

    private final String query;
    private final Set<String> terms;
    private final boolean testTask;
    private final boolean reportTask;

    PlanningEvidencePolicy(String query) {
        this.query = normalize(query);
        terms = meaningfulTerms(this.query);
        // 提交物中泛称“附测试方案”不应令全仓库测试夹具进入业务事实。
        testTask = Pattern.compile("(?i)(修复|排查|补充|编写|完善|重构|验证|分析).{0,24}(测试|用例|断言|夹具)"
                + "|测试.{0,12}(失败|修复|覆盖)|\\b(test|tests|testing|fixture|assertion)\\b").matcher(this.query).find();
        reportTask = Pattern.compile("(?i)(构建|打包|分包|包体|覆盖率|测试报告|前端性能|bundle|chunk|build|coverage)")
                .matcher(this.query).find();
    }

    /** 先识别报告和夹具，再识别测试源码，避免它们因扩展名被标为正式实现。 */
    static PlanningFactOrigin origin(String path, String language) {
        String normalized = normalize(path).replace('\\', '/');
        if (REPORT_PATH.matcher(normalized).find()) return PlanningFactOrigin.GENERATED_REPORT;
        if (FIXTURE_PATH.matcher(normalized).find()) return PlanningFactOrigin.TEST_FIXTURE;
        if (TEST_PATH.matcher(normalized).find() || path != null && JAVA_TEST_FILE.matcher(path).find()) {
            return PlanningFactOrigin.TEST_SOURCE;
        }
        if (EXAMPLE_PATH.matcher(normalized).find()) return PlanningFactOrigin.EXAMPLE_MATERIAL;
        if (normalized.endsWith(".md") || normalized.endsWith(".rst") || normalized.endsWith(".adoc")) {
            return PlanningFactOrigin.PROJECT_DOCUMENT;
        }
        if (SOURCE_PATH.matcher(normalized).matches()) return PlanningFactOrigin.PROJECT_SOURCE;
        if (PlanningDigestSelector.isDocument(new FileSnippet(path, language, "", "", false))) {
            return PlanningFactOrigin.USER_MATERIAL;
        }
        return PlanningFactOrigin.UNKNOWN;
    }

    /** 只影响证据候选，不从上传列表、本地索引或分析报告删除任何文件。 */
    boolean allows(FileSnippet file) {
        if (file == null || file.path() == null || file.path().isBlank()) return false;
        return switch (origin(file.path(), file.language())) {
            case TEST_SOURCE, TEST_FIXTURE -> testTask || explicitlyNamed(file.path());
            case EXAMPLE_MATERIAL -> explicitlyNamed(file.path()) || query.contains("示例") || query.contains("样例");
            case GENERATED_REPORT -> reportTask || explicitlyNamed(file.path());
            default -> true;
        };
    }

    /** 元数据短句可继承相关文档主题；业务规则须有自身主题或文件标题证据，不按“统计”类别直接放行。 */
    boolean relevant(FileSnippet file, String evidence, PlanningFactCategory category) {
        if (!allows(file) || evidence == null || evidence.isBlank()) return false;
        if (!reportTask && BUILD_WARNING.matcher(evidence).find()) return false;
        if (EXAMPLE_LABEL.matcher(evidence).lookingAt()) return false;
        if (overlaps(evidence) || explicitlyNamed(file.path())) return true;
        String name = fileName(file.path());
        if (overlaps(name)) return true;
        return metadataApplies(category) && PlanningDigestSelector.isDocument(file)
                && overlaps(file.summary());
    }

    /** 混合文档的摘要可能同时提到订单与研究，不能让研究元数据仅凭摘要中的订单词通过。 */
    private boolean metadataApplies(PlanningFactCategory category) {
        return switch (category) {
            case BUSINESS_RULE -> false;
            case REGION, DISEASE_CATEGORY, POPULATION, ANALYSIS_METHOD ->
                    Pattern.compile("研究|论文|死亡率|发病率|疾病|人群|地区|区域|yll|arriaga|方法")
                            .matcher(query).find();
            default -> true;
        };
    }

    /** Markdown 的示例小节不升级为事实；保留其他正文和非示例代码块内的资料。 */
    List<String> evidenceLines(FileSnippet file) {
        return contextText(file).lines()
                .flatMap(line -> java.util.Arrays.stream(line.split("[。；;]+")))
                .map(String::trim).filter(value -> !value.isBlank())
                .filter(value -> !EXAMPLE_LABEL.matcher(value).lookingAt()).toList();
    }

    /** 计划摘录保留源码原文，只在 Markdown 内跳过明确示例小节，避免改坏代码标点。 */
    String contextText(FileSnippet file) {
        if (file.content() == null) return "";
        List<String> result = new ArrayList<>();
        boolean markdown = file.path() != null && file.path().toLowerCase(Locale.ROOT).endsWith(".md");
        if (!markdown) return file.content();
        int exampleDepth = 0;
        boolean fenced = false;
        for (String line : file.content().lines().toList()) {
            String stripped = line.strip();
            if (markdown && (stripped.startsWith("```") || stripped.startsWith("~~~"))) {
                fenced = !fenced;
                continue;
            }
            var heading = HEADING.matcher(stripped);
            if (markdown && !fenced && heading.matches()) {
                int depth = heading.group(1).length();
                if (exampleDepth > 0 && depth <= exampleDepth) exampleDepth = 0;
                if (EXAMPLE_LABEL.matcher(heading.group(2)).find()) exampleDepth = depth;
                continue;
            }
            if (exampleDepth > 0 || EXAMPLE_LABEL.matcher(stripped).lookingAt()) continue;
            result.add(line);
        }
        return String.join("\n", result);
    }

    /** 精确文件名引用比泛化目录名可靠；不将普通的 docs/src 当成任务对象。 */
    private boolean explicitlyNamed(String path) {
        String name = fileName(path);
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        return name.length() > 4 && (query.contains(name)
                || stem.length() > 8 && query.contains(stem));
    }

    private boolean overlaps(String text) {
        String normalized = normalize(text);
        return terms.stream().anyMatch(normalized::contains);
    }

    /** 每次查询仅拆词一次；不建立跨请求缓存或保存用户正文。 */
    private Set<String> meaningfulTerms(String text) {
        Set<String> result = new LinkedHashSet<>();
        var words = WORD.matcher(text);
        while (words.find()) {
            String word = words.group();
            if (word.matches("[\\p{IsHan}]+")) {
                for (int index = 0; index + 2 <= word.length(); index++) {
                    String term = word.substring(index, index + 2);
                    if (!GENERIC_TERMS.contains(term)) result.add(term);
                }
            } else if (!GENERIC_TERMS.contains(word)) result.add(word);
        }
        return Set.copyOf(result);
    }

    private static String fileName(String path) {
        String normalized = normalize(path).replace('\\', '/');
        return normalized.substring(normalized.lastIndexOf('/') + 1);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }
}
