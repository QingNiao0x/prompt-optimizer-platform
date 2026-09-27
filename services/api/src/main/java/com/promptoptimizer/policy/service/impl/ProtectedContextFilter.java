package com.promptoptimizer.policy.service.impl;

import com.promptoptimizer.context.dto.ContextAnalysisRequest;
import com.promptoptimizer.context.dto.ContextFileInput;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.context.domain.FileAnalysisCoverage;
import com.promptoptimizer.enhancement.dto.PermissionPolicyInput;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 在上下文分析和 Provider 调用前移除平台及用户声明的受保护路径。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Component
public class ProtectedContextFilter {

    private static final Pattern CHUNK_SUFFIX = Pattern.compile("#chunk-\\d+$", Pattern.CASE_INSENSITIVE);

    /**
     * 在上下文分析前剔除受保护路径；返回被过滤路径供用户查看覆盖率与脱敏报告。
     */
    public FilteredContext filter(ContextAnalysisRequest request, PermissionPolicyInput permissionPolicy) {
        List<PathRule> rules = rules(permissionPolicy);
        List<ContextFileInput> allowed = new ArrayList<>();
        Set<String> protectedPaths = new LinkedHashSet<>();
        for (ContextFileInput file : request.files()) {
            String normalized = normalize(file.path());
            if (rules.stream().anyMatch(rule -> rule.matches(normalized))) {
                protectedPaths.add(sourcePath(file.path()));
            } else {
                allowed.add(file);
            }
        }
        return new FilteredContext(
                new ContextAnalysisRequest(request.customDescription(), allowed),
                List.copyOf(protectedPaths)
        );
    }

    /** 将未读取的受保护文件标记为部分覆盖，避免误报为完整分析。 */
    public ContextSnapshot attachReport(ContextSnapshot snapshot, FilteredContext filtered) {
        if (filtered.protectedPaths().isEmpty()) {
            return snapshot;
        }
        List<String> warnings = new ArrayList<>(snapshot.warnings());
        List<String> redactions = new ArrayList<>(snapshot.redactions());
        List<FileAnalysisCoverage> coverage = new ArrayList<>(snapshot.fileCoverage());
        for (String path : filtered.protectedPaths()) {
            warnings.add("已在分析前过滤受保护文件：" + path);
            redactions.add(path);
            coverage.add(new FileAnalysisCoverage(
                    path,
                    "FAILED",
                    0,
                    0,
                    0,
                    0,
                    0,
                    false,
                    "受权限策略保护，未读取文件内容。"
            ));
        }
        return new ContextSnapshot(
                snapshot.customDescription(),
                snapshot.technologyStack(),
                snapshot.dependencies(),
                snapshot.directoryTree(),
                snapshot.fileSnippets(),
                "PARTIAL",
                coverage,
                warnings,
                redactions,
                snapshot.analysisVersion()
        );
    }

    private List<PathRule> rules(PermissionPolicyInput policy) {
        Set<String> values = new LinkedHashSet<>(PlatformPermissionPolicy.PROTECTED_PATHS);
        values.addAll(policy.protectedPaths());
        return values.stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(this::compileRule)
                .toList();
    }

    /** 把权限规则转为路径匹配器，支持 globstar 与仅文件名规则。 */
    private PathRule compileRule(String value) {
        if ("生产环境配置".equals(value)) {
            return path -> path.matches(".*(?:application[-.](?:prod|production)|config/(?:prod|production))\\.(?:yml|yaml|properties|json|toml)$");
        }
        String normalized = normalize(value);
        StringBuilder regex = new StringBuilder("^");
        for (int index = 0; index < normalized.length(); index++) {
            char current = normalized.charAt(index);
            if (current == '*') {
                boolean globstar = index + 1 < normalized.length() && normalized.charAt(index + 1) == '*';
                if (globstar) {
                    index++;
                    if (index + 1 < normalized.length() && normalized.charAt(index + 1) == '/') {
                        index++;
                        regex.append("(?:.*/)?");
                    } else {
                        regex.append(".*");
                    }
                } else {
                    regex.append("[^/]*");
                }
            } else if (current == '?') {
                regex.append("[^/]");
            } else {
                regex.append(Pattern.quote(String.valueOf(current)));
            }
        }
        Pattern pattern = Pattern.compile(regex.append('$').toString(), Pattern.CASE_INSENSITIVE);
        boolean fileNameOnly = !normalized.contains("/");
        return path -> pattern.matcher(path).matches()
                || fileNameOnly && pattern.matcher(fileName(path)).matches();
    }

    private String normalize(String path) {
        String normalized = sourcePath(path).replace('\\', '/').toLowerCase(Locale.ROOT);
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    private String sourcePath(String path) {
        return CHUNK_SUFFIX.matcher(path == null ? "" : path.trim()).replaceFirst("");
    }

    private String fileName(String path) {
        int separator = path.lastIndexOf('/');
        return separator < 0 ? path : path.substring(separator + 1);
    }

    @FunctionalInterface
    private interface PathRule {
        boolean matches(String path);
    }

    /**
     * 可继续分析的请求及本次被拦截的受保护路径。
     *
     * @author QingNiao
     * @since 0.1.0
     */
    public record FilteredContext(ContextAnalysisRequest request, List<String> protectedPaths) {

        public FilteredContext {
            protectedPaths = List.copyOf(protectedPaths);
        }
    }
}
