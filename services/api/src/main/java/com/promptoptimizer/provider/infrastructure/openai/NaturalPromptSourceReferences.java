package com.promptoptimizer.provider.infrastructure.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.provider.domain.*;
import java.util.*;

/**
 * 给元数据补正提供原来源的逐行引用选项。模型显式选择，服务端不做模糊匹配、自动选择或语义背书。
 * 仅绑定当前出错的evidence路径，后续来源类别、实际题答及正文覆盖仍须通过原校验。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class NaturalPromptSourceReferences {
    private static final int MAX_REFERENCES = 4096;
    private static final int MAX_QUOTE_CHARACTERS = 200_000;
    private static final NaturalPromptSourceReferences EMPTY = new NaturalPromptSourceReferences(Map.of(), Map.of());
    private final Map<String, Option> options;
    private final Map<String, List<String>> paths;

    private NaturalPromptSourceReferences(Map<String, Option> options, Map<String, List<String>> paths) {
        this.options = Collections.unmodifiableMap(new LinkedHashMap<>(options));
        this.paths = Collections.unmodifiableMap(new LinkedHashMap<>(paths));
    }

    /** 没有已知来源的引文错误时保持旧补正；超出选项目录容量时也整体回退，不截断后声称覆盖完整。 */
    static NaturalPromptSourceReferences create(JsonNode candidate, List<NaturalPromptMetadataRepair.Issue> issues, NaturalPromptInput input) {
        Map<String, NaturalTaskSource> sources = new LinkedHashMap<>(); input.taskSources().forEach(source -> sources.put(source.id(), source));
        var options = new LinkedHashMap<String, Option>(); var paths = new LinkedHashMap<String, List<String>>();
        Map<String, String> assigned = new HashMap<>(); int characters = 0;
        for (var issue : issues) {
            if (!issue.code().equals("SOURCE_QUOTE_MISMATCH") || paths.containsKey(issue.path())) continue;
            JsonNode evidence = candidate.at(issue.path()); Set<String> sourceIds = new LinkedHashSet<>();
            for (var item : evidence) sourceIds.add(item.path("sourceId").asText());
            // 不根据错误来源编号猜另一个来源；这类错误继续使用原有严格补正方式。
            if (sourceIds.isEmpty() || sourceIds.stream().anyMatch(id -> !sources.containsKey(id))) continue;
            var allowed = new LinkedHashSet<String>();
            for (String sourceId : sourceIds) for (String line : sources.get(sourceId).text().lines().toList()) {
                if (line.isBlank()) continue;
                String key = sourceId + '\0' + line;
                String id = assigned.get(key);
                if (id == null) {
                    if (options.size() >= MAX_REFERENCES || (long) characters + line.length() > MAX_QUOTE_CHARACTERS) return EMPTY;
                    id = "s" + options.size(); assigned.put(key, id); options.put(id, new Option(id, sourceId, line)); characters += line.length();
                }
                allowed.add(id);
            }
            if (!allowed.isEmpty()) paths.put(issue.path(), List.copyOf(allowed));
        }
        return paths.isEmpty() ? EMPTY : new NaturalPromptSourceReferences(options, paths);
    }

    boolean isEmpty() { return paths.isEmpty(); }

    /** 选项只进入补正数据区，不登记为新taskSource、用户确认、授权或正文。 */
    void appendTo(ObjectNode repair, ObjectMapper json) {
        if (isEmpty()) return;
        repair.set("sourceReferences", json.valueToTree(options.values())); repair.set("referencePaths", json.valueToTree(paths));
    }

    /** 已提供引用选项的路径只接受显式编号，拒绝自由抄写、跨路径编号、重复或空选择。 */
    JsonNode resolve(String path, JsonNode value, ObjectNode factory) {
        if (!paths.containsKey(path)) return value.deepCopy();
        if (!value.isArray() || value.isEmpty() || value.size() > 64) throw invalid();
        Set<String> seen = new HashSet<>(); var evidence = factory.arrayNode();
        for (JsonNode selected : value) {
            if (!selected.isTextual() || !paths.get(path).contains(selected.asText()) || !seen.add(selected.asText())) throw invalid();
            Option option = options.get(selected.asText());
            evidence.addObject().put("sourceId", option.sourceId()).put("quote", option.quote());
        }
        return evidence;
    }

    /** 当前请求内的选项编号；quote逐字来自声明的sourceId，编号不携带任何授权含义。 */
    private record Option(String id, String sourceId, String quote) { }

    private static ProviderException invalid() {
        return new ProviderException(ProviderFailureType.INVALID_RESPONSE, "来源引用选择越界或协议无效", false);
    }
}
