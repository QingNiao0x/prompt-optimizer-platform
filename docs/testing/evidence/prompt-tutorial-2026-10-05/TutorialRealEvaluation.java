import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.context.domain.*;
import com.promptoptimizer.enhancement.domain.*;
import com.promptoptimizer.enhancement.dto.*;
import com.promptoptimizer.enhancement.service.impl.*;
import com.promptoptimizer.policy.service.impl.ConstraintCompleterImpl;
import com.promptoptimizer.provider.domain.*;
import com.promptoptimizer.provider.infrastructure.openai.*;
import com.promptoptimizer.template.domain.*;
import com.promptoptimizer.template.service.impl.PromptTemplateRegistryImpl;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.slf4j.MDC;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Isolated synthetic real-adapter evaluation: no login, Spring boot, DB or bound Plan. */
public final class TutorialRealEvaluation {
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    static final Path ROOT = Path.of("tmp/template-tutorial-20261005");
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !Set.of("baseline", "candidate", "execution", "execution-calibrated", "plan").contains(args[0]))
            throw new IllegalArgumentException("INVALID_MODE");
        var env = new StandardEnvironment();
        for (var source : new YamlPropertySourceLoader().load("tutorial-eval",
                new FileSystemResource("services/api/src/main/resources/application.yml")))
            env.getPropertySources().addLast(source);
        var props = Binder.get(env).bind("app.provider.openai-compatible", OpenAiCompatibleProperties.class)
                .orElseThrow(() -> new IllegalStateException("MODEL_PROPERTIES_UNAVAILABLE"));
        var root = JSON.readTree(Files.readString(ROOT.resolve("cases.json")));
        String modelId = root.path("modelId").asText();
        var selection = props.resolveModel(modelId);
        if (selection.route().apiKey().isBlank()) throw new IllegalStateException("MODEL_KEY_UNAVAILABLE");
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5)); factory.setReadTimeout(Duration.ofSeconds(90));
        var client = RestClient.builder().requestFactory(factory).build();
        var provider = new OpenAiCompatiblePromptEnhancementProvider(client, JSON, props);
        for (var c : root.path("cases")) {
            String id = c.path("id").asText();
            if (args[0].equals("baseline") && !Set.of("TT-GUIDE-L", "TT-ACADEMIC-L").contains(id)) continue;
            if (args[0].equals("plan") && !Set.of("TT-GUIDE-L", "TT-HOSPITAL-M", "TT-NEWS-M").contains(id)) continue;
            String raw = c.path("rawPrompt").asText();
            String description = c.path("contextDescription").asText();
            var snippets = new ArrayList<FileSnippet>();
            for (var f : c.path("files")) snippets.add(new FileSnippet(f.path("path").asText(), f.path("language").asText(),
                    f.path("content").asText(), f.path("content").asText(), false));
            // Synthetic snapshots exercise actual template/provider/assembler; never claim folder-index coverage.
            var context = new ContextSnapshot(description, List.of(), List.of(), snippets.stream().map(FileSnippet::path).toList(),
                    snippets, List.of(), List.of(), "tutorial-synthetic-v1");
            if (args[0].startsWith("execution")) {
                var optimized = JSON.readTree(Files.readString(ROOT.resolve("candidate-" + id + ".json")));
                if (!optimized.path("status").asText().equals("SUCCESS")) continue;
                String prompt = optimized.path("result").path("optimizedPrompt").asText();
                String material = "\n\n补充描述：" + description + "\n提供资料：\n" + JSON.writeValueAsString(c.path("files"));
                for (String arm : List.of("raw", "enhanced")) {
                    var record = base(args[0], id, modelId);
                    record.put("parameters", Map.of("maxTokens",8192,"temperature",0.2,"thinking",
                            args[0].equals("execution-calibrated") ? "disabled" : "provider-default"));
                    long started = System.nanoTime();
                    try {
                        String task = (arm.equals("raw") ? raw : prompt) + material;
                        var body = new LinkedHashMap<String,Object>(Map.of("model", selection.model(), "messages", List.of(
                                Map.of("role", "system", "content", "请执行用户任务并交付实际成果，不要再次优化提示词。相同资料仅用于核对事实，不得编造事实或凭据。"),
                                Map.of("role", "user", "content", "任务：\n" + task)), "temperature", 0.2, "max_tokens", 8192, "stream", false));
                        // A separate diagnostic arm; never rewrite first outputs or change platform defaults.
                        if (args[0].equals("execution-calibrated")) body.put("thinking",Map.of("type","disabled"));
                        var response = client.post().uri(selection.route().endpoint())
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + selection.route().apiKey()).contentType(MediaType.APPLICATION_JSON)
                                .body(body).retrieve().body(String.class);
                        var node = JSON.readTree(response); var choice = node.path("choices").get(0);
                        String text = choice.path("message").path("content").asText("");
                        String finish = choice.path("finish_reason").asText();
                        record.put("status", text.isBlank() ? "EMPTY" : finish.equals("length") ? "PARTIAL" : "COMPLETE");
                        record.put("actualModel", node.path("model").asText(selection.model())); record.put("finishReason", finish);
                        record.put("usage", node.path("usage")); record.put("output", text);
                    } catch (Exception e) { record.put("status", "FAILED"); record.put("reason", e.getClass().getSimpleName()); }
                    record.put("elapsedMs", (System.nanoTime()-started)/1_000_000); record.put("arm", arm);
                    save(args[0]+"-"+id+"-"+arm, record, selection.route().apiKey());
                }
                continue;
            }
            var record = base(args[0], id, modelId); long started = System.nanoTime();
            try {
                if (args[0].equals("plan")) {
                    var digest = new PlanningContextDigest(description, List.of(), List.of(), context.directoryTree(),
                            snippets.stream().map(f -> f.path()+": "+f.content()).toList(), "COMPLETE", snippets.size(), List.of());
                    record.put("plan", provider.plan(new PlanningProviderRequest(raw, description, List.of(), digest, modelId)));
                    record.put("boundPlan", false);
                } else {
                    var intent = TaskIntentResolver.resolve(TemplateCode.AUTO, raw);
                    var template = new PromptTemplateRegistryImpl().resolve(TemplateCode.AUTO, raw);
                    var completer = new ConstraintCompleterImpl();
                    var constraints = intent.engineeringConstraints() && !TaskIntentResolver.software(template.code())
                            ? completer.completeWithAuxiliaryCode(context, PermissionPolicyInput.empty(), true, template.code())
                            : completer.complete(context, PermissionPolicyInput.empty(), true, template.code());
                    var request = new EnhancementProviderRequest(raw, context, template, List.of(), constraints, List.of(),
                            new EnhancementOptions(TemplateCode.AUTO, false, true, false), modelId);
                    var result = provider.enhanceValidated(request, response -> new OptimizationResultAssembler().assemble(response,
                            context, template, List.of(), List.of(), false, constraints, false, (System.nanoTime()-started)/1_000_000, raw));
                    record.put("intent", intent); record.put("result", result);
                    record.put("constraintsRetained", constraints.stream().allMatch(result.optimizedPrompt()::contains));
                    record.put("ambiguitiesCopied", result.ambiguities().stream().allMatch(result.optimizedPrompt()::contains));
                    if (result.provider().mock()) throw new IllegalStateException("MOCK_NOT_ALLOWED");
                }
                record.put("status", "SUCCESS");
            } catch (Exception e) {
                record.put("status", "FAILED");
                record.put("reason", e instanceof ProviderResponseValidationException v ? v.getReason().name() : e.getClass().getSimpleName());
            }
            record.put("elapsedMs", (System.nanoTime()-started)/1_000_000);
            save(args[0]+"-"+id, record, selection.route().apiKey());
        }
    }
    static Map<String,Object> base(String stage, String id, String model) {
        var record = new LinkedHashMap<String,Object>(); String requestId=UUID.randomUUID().toString(); MDC.put("requestId",requestId);
        record.put("scope","REAL_COMPONENT_CHAIN_SYNTHETIC_NO_HTTP_AUTH_OR_BOUND_PLAN");
        record.put("stage",stage);record.put("caseId",id);record.put("modelId",model);record.put("requestId",requestId);
        record.put("collectedAt",Instant.now().toString()); return record;
    }
    static void save(String name, Map<String,Object> record, String key) throws Exception {
        String value = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(record);
        if (value.contains(key) || new SensitiveValueDetector().containsCredential(value)) throw new IllegalStateException("SECRET_OUTPUT_REJECTED");
        Files.writeString(ROOT.resolve(name+".json"), value+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        System.out.println(JSON.writeValueAsString(Map.of("event","tutorial.result","file",name,"status",record.get("status"),"elapsedMs",record.get("elapsedMs"))));
        MDC.clear();
    }
}
