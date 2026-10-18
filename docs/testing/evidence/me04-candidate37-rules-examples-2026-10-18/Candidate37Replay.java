import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.*;
import com.promptoptimizer.enhancement.dto.PermissionPolicyInput;
import com.promptoptimizer.enhancement.service.impl.*;
import com.promptoptimizer.policy.service.impl.ConstraintCompleterImpl;
import com.promptoptimizer.provider.domain.*;
import com.promptoptimizer.template.domain.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * 对六份已保存合成响应执行相同组装入口；不联网、不启动Spring、不修改原件或创建账号。
 * @author QingNiao
 * @since 0.1.0
 */
public final class Candidate37Replay {
    /** 运行目录与输出必须明确绑定；不覆盖既有回放结果。 */
    public static void main(String[] args) throws Exception {
        Path root = Path.of("").toAbsolutePath().normalize();
        Path out = root.resolve(args[0]).normalize();
        if (!out.startsWith(root.resolve("tmp/me04-candidate37-20261018"))) throw new IllegalArgumentException("Output outside replay workspace");
        Path runtime = Path.of(OptimizationResultAssembler.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (!runtime.equals(root.resolve(args[1]).normalize())) throw new IllegalArgumentException("Unexpected runtime");
        var json = new ObjectMapper();
        Path fixture = root.resolve("services/api/src/test/resources/fixtures/candidate37-existing-model-replay.json");
        var rows = new ArrayList<Map<String, Object>>();
        Files.createDirectories(out);
        for (var sample : json.readTree(Files.readString(fixture)).path("samples")) {
            String raw = sample.path("rawPrompt").asText();
            String modelText = sample.path("modelResponseRaw").asText();
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(modelText.getBytes(StandardCharsets.UTF_8)));
            if (!sha.equals(sample.path("modelResponseSha256").asText())) throw new IllegalArgumentException("Original response changed");
            var model = json.readTree(modelText);
            var response = new EnhancementProviderResponse(json.convertValue(model.path("sections"), new TypeReference<List<PromptSection>>() {}),
                    "deepseek", "deepseek-flash", false, List.of(), List.of(), json.treeToValue(model.path("assessment"), TaskEnhancementAssessment.class));
            var context = new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "EMPTY");
            var bundle = new ConstraintCompleterImpl().completeForTask(context, PermissionPolicyInput.empty(), false, TaskIntentResolver.resolve(TemplateCode.AUTO, raw));
            var task = EffectiveTaskViewFactory.create(raw, TemplateCode.AUTO, List.of(), List.of(), false);
            for (var format : PromptFormat.values()) {
                var row = new LinkedHashMap<String, Object>();
                row.put("trace", sample.path("trace").asInt()); row.put("format", format); row.put("modelResponseSha256", sha);
                Path directory = out.resolve(sample.path("trace").asText() + "-" + format.name()); Files.createDirectories(directory);
                Files.writeString(directory.resolve("model-response-raw.json"), modelText, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                try {
                    var result = new OptimizationResultAssembler().assemble(response, context,
                            new PromptTemplate(TemplateCode.AUTO, "保留交付。", "遵守原要求。", "不新增示例。"),
                            List.of(), List.of(), false, bundle, false, 1, raw, List.of(), List.of(), task, format);
                    String body = result.optimizedPrompt();
                    Files.writeString(directory.resolve("optimized-prompt.md"), body, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                    Files.writeString(directory.resolve("result.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(result), StandardOpenOption.CREATE_NEW);
                    row.put("state", "ASSEMBLED"); row.put("bodyCodePoints", body.codePointCount(0, body.length()));
                    row.put("bodySha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8))));
                    row.put("constraintGroups", bundle); row.put("ambiguities", result.ambiguities());
                } catch (ProviderResponseValidationException error) {
                    row.put("state", "REJECTED"); row.put("reason", error.getReason()); row.put("field", error.getField());
                }
                rows.add(row);
            }
        }
        Files.writeString(out.resolve("summary.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(rows), StandardOpenOption.CREATE_NEW);
        System.out.println("Offline replay entries=" + rows.size() + "; model calls=0");
    }
}
