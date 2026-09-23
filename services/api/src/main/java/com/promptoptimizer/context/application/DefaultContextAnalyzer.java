package com.promptoptimizer.context.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.context.api.ContextAnalysisRequest;
import com.promptoptimizer.context.api.ContextFileInput;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.context.domain.DependencyItem;
import com.promptoptimizer.context.domain.DocumentSelection;
import com.promptoptimizer.context.domain.FileAnalysisCoverage;
import com.promptoptimizer.context.domain.FileSnippet;
import com.promptoptimizer.context.domain.TechnologyStackItem;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 默认上下文分析器：识别技术栈、依赖、目录结构和文件片段，并对敏感内容脱敏。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Service
public class DefaultContextAnalyzer implements ContextAnalyzer {

    private static final String ANALYSIS_VERSION = "v3";
    private static final int MAX_TOTAL_BYTES = 64 * 1024 * 1024;
    private static final int MAX_FILE_BYTES = 5 * 1024 * 1024;
    private static final int MAX_BINARY_FILE_BYTES = 25 * 1024 * 1024;
    private static final int MAX_DIRECTORY_ENTRIES = 100;
    private static final int MAX_SNIPPETS = 40;
    private static final int MAX_SNIPPET_CHARS = 60_000;
    private static final int MAX_SNIPPET_TOTAL_CHARS = 120_000;
    private static final int MAX_CHUNKS_PER_FILE = 10;

    private static final Set<String> IGNORED_SEGMENTS = Set.of(
            ".git", "node_modules", "target", "build", "dist", "out", ".idea", ".vscode",
            "logs", "log", "coverage", "__pycache__", ".venv", "venv", "vendor",
            ".cache", ".parcel-cache", ".turbo", "tmp", "temp"
    );
    private static final Set<String> SENSITIVE_FILE_NAMES = Set.of(
            ".env", ".env.local", ".env.development", ".env.production",
            "id_rsa", "id_ed25519", "credentials", "credentials.json"
    );

    private static final Pattern WINDOWS_ABSOLUTE_PATH = Pattern.compile("^[A-Za-z]:[\\\\/].*");
    private static final Pattern UNIX_ABSOLUTE_PATH = Pattern.compile("^/.*");
    private static final Pattern TRAVERSAL_PATH = Pattern.compile("(^|/)\\.\\.($|/)");
    private static final Pattern POM_DEPENDENCY = Pattern.compile(
            "<dependency>\\s*.*?<groupId>\\s*([^<]+)\\s*</groupId>\\s*<artifactId>\\s*([^<]+)\\s*</artifactId>(?:\\s*<version>\\s*([^<]+)\\s*</version>)?.*?</dependency>",
            Pattern.DOTALL
    );
    private static final Pattern REQUIREMENT = Pattern.compile(
            "^([A-Za-z0-9_.-]+)\\s*(?:[=<>!~]+\\s*([^;\\s]+))?.*$"
    );
    private static final Pattern GO_MODULE = Pattern.compile(
            "^\\s*([A-Za-z0-9_./-]+)\\s+v([A-Za-z0-9.+-]+)\\s*$"
    );
    private static final Pattern SECRET_VALUE = Pattern.compile(
            "(?i)(api[_-]?key|secret|password|token)\\s*[:=]\\s*[\\\"']?([A-Za-z0-9_./+=-]{8,})"
    );
    private static final Pattern PRIVATE_KEY = Pattern.compile("-----BEGIN .* PRIVATE KEY-----");
    private static final Pattern OPENAI_STYLE_KEY = Pattern.compile("sk-[A-Za-z0-9_-]{10,}");
    private static final Pattern INDEX_CHUNK_SUFFIX = Pattern.compile("#chunk-\\d+$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PRODUCTION_CONFIG_PATH = Pattern.compile(
            "(?i)(?:^|/)(?:application[-.](?:prod|production)|config/(?:prod|production))"
                    + "\\.(?:yml|yaml|properties|json|toml)$"
    );

    private final ObjectMapper objectMapper;
    private final BinaryContentExtractor binaryContentExtractor;
    private final FileContentSummarizer fileContentSummarizer;
    private final ContentChunkSelector contentChunkSelector;
    private final DocumentIndexLookup documentIndexLookup;

    @Autowired
    public DefaultContextAnalyzer(
            ObjectMapper objectMapper,
            BinaryContentExtractor binaryContentExtractor,
            FileContentSummarizer fileContentSummarizer,
            DocumentIndexLookup documentIndexLookup
    ) {
        this.objectMapper = objectMapper;
        this.binaryContentExtractor = binaryContentExtractor;
        this.fileContentSummarizer = fileContentSummarizer;
        this.contentChunkSelector = new ContentChunkSelector();
        this.documentIndexLookup = documentIndexLookup;
    }

    public DefaultContextAnalyzer(
            ObjectMapper objectMapper,
            BinaryContentExtractor binaryContentExtractor,
            FileContentSummarizer fileContentSummarizer
    ) {
        this(objectMapper, binaryContentExtractor, fileContentSummarizer, DocumentIndexLookup.empty());
    }

    /**
     * 分析请求中的项目描述和文件内容，返回本次请求可用的上下文快照。
     */
    @Override
    public ContextSnapshot analyze(ContextAnalysisRequest request) {
        return analyze(request, "");
    }

    /**
     * 分析文件并根据当前任务从长内容中选择相关片段；全文摘要仍覆盖文件首、中、尾。
     */
    @Override
    public ContextSnapshot analyze(ContextAnalysisRequest request, String query) {
        List<String> warnings = new ArrayList<>();
        List<String> redactions = new ArrayList<>();
        List<FileAnalysisCoverage> fileCoverage = new ArrayList<>();
        Map<String, AnalyzedFile> files = collectFiles(request, query, warnings, redactions, fileCoverage);
        Map<String, TechnologyStackItem> stack = new LinkedHashMap<>();
        List<DependencyItem> dependencies = new ArrayList<>();

        for (AnalyzedFile file : files.values()) {
            detectTechnology(file, stack);
            extractDependencies(file, dependencies, warnings);
        }

        return new ContextSnapshot(
                sanitizeDescription(request.customDescription(), warnings, redactions),
                new ArrayList<>(stack.values()),
                deduplicateDependencies(dependencies),
                buildDirectoryTree(files.keySet(), warnings),
                buildSnippets(files.values(), warnings, query),
                resolveAnalysisStatus(request, files, fileCoverage),
                fileCoverage,
                warnings,
                redactions,
                ANALYSIS_VERSION
        );
    }

    /**
     * 收集并校验用户提交的文件，按大小、路径和敏感内容规则过滤。
     */
    private Map<String, AnalyzedFile> collectFiles(
            ContextAnalysisRequest request,
            String query,
            List<String> warnings,
            List<String> redactions,
            List<FileAnalysisCoverage> fileCoverage
    ) {
        Map<String, AnalyzedFile> files = new LinkedHashMap<>();
        Set<String> seenPaths = new HashSet<>();
        int totalBytes = 0;

        for (ContextFileInput input : request.files()) {
            String path = normalizePath(input.path());
            if (path == null) {
                warnings.add("已忽略不安全或无效路径：" + input.path());
                continue;
            }
            if (isIgnoredPath(path)) {
                warnings.add("已忽略生成目录或工具目录：" + path);
                continue;
            }
            if (isProtectedPath(path)) {
                String sourcePath = sourcePathOf(path);
                warnings.add("已忽略受保护文件：" + sourcePath);
                redactions.add(sourcePath);
                continue;
            }
            if (!seenPaths.add(path)) {
                warnings.add("已忽略重复文件：" + path);
                continue;
            }

            String language = languageOf(path, input.language());
            if (input.documentId() != null && !input.documentId().isBlank()) {
                collectIndexedDocument(
                        input.documentId(),
                        path,
                        query,
                        files,
                        warnings,
                        redactions,
                        fileCoverage
                );
                continue;
            }
            String content = input.content() == null ? "" : input.content();
            if (binaryContentExtractor.supports(language)) {
                totalBytes = collectBinaryFile(path, language, content, files, warnings, redactions, totalBytes);
                continue;
            }

            int bytes = content.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > MAX_FILE_BYTES) {
                content = truncateByBytes(content, MAX_FILE_BYTES);
                bytes = content.getBytes(StandardCharsets.UTF_8).length;
                warnings.add("文件内容已截断：" + path);
            }
            if (totalBytes + bytes > MAX_TOTAL_BYTES) {
                warnings.add("已达到上下文总大小上限，后续文件未分析：" + path);
                break;
            }
            totalBytes += bytes;

            if (containsBinaryMarker(content)) {
                warnings.add("已跳过疑似二进制文件：" + path);
                continue;
            }

            boolean sensitive = containsSecret(content);
            if (sensitive) {
                redactions.add(path);
            }
            files.put(path, new AnalyzedFile(
                    path,
                    sensitive ? "[CONTENT REDACTED: sensitive material detected]" : content,
                    language,
                    bytes >= MAX_FILE_BYTES,
                    ""
            ));
        }
        return files;
    }

    /**
     * 解码并解析 Office、PDF、WPS/OpenDocument 或图片文件，把提取文本计入上下文总预算。
     */
    private int collectBinaryFile(
            String path,
            String language,
            String base64Content,
            Map<String, AnalyzedFile> files,
            List<String> warnings,
            List<String> redactions,
            int totalBytes
    ) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64Content.trim());
        } catch (IllegalArgumentException exception) {
            warnings.add("无法解析文件编码：" + path);
            return totalBytes;
        }
        if (decoded.length > MAX_BINARY_FILE_BYTES) {
            warnings.add("已忽略超过 25 MB 的兼容上传文件，请使用分片上传：" + path);
            return totalBytes;
        }
        if (totalBytes + decoded.length > MAX_TOTAL_BYTES) {
            warnings.add("已达到上下文总大小上限，后续文件未分析：" + path);
            return totalBytes;
        }
        try {
            BinaryContentExtractor.ExtractedText extracted = binaryContentExtractor.extract(path, language, decoded);
            if (Set.of("jpeg", "png", "gif", "webp", "bmp").contains(language)) {
                warnings.add("图片当前仅提取元数据，尚未进行 OCR 或视觉识别：" + path);
            }
            boolean sensitive = containsSecret(extracted.content());
            if (sensitive) {
                redactions.add(path);
            }
            files.put(path, new AnalyzedFile(
                    path,
                    sensitive ? "[CONTENT REDACTED: sensitive material detected]" : extracted.content(),
                    language,
                    extracted.truncated(),
                    ""
            ));
            return totalBytes + decoded.length;
        } catch (IllegalArgumentException exception) {
            warnings.add("无法解析文件：" + path + "（" + exception.getMessage() + "）");
            return totalBytes;
        }
    }

    /**
     * 读取已完成分片解析的临时文档索引。索引不存在或尚未就绪时返回明确警告。
     */
    private void collectIndexedDocument(
            String documentId,
            String requestedPath,
            String query,
            Map<String, AnalyzedFile> files,
            List<String> warnings,
            List<String> redactions,
            List<FileAnalysisCoverage> fileCoverage
    ) {
        DocumentSelection selection = documentIndexLookup.retrieve(
                documentId,
                query,
                MAX_SNIPPET_CHARS,
                MAX_CHUNKS_PER_FILE
        ).orElse(null);
        if (selection == null) {
            warnings.add("文档索引不存在、尚未完成或已经过期：" + requestedPath);
            fileCoverage.add(new FileAnalysisCoverage(
                    requestedPath,
                    "FAILED",
                    0,
                    0,
                    0,
                    0,
                    0,
                    false,
                    "请重新上传并等待文档解析完成"
            ));
            return;
        }

        String indexedPath = normalizePath(selection.path());
        if (indexedPath == null || !indexedPath.equals(requestedPath)) {
            warnings.add("文档索引与请求路径不匹配：" + requestedPath);
            fileCoverage.add(new FileAnalysisCoverage(
                    requestedPath,
                    "FAILED",
                    selection.sourceBytes(),
                    selection.extractedCharacters(),
                    selection.totalChunks(),
                    0,
                    0,
                    false,
                    "文档引用校验失败"
            ));
            return;
        }
        selection.warnings().forEach(message -> warnings.add(requestedPath + "：" + message));
        boolean sensitive = containsSecret(selection.content()) || containsSecret(selection.summary());
        if (sensitive) {
            redactions.add(requestedPath);
        }
        String content = sensitive
                ? "[CONTENT REDACTED: sensitive material detected]"
                : selection.content();
        files.put(requestedPath, new AnalyzedFile(
                requestedPath,
                content,
                selection.language(),
                !selection.completelyParsed(),
                sensitive ? "" : selection.summary()
        ));
        boolean contextLimited = selection.selectedChunks() < selection.totalChunks()
                || selection.selectedCharacters() < selection.extractedCharacters();
        fileCoverage.add(new FileAnalysisCoverage(
                requestedPath,
                selection.completelyParsed() ? "COMPLETE" : "PARTIAL",
                selection.sourceBytes(),
                selection.extractedCharacters(),
                selection.totalChunks(),
                selection.selectedChunks(),
                selection.selectedCharacters(),
                contextLimited,
                contextLimited
                        ? "全文已建立临时索引，本次只发送与任务相关的片段"
                        : "本次已使用全部提取内容"
        ));
        if (contextLimited) {
            warnings.add(
                    "文档全文已建立临时索引，本次从 "
                            + selection.totalChunks()
                            + " 个片段中选取 "
                            + selection.selectedChunks()
                            + " 个相关片段："
                            + requestedPath
            );
        }
    }

    /** 结合上传输入和逐文件覆盖率确定 EMPTY、FAILED、PARTIAL 或 COMPLETE。 */
    private String resolveAnalysisStatus(
            ContextAnalysisRequest request,
            Map<String, AnalyzedFile> files,
            List<FileAnalysisCoverage> fileCoverage
    ) {
        if (request.files().isEmpty() && (request.customDescription() == null
                || request.customDescription().isBlank())) {
            return "EMPTY";
        }
        if (files.isEmpty() && !request.files().isEmpty()) {
            return "FAILED";
        }
        boolean partial = fileCoverage.stream()
                .anyMatch(item -> !"COMPLETE".equals(item.extractionStatus()));
        return partial ? "PARTIAL" : "COMPLETE";
    }

    /**
     * 根据文件路径和内容关键词识别技术栈，保留置信度更高的结果。
     */
    private void detectTechnology(AnalyzedFile file, Map<String, TechnologyStackItem> stack) {
        String source = sourcePathOf(file.path());
        String path = source.toLowerCase(Locale.ROOT);
        String content = file.content().toLowerCase(Locale.ROOT);
        String language = file.language().toLowerCase(Locale.ROOT);
        if (path.endsWith("pom.xml") || path.endsWith("build.gradle") || path.endsWith("build.gradle.kts")) {
            addStack(stack, "Java", source, 0.95);
        }
        if ("java".equals(language) || path.endsWith(".java")) {
            addStack(stack, "Java", source, 0.9);
        }
        if (content.contains("spring-boot") || content.contains("org.springframework.boot")
                || content.contains("@springbootapplication")) {
            addStack(stack, "Spring Boot", source, path.endsWith("pom.xml") ? 0.98 : 0.94);
        }
        if (path.endsWith("package.json")) {
            addStack(stack, "Node.js", source, 0.9);
            if (content.contains("\"vue\"")) {
                addStack(stack, "Vue", source, 0.98);
            }
            if (content.contains("typescript")) {
                addStack(stack, "TypeScript", source, 0.95);
            }
            if (content.contains("\"vite\"")) {
                addStack(stack, "Vite", source, 0.95);
            }
            if (content.contains("\"react\"")) {
                addStack(stack, "React", source, 0.98);
            }
        }
        if ("vue".equals(language) || path.endsWith(".vue")) {
            addStack(stack, "Vue", source, 0.9);
            if (content.contains("lang=\"ts\"") || content.contains("lang='ts'")) {
                addStack(stack, "TypeScript", source, 0.88);
            }
        }
        if ("typescript".equals(language) || path.endsWith(".ts") || path.endsWith(".tsx")) {
            addStack(stack, "TypeScript", source, 0.9);
        }
        if (path.endsWith("requirements.txt") || path.endsWith("pyproject.toml")) {
            addStack(stack, "Python", source, 0.95);
        } else if ("python".equals(language)) {
            addStack(stack, "Python", source, 0.9);
        }
        if (path.endsWith("go.mod")) {
            addStack(stack, "Go", source, 0.98);
        } else if ("go".equals(language)) {
            addStack(stack, "Go", source, 0.9);
        }
        if (path.endsWith("cargo.toml")) {
            addStack(stack, "Rust", source, 0.98);
        } else if ("rust".equals(language)) {
            addStack(stack, "Rust", source, 0.9);
        }
        if (content.contains("postgresql") || content.contains("postgres")) {
            addStack(stack, "PostgreSQL", source, 0.82);
        }
        if (content.contains("redis")) {
            addStack(stack, "Redis", source, 0.82);
        }
    }

    /**
     * 根据文件类型提取 Maven、npm、Python 或 Go 依赖。
     */
    private void extractDependencies(
            AnalyzedFile file,
            List<DependencyItem> dependencies,
            List<String> warnings
    ) {
        String source = sourcePathOf(file.path());
        String path = source.toLowerCase(Locale.ROOT);
        if (path.endsWith("pom.xml")) {
            Matcher matcher = POM_DEPENDENCY.matcher(file.content());
            while (matcher.find()) {
                dependencies.add(new DependencyItem(
                        "maven",
                        matcher.group(1).trim() + ":" + matcher.group(2).trim(),
                        optionalTrim(matcher.group(3)),
                        source
                ));
            }
            return;
        }
        if (path.endsWith("package.json")) {
            extractNodeDependencies(file, source, dependencies, warnings);
            return;
        }
        if (path.endsWith("requirements.txt")) {
            for (String line : file.content().lines().toList()) {
                String candidate = line.trim();
                if (candidate.isEmpty() || candidate.startsWith("#")) {
                    continue;
                }
                Matcher matcher = REQUIREMENT.matcher(candidate);
                if (matcher.matches()) {
                    dependencies.add(new DependencyItem(
                            "python",
                            matcher.group(1),
                            optionalTrim(matcher.group(2)),
                            source
                    ));
                }
            }
            return;
        }
        if (path.endsWith("go.mod")) {
            for (String line : file.content().lines().toList()) {
                Matcher matcher = GO_MODULE.matcher(line);
                if (matcher.matches()) {
                    dependencies.add(new DependencyItem("go", matcher.group(1), matcher.group(2), source));
                }
            }
        }
    }

    /**
     * 解析 package.json 中的 dependencies 和 devDependencies。
     */
    private void extractNodeDependencies(
            AnalyzedFile file,
            String source,
            List<DependencyItem> dependencies,
            List<String> warnings
    ) {
        try {
            JsonNode root = objectMapper.readTree(file.content());
            addNodeDependencyGroup(root.path("dependencies"), source, dependencies);
            addNodeDependencyGroup(root.path("devDependencies"), source, dependencies);
        } catch (IOException | RuntimeException exception) {
            warnings.add("无法解析 package.json：" + source);
        }
    }

    /**
     * 将一组 npm 依赖写入依赖列表。
     */
    private void addNodeDependencyGroup(JsonNode group, String source, List<DependencyItem> dependencies) {
        if (!group.isObject()) {
            return;
        }
        group.fields().forEachRemaining(entry -> dependencies.add(new DependencyItem(
                "npm",
                entry.getKey(),
                entry.getValue().asText(null),
                source
        )));
    }

    /**
     * 按生态和名称去重，保留首次出现的依赖。
     */
    private List<DependencyItem> deduplicateDependencies(List<DependencyItem> dependencies) {
        Map<String, DependencyItem> unique = new LinkedHashMap<>();
        for (DependencyItem dependency : dependencies) {
            unique.putIfAbsent(
                    dependency.ecosystem() + ":" + dependency.name(),
                    dependency
            );
        }
        return new ArrayList<>(unique.values());
    }

    /**
     * 从文件路径构建目录树摘要，并按数量上限截断。
     */
    private List<String> buildDirectoryTree(Set<String> paths, List<String> warnings) {
        Set<String> entries = new LinkedHashSet<>();
        for (String indexedPath : paths) {
            String path = sourcePathOf(indexedPath);
            String[] segments = path.split("/");
            StringBuilder directory = new StringBuilder();
            for (int index = 0; index < segments.length - 1; index++) {
                if (directory.length() > 0) {
                    directory.append('/');
                }
                directory.append(segments[index]);
                entries.add(directory + "/");
            }
            entries.add(path);
        }
        List<String> result = entries.stream().sorted().limit(MAX_DIRECTORY_ENTRIES).toList();
        if (entries.size() > MAX_DIRECTORY_ENTRIES) {
            warnings.add("目录结构摘要已截断");
        }
        return result;
    }

    /**
     * 构建文件内容片段列表，同时受文件数量、单文件和总字符预算限制。
     */
    private List<FileSnippet> buildSnippets(
            Iterable<AnalyzedFile> files,
            List<String> warnings,
            String query
    ) {
        List<FileSnippet> snippets = new ArrayList<>();
        Set<String> includedPaths = new HashSet<>();
        int totalCharacters = 0;
        for (AnalyzedFile file : files) {
            if (snippets.size() >= MAX_SNIPPETS) {
                warnings.add("文件摘要数量已达到上限");
                break;
            }
            String sourcePath = sourcePathOf(file.path());
            if (file.content().isBlank()) {
                warnings.add("文件内容为空，无法生成摘要：" + sourcePath);
                continue;
            }
            if (!includedPaths.add(sourcePath)) {
                continue;
            }
            int remaining = MAX_SNIPPET_TOTAL_CHARS - totalCharacters;
            if (remaining <= 0) {
                warnings.add("文件内容摘要已达到 Token 预算");
                break;
            }
            int allowance = Math.min(MAX_SNIPPET_CHARS, remaining);
            ContentChunkSelector.Selection selection = contentChunkSelector.select(
                    file.content(),
                    query,
                    allowance,
                    MAX_CHUNKS_PER_FILE
            );
            boolean truncated = file.truncated() || selection.contextLimited();
            String summary = file.summary().isBlank()
                    ? fileContentSummarizer.summarize(sourcePath, file.language(), file.content())
                    : file.summary();
            snippets.add(new FileSnippet(
                    sourcePath,
                    file.language(),
                    selection.content(),
                    summary,
                    truncated
            ));
            totalCharacters += selection.selectedCharacters();
            if (selection.contextLimited() && !file.truncated()) {
                warnings.add("文件已完整解析，本次上下文仅选取相关片段：" + sourcePath);
            }
        }
        return snippets;
    }

    /**
     * 清理项目描述，命中敏感规则时整段脱敏。
     */
    private String sanitizeDescription(String description, List<String> warnings, List<String> redactions) {
        if (description == null || description.isBlank()) {
            return "";
        }
        String normalized = description.trim();
        if (containsSecret(normalized)) {
            warnings.add("项目描述疑似包含敏感信息，已脱敏");
            redactions.add("customDescription");
            return "[DESCRIPTION REDACTED: sensitive material detected]";
        }
        return normalized;
    }

    /**
     * 写入技术栈条目，已有更高置信度结果时保留原值。
     */
    private void addStack(Map<String, TechnologyStackItem> stack, String name, String source, double confidence) {
        TechnologyStackItem current = stack.get(name);
        if (current == null || confidence > current.confidence()) {
            stack.put(name, new TechnologyStackItem(name, source, confidence));
        }
    }

    /**
     * 规范化相对路径，拒绝绝对路径和目录穿越。
     */
    private String normalizePath(String rawPath) {
        if (rawPath == null) {
            return null;
        }
        String path = rawPath.trim().replace('\\', '/');
        while (path.startsWith("./")) {
            path = path.substring(2);
        }
        if (path.isBlank() || WINDOWS_ABSOLUTE_PATH.matcher(path).matches()
                || path.indexOf(':') >= 0 || path.chars().anyMatch(character -> character < 32)
                || UNIX_ABSOLUTE_PATH.matcher(path).matches() || TRAVERSAL_PATH.matcher(path).find()) {
            return null;
        }
        return path;
    }

    /**
     * 判断路径是否命中应忽略的生成目录或工具目录。
     */
    private boolean isIgnoredPath(String path) {
        for (String segment : path.split("/")) {
            if (IGNORED_SEGMENTS.contains(segment.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 在后端再次拦截环境变量、密钥和明确的生产配置文件，防止绕过前端直接提交。
     */
    private boolean isProtectedPath(String indexedPath) {
        String path = sourcePathOf(indexedPath).toLowerCase(Locale.ROOT);
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        return SENSITIVE_FILE_NAMES.contains(fileName)
                || (fileName.startsWith(".env.") && !".env.example".equals(fileName))
                || fileName.endsWith(".pem")
                || fileName.endsWith(".key")
                || fileName.endsWith(".p12")
                || fileName.endsWith(".jks")
                || PRODUCTION_CONFIG_PATH.matcher(path).find();
    }

    /**
     * 通过空字符标记判断内容是否疑似二进制。
     */
    private boolean containsBinaryMarker(String content) {
        return content.indexOf('\u0000') >= 0;
    }

    /**
     * 判断内容是否包含常见密钥、私钥或 API Key 形态的敏感信息。
     */
    private boolean containsSecret(String content) {
        return SECRET_VALUE.matcher(content).find()
                || PRIVATE_KEY.matcher(content).find()
                || OPENAI_STYLE_KEY.matcher(content).find();
    }

    /**
     * 根据扩展名或用户显式指定的语言推断文件语言。
     */
    private String languageOf(String path, String explicitLanguage) {
        if (explicitLanguage != null && !explicitLanguage.isBlank()) {
            return explicitLanguage.trim();
        }
        String lowerPath = sourcePathOf(path).toLowerCase(Locale.ROOT);
        if (lowerPath.endsWith(".java")) return "java";
        if (lowerPath.endsWith(".ts") || lowerPath.endsWith(".tsx")) return "typescript";
        if (lowerPath.endsWith(".mjs") || lowerPath.endsWith(".cjs")) return "javascript";
        if (lowerPath.endsWith(".mts") || lowerPath.endsWith(".cts")) return "typescript";
        if (lowerPath.endsWith(".vue")) return "vue";
        if (lowerPath.endsWith(".py")) return "python";
        if (lowerPath.endsWith(".pyi") || lowerPath.endsWith(".pyx")) return "python";
        if (lowerPath.endsWith(".go")) return "go";
        if (lowerPath.endsWith(".rs")) return "rust";
        if (lowerPath.endsWith(".csproj") || lowerPath.endsWith(".xaml")) return "xml";
        if (lowerPath.endsWith(".razor") || lowerPath.endsWith(".cshtml")) return "razor";
        if (lowerPath.endsWith(".pptx")) return "pptx";
        if (lowerPath.endsWith(".ppt")) return "ppt";
        if (lowerPath.endsWith(".pdf")) return "pdf";
        if (lowerPath.endsWith(".docx")) return "docx";
        if (lowerPath.endsWith(".doc")) return "doc";
        if (lowerPath.endsWith(".xlsx")) return "xlsx";
        if (lowerPath.endsWith(".xls")) return "xls";
        if (lowerPath.endsWith(".et")) return "et";
        if (lowerPath.endsWith(".dps")) return "dps";
        if (lowerPath.endsWith(".wps")) return "wps";
        if (lowerPath.endsWith(".json")) return "json";
        if (lowerPath.endsWith(".xml")) return "xml";
        if (lowerPath.endsWith(".yml") || lowerPath.endsWith(".yaml")) return "yaml";
        return "text";
    }

    /**
     * 按 UTF-8 字节数截断内容，避免截断多字节字符。
     */
    private String truncateByBytes(String content, int maxBytes) {
        StringBuilder result = new StringBuilder();
        int bytes = 0;
        for (int offset = 0; offset < content.length();) {
            int codePoint = content.codePointAt(offset);
            String character = new String(Character.toChars(codePoint));
            int characterBytes = character.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + characterBytes > maxBytes) {
                break;
            }
            result.append(character);
            bytes += characterBytes;
            offset += Character.charCount(codePoint);
        }
        return result.toString();
    }

    /**
     * 返回去空格后的可选值，空值统一转为空字符串。
     */
    private String optionalTrim(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * 本地索引会在原路径后附加代码块编号；分析与展示时恢复为真实源文件路径。
     */
    private String sourcePathOf(String indexedPath) {
        return INDEX_CHUNK_SUFFIX.matcher(indexedPath).replaceFirst("");
    }

    /**
     * 内部使用的文件分析载体。
     */
    private record AnalyzedFile(
            String path,
            String content,
            String language,
            boolean truncated,
            String summary
    ) {
    }
}
