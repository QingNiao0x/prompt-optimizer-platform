import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.PromptFormat;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.PermissionPolicyInput;
import com.promptoptimizer.enhancement.dto.EnhancementOptions;
import com.promptoptimizer.enhancement.service.impl.EffectiveTaskViewFactory;
import com.promptoptimizer.enhancement.service.impl.OptimizationResultAssembler;
import com.promptoptimizer.policy.service.impl.ConstraintCompleterImpl;
import com.promptoptimizer.provider.domain.EnhancementProviderResponse;
import com.promptoptimizer.provider.domain.EnhancementProviderRequest;
import com.promptoptimizer.provider.domain.TaskEnhancementAssessment;
import com.promptoptimizer.provider.infrastructure.openai.OpenAiCompatibleProperties;
import com.promptoptimizer.provider.infrastructure.openai.OpenAiCompatiblePromptEnhancementProvider;
import com.promptoptimizer.template.domain.PromptTemplate;
import com.promptoptimizer.template.domain.TaskIntentResolver;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.springframework.web.client.RestClient;

/**
 * 使用已保存的合成响应回放公开组装入口；只有文件读写，不创建身份、不访问HTTP或数据库。
 * 结果长度及精确字符串计数只作确定性差异记录，不作为语义等价或作品质量证明。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class Candidate36Replay {
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 参数指定受控tmp输出和声明的运行目录，拒绝覆盖既有证据及混用运行文件。 */
    public static void main(String[] args) throws Exception {
        Path root = Path.of("").toAbsolutePath().normalize();
        Path output = root.resolve(args[0]).normalize();
        if (!output.startsWith(root.resolve("tmp/me04-candidate36-20261018"))) throw new IllegalArgumentException("Output outside replay workspace");
        Path runtime = Path.of(OptimizationResultAssembler.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (!runtime.equals(root.resolve(args[1]).normalize())) throw new IllegalArgumentException("Unexpected runtime");
        Files.createDirectories(output);
        var summaries = new ArrayList<ObjectNode>();
        Path fixture = root.resolve("services/api/src/test/resources/fixtures/candidate36-existing-model-replay.json");
        for (JsonNode sample : JSON.readTree(Files.readString(fixture)).path("samples")) {
            String rawModel = sample.path("modelResponseRaw").asText();
            if (!sha(rawModel.getBytes(StandardCharsets.UTF_8)).equals(sample.path("modelResponseSha256").asText()))
                throw new IllegalArgumentException("Saved model response drift");
            var original = JSON.readTree(rawModel);
            var assessment = (ObjectNode) original.path("assessment").deepCopy();
            boolean defaulted = !assessment.has("presentation");
            if (defaulted) assessment.put("presentation", "STRUCTURED");
            String raw = sample.path("rawPrompt").asText();
            var format = PromptFormat.valueOf(sample.path("requestedFormat").asText());
            var response = new EnhancementProviderResponse(
                    JSON.convertValue(original.path("sections"), new TypeReference<List<PromptSection>>() { }),
                    "deepseek", "deepseek-flash", false, List.of(), List.of(), JSON.treeToValue(assessment, TaskEnhancementAssessment.class));
            var context = new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "EMPTY");
            var bundle = new ConstraintCompleterImpl().completeForTask(context, PermissionPolicyInput.empty(), false,
                    TaskIntentResolver.resolve(TemplateCode.AUTO, raw));
            var task = EffectiveTaskViewFactory.create(raw, TemplateCode.AUTO, List.of(), List.of(), false);
            var template = new PromptTemplate(TemplateCode.AUTO, "保留交付。", "遵守原要求。", "不添加未授权示例。");
            var result = new OptimizationResultAssembler().assemble(response, context,
                    template,
                    List.of(), List.of(), false, bundle, false, 1, raw, List.of(), List.of(), task, format);
            var request = new EnhancementProviderRequest(raw, context, template, List.of(), List.of(), false,
                    bundle.visibleConstraints(), List.of(), new EnhancementOptions(TemplateCode.AUTO, false, true, false, format),
                    "deepseek-flash", List.of(), List.of(), task);
            String requestJson = buildOfflineRequest(request);
            Path folder = output.resolve(sample.path("id").asText());
            Files.createDirectories(folder);
            Files.writeString(folder.resolve("model-response-raw.json"), rawModel, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            Files.writeString(folder.resolve("optimized-prompt.md"), result.optimizedPrompt(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            Files.writeString(folder.resolve("result.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            Files.writeString(folder.resolve("constructed-request.json"), requestJson, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            var summary = JSON.createObjectNode();
            summary.put("id", sample.path("id").asText());
            summary.put("trace", sample.path("trace").asInt());
            summary.put("format", format.name());
            summary.put("modelResponseSha256", sha(rawModel.getBytes(StandardCharsets.UTF_8)));
            summary.put("missingPresentationDefaultedAsExistingProvider", defaulted);
            summary.put("bodySha256", sha(result.optimizedPrompt().getBytes(StandardCharsets.UTF_8)));
            summary.put("bodyCodePoints", result.optimizedPrompt().codePointCount(0, result.optimizedPrompt().length()));
            summary.put("constructedRequestUtf8Bytes", requestJson.getBytes(StandardCharsets.UTF_8).length);
            summary.put("constructedRequestSha256", sha(requestJson.getBytes(StandardCharsets.UTF_8)));
            summary.set("constraintGroups", JSON.valueToTree(result.constraintGroups()));
            summary.set("appliedConstraints", JSON.valueToTree(result.appliedConstraints()));
            summary.set("ambiguities", JSON.valueToTree(result.ambiguities()));
            summary.put("containsOldUnsupportedAbsence", result.optimizedPrompt().contains("项目中没有既有实现或既有规则可沿用"));
            summary.put("containsOldMergeContradiction", result.optimizedPrompt().contains("嵌套 Map 采用浅合并：当 current 与 patch 在同一键上的值均为 Map 时，递归合并"));
            summaries.add(summary);
        }
        var report = JSON.createObjectNode();
        report.put("evidenceType", "OFFLINE_SAVED_MODEL_ASSEMBLY_REPLAY");
        report.put("newModelCalls", 0);
        report.put("newDownstreamWorks", 0);
        report.put("runtime", runtime.toString());
        report.put("assemblerClassSha256", sha(Files.readAllBytes(runtime.resolve("com/promptoptimizer/enhancement/service/impl/OptimizationResultAssembler.class"))));
        report.put("fixtureSha256", sha(Files.readAllBytes(fixture)));
        report.set("cases", JSON.valueToTree(summaries));
        Files.writeString(output.resolve("summary.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        System.out.println("Saved model cases replayed: " + summaries.size() + "; model calls: 0");
    }

    /** 仅调用原Provider的请求构造方法；固定无效端点及合成凭据，不调用enhance或发送网络请求。 */
    private static String buildOfflineRequest(EnhancementProviderRequest request) throws Exception {
        var properties = new OpenAiCompatibleProperties();
        properties.setEndpoint(java.net.URI.create("https://offline.invalid/chat/completions"));
        properties.setApiKey("offline-synthetic-placeholder");
        properties.setModel("deepseek-flash");
        properties.setModels(List.of("deepseek-flash"));
        var restClient = RestClient.builder().requestFactory((uri, method) -> {
            throw new AssertionError("Offline replay must never create an HTTP request");
        }).build();
        var provider = new OpenAiCompatiblePromptEnhancementProvider(restClient, JSON, properties);
        var method = OpenAiCompatiblePromptEnhancementProvider.class.getDeclaredMethod("buildRequest", EnhancementProviderRequest.class);
        method.setAccessible(true);
        return JSON.writeValueAsString(method.invoke(provider, request));
    }

    /** 原始UTF-8字节与输出分别哈希，避免把兼容归一化误称为未修改原件。 */
    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
