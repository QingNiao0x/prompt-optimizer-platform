import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.PromptFormat;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.PermissionPolicyInput;
import com.promptoptimizer.enhancement.service.impl.EffectiveTaskViewFactory;
import com.promptoptimizer.enhancement.service.impl.OptimizationResultAssembler;
import com.promptoptimizer.policy.service.impl.ConstraintCompleterImpl;
import com.promptoptimizer.provider.domain.EnhancementProviderResponse;
import com.promptoptimizer.provider.domain.TaskEnhancementAssessment;
import com.promptoptimizer.template.domain.PromptTemplate;
import com.promptoptimizer.template.domain.TaskIntentResolver;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/**
 * 对已保存合成模型原件运行完整公开组装入口；不启动Spring，不调用Provider或读账户资料。
 * 同一助手可分别加载父／新冻结classes，结果来源字段通过JSON读取以兼容旧结果类型。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class Candidate35Replay {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path ROOT = Path.of("").toAbsolutePath().normalize();
    private static final Path FIXTURE = ROOT.resolve("services/api/src/test/resources/fixtures/candidate34-task-constraint-replay.json");
    private static final String HEADING = "平台强制约束（不得删除或弱化）：";

    private Candidate35Replay() { }

    /** 参数为冻结清单和全新私有输出目录；先校验实际加载类来源与全部运行文件哈希。 */
    public static void main(String[] args) throws Exception {
        require(args.length == 2, "Frozen manifest and new private output directory required");
        Path manifestPath = owned(args[0]);
        require(List.of(
                ROOT.resolve("docs/testing/evidence/me04-candidate34-framework-natural-2026-10-18/candidate34-r1-source.json"),
                ROOT.resolve("docs/testing/evidence/me04-candidate35-platform-rules-2026-10-18/candidate35-source.json"))
                .contains(manifestPath), "Only the declared parent and candidate manifests may be read");
        Path output = owned(args[1]);
        require(output.startsWith(ROOT.resolve("tmp")), "Replay output must stay under repository tmp");
        require(!Files.exists(output), "Never overwrite a replay output");
        byte[] manifestBytes = Files.readAllBytes(manifestPath);
        JsonNode manifest = JSON.readTree(manifestBytes);
        String candidate = manifest.path("version").asText();
        require(List.of("candidate34-r1", "candidate35").contains(candidate), "Unexpected frozen candidate");
        Path runtime = owned(manifest.path("runtimeSnapshot").asText());
        Path loaded = Path.of(OptimizationResultAssembler.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toAbsolutePath().normalize();
        require(loaded.equals(runtime), "Assembler was not loaded from the declared frozen runtime");
        require(manifest.path("runtimeFiles").size() > 0, "Empty frozen runtime manifest");
        for (JsonNode row : manifest.path("runtimeFiles")) {
            Path file = runtime.resolve(row.path("path").asText()).normalize();
            require(file.startsWith(runtime), "Runtime path escaped frozen snapshot");
            require(sha(Files.readAllBytes(file)).equals(row.path("sha256").asText()), "Frozen runtime hash drift");
        }
        byte[] fixtureBytes = Files.readAllBytes(FIXTURE);
        var fixture = JSON.readTree(fixtureBytes);
        require(fixture.path("samples").size() == 2, "Expected exactly two saved model responses");
        var summary = JSON.createObjectNode();
        summary.put("runtimeCandidate", candidate).put("manifestSha256", sha(manifestBytes))
                .put("fixtureSha256", sha(fixtureBytes)).put("replayOnly", true)
                .put("newModelCalls", 0).put("newDownstreamWorks", 0).put("springStarted", false)
                .put("actualNativeCopyOrDatabaseHistory", false);
        var rows = summary.putArray("cases");
        Files.createDirectories(output);
        for (JsonNode sample : fixture.path("samples")) {
            rows.add(replay(sample, output));
        }
        writeNew(output.resolve("summary.json"), summary);
        System.out.println(JSON.writeValueAsString(summary));
    }

    /** 仅沿用Provider已有的缺省presentation兼容；原始JSON及其SHA256不改写。 */
    private static ObjectNode replay(JsonNode sample, Path output) throws Exception {
        String id = sample.path("id").asText();
        require(List.of("REQUESTS-COMPACT", "REQUESTS-STRUCTURED").contains(id), "Unknown synthetic fixture");
        int trace = sample.path("trace").asInt();
        require(trace == (id.endsWith("-COMPACT") ? 382 : 383), "Fixture trace and mode mismatch");
        PromptFormat format = PromptFormat.valueOf(id.substring("REQUESTS-".length()));
        String raw = sample.path("rawPrompt").asText();
        require("使用requests库爬取网页标题。".equals(raw), "Original task drift");
        String modelRaw = sample.path("modelResponseRaw").asText();
        require(sha(modelRaw.getBytes(StandardCharsets.UTF_8)).equals(sample.path("modelResponseSha256").asText()),
                "Saved model response hash mismatch");
        JsonNode original = JSON.readTree(modelRaw);
        var sections = JSON.convertValue(original.path("sections"), new TypeReference<List<PromptSection>>() { });
        var assessment = (ObjectNode) original.path("assessment").deepCopy();
        boolean normalizedPresentation = !assessment.has("presentation");
        if (normalizedPresentation) assessment.put("presentation", "STRUCTURED");
        var provider = new EnhancementProviderResponse(sections, "deepseek", "deepseek-flash", false,
                List.of(), List.of(), JSON.treeToValue(assessment, TaskEnhancementAssessment.class));
        var context = new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "EMPTY");
        var bundle = new ConstraintCompleterImpl().completeForTask(context, PermissionPolicyInput.empty(), false,
                TaskIntentResolver.resolve(TemplateCode.AUTO, raw));
        var view = EffectiveTaskViewFactory.create(raw, TemplateCode.AUTO, List.of(), List.of(), false);
        var result = new OptimizationResultAssembler().assemble(provider, context,
                new PromptTemplate(TemplateCode.AUTO, "保留交付。", "遵守原要求。", "不添加未授权示例。"),
                List.of(), List.of(), false, bundle, false, 1, raw, List.of(), List.of(), view, format);
        JsonNode resultJson = JSON.valueToTree(result);
        require(format.name().equals(resultJson.path("promptFormat").asText()), "Requested presentation was not preserved");
        String body = result.optimizedPrompt();
        Path folder = output.resolve(id);
        Files.createDirectory(folder);
        Files.writeString(folder.resolve("model-response-raw.json"), modelRaw, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        Files.writeString(folder.resolve("optimized-prompt.md"), body, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        writeNew(folder.resolve("assembled-result.json"), resultJson);
        var row = JSON.createObjectNode();
        row.put("caseId", id).put("trace", trace).put("requestedFormat", format.name())
                .put("rawPromptSha256", sha(raw.getBytes(StandardCharsets.UTF_8)))
                .put("modelResponseSha256", sample.path("modelResponseSha256").asText())
                .put("missingPresentationNormalizedAsExistingProvider", normalizedPresentation)
                .put("bodySha256", sha(body.getBytes(StandardCharsets.UTF_8)))
                .put("bodyWithoutLegacyHeadingLineSha256", sha(body.replace(HEADING + "\n", "").getBytes(StandardCharsets.UTF_8)))
                .put("bodyUtf16Length", body.length()).put("bodyCodePointLength", body.codePointCount(0, body.length()))
                .put("bodyHanCount", body.codePoints().filter(point -> Character.UnicodeScript.of(point) == Character.UnicodeScript.HAN).count())
                .put("legacyHeadingOccurrences", occurrences(body, HEADING));
        row.set("sourceConstraintGroups", JSON.valueToTree(bundle));
        // 父候选没有新accessor；从实际序列化结果观察来源，不为旧输出伪造新元数据。
        row.set("resultConstraintGroups", resultJson.path("constraintGroups").isMissingNode()
                ? com.fasterxml.jackson.databind.node.NullNode.instance : resultJson.path("constraintGroups"));
        row.set("appliedConstraints", resultJson.path("appliedConstraints"));
        row.set("ambiguities", resultJson.path("ambiguities"));
        var counts = row.putArray("canonicalPlatformRules");
        boolean complete = true;
        for (String rule : bundle.platformMandatory()) {
            int count = occurrences(body, rule);
            counts.addObject().put("rule", rule).put("exactOccurrences", count).put("present", count > 0);
            complete &= count > 0;
        }
        row.put("canonicalPlatformRulesComplete", complete);
        var content = JSON.createArrayNode();
        for (var section : result.sections()) {
            if (!"CONSTRAINTS".equals(section.type().name())) content.add(JSON.valueToTree(section));
        }
        row.put("otherSectionsSha256", sha(JSON.writeValueAsBytes(content)));
        writeNew(folder.resolve("checks.json"), row);
        return row;
    }

    /** 只统计完整原文出现次数，不将字符串命中当作任意同义语义等价证明。 */
    private static int occurrences(String text, String value) {
        int count = 0;
        for (int offset = 0; (offset = text.indexOf(value, offset)) >= 0; offset += value.length()) count++;
        return count;
    }

    /** 所有输入和输出保留在本仓库，禁止读取账户、密钥或其他任意外部路径。 */
    private static Path owned(String value) {
        Path path = ROOT.resolve(value).normalize();
        require(path.startsWith(ROOT), "Replay path escaped repository");
        return path;
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void writeNew(Path path, JsonNode value) throws Exception {
        Files.writeString(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalArgumentException(message);
    }
}
