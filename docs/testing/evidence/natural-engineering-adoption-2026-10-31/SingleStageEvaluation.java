import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.common.security.SensitiveValueScanner;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.context.domain.FileSnippet;
import com.promptoptimizer.enhancement.domain.NaturalProcessing;
import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.dto.ConversationMessage;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.provider.domain.NaturalPromptInput;
import com.promptoptimizer.provider.domain.ProviderException;
import com.promptoptimizer.provider.domain.ProviderFailureType;
import com.promptoptimizer.provider.infrastructure.openai.OpenAiCompatibleNaturalPromptProvider;
import com.promptoptimizer.provider.infrastructure.openai.OpenAiCompatibleProperties;
import com.promptoptimizer.provider.infrastructure.openai.OpenAiCompatibleRouteProperties;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 同指导单阶段对照的 Provider 定向评测入口；完整传入冻结合成材料并执行生产协议校验。
 * 不模拟登录、浏览器索引、Plan 所有权绑定、最终组装或历史保存，不能据此声称工作台验收通过。
 * 本机 relay 负责实际调用计数及外部凭据；本入口只持有 fixture 占位值。
 */
public final class SingleStageEvaluation {
    private static final Logger LOG = LoggerFactory.getLogger(SingleStageEvaluation.class);
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final SensitiveValueScanner SENSITIVE = new SensitiveValueScanner();

    private SingleStageEvaluation() { }

    /** 参数为题库、题号、策略、ENHANCE/PLAN、本机 relay 端口、不可覆盖的结果文件。 */
    public static void main(String[] args) {
        if (args.length != 6) {
            LOG.error("评测参数数量无效，需要六项参数");
            System.exit(2);
        }
        Path output = Path.of(args[5]).toAbsolutePath().normalize();
        ObjectNode result = JSON.createObjectNode();
        result.put("schemaVersion", 1);
        result.put("caseId", args[1]);
        result.put("strategyVersion", args[2]);
        result.put("operation", args[3]);
        result.put("evaluationScope", "PROVIDER_DIRECT");
        result.put("productWorkflowVerified", false);
        result.put("answerBinding", "SYNTHETIC_FIXTURE_NOT_PRODUCT_SESSION_VERIFIED");
        result.put("startedAt", Instant.now().toString());
        long started = System.nanoTime();
        boolean success = false;
        try {
            if (Files.exists(output)) throw new IllegalArgumentException("结果文件已存在，不允许覆盖");
            if (!Set.of("core-repair-v3", "core-repair-single-pass").contains(args[2])) {
                throw new IllegalArgumentException("策略不在本轮冻结范围内");
            }
            String operation = args[3].toUpperCase(java.util.Locale.ROOT);
            if (!Set.of("ENHANCE", "PLAN").contains(operation)) throw new IllegalArgumentException("操作无效");
            int port = Integer.parseInt(args[4]);
            if (port < 1 || port > 65535) throw new IllegalArgumentException("端口无效");
            Path casesPath = Path.of(args[0]);
            if (Files.size(casesPath) > 4_000_000) throw new IllegalArgumentException("题库超出容量");
            JsonNode document = JSON.readTree(Files.readString(casesPath, StandardCharsets.UTF_8));
            JsonNode selected = null;
            for (JsonNode entry : document.path("cases")) {
                if (args[1].equals(entry.path("id").asText())) {
                    if (selected != null) throw new IllegalArgumentException("题号重复");
                    selected = entry;
                }
            }
            if (selected == null) throw new IllegalArgumentException("题号不存在");
            NaturalPromptInput input = input(selected, args[2]);
            if (operation.equals("PLAN") && !input.actualAnswers().isEmpty()) {
                throw new IllegalArgumentException("首次 Plan 不接受预填回答");
            }
            result.set("input", JSON.valueToTree(input));
            var provider = provider(port);
            if (operation.equals("ENHANCE")) {
                var draft = provider.enhance(input, candidate -> {
                    if (candidate == null || candidate.taskUnderstanding() == null
                            || candidate.provider() == null || candidate.framework() == null
                            || candidate.optimizedPrompt() == null || candidate.optimizedPrompt().isBlank()) {
                        throw invalid("自然响应不完整");
                    }
                    if (SENSITIVE.containsCredential(candidate.optimizedPrompt())) throw invalid("正文包含疑似凭据");
                    var task = candidate.taskUnderstanding().resolve(input);
                    task.review().validateEnhancement(task, candidate.optimizedPrompt().strip());
                    return candidate;
                });
                result.set("draft", JSON.valueToTree(draft));
                result.set("taskUnderstanding", JSON.valueToTree(draft.taskUnderstanding().resolve(input)));
                result.set("completedStages", JSON.valueToTree(draft.completedStages()));
                result.set("processing", JSON.valueToTree(NaturalProcessing.completed(
                        draft.completedStages(), input.rawPrompt(), draft.optimizedPrompt())));
            } else {
                var plan = provider.plan(input, candidate -> {
                    if (candidate == null || candidate.taskUnderstanding() == null || candidate.questions() == null
                            || candidate.summary() == null || candidate.summary().isBlank()
                            || candidate.questions().size() > 64) throw invalid("Plan响应不完整");
                    Set<String> ids = new HashSet<>();
                    for (PlanQuestion question : candidate.questions()) {
                        if (question == null || question.id() == null || question.id().isBlank()
                                || !ids.add(question.id())) throw invalid("Plan题号为空或重复");
                    }
                    var task = candidate.taskUnderstanding().resolve(input);
                    task.review().validatePlan(ids);
                    Set<String> linked = task.openDecisions().stream()
                            .map(decision -> decision.questionId()).filter(id -> id != null && !id.isBlank())
                            .collect(Collectors.toSet());
                    if (!linked.equals(ids)) throw invalid("Plan问题与重要未知的关联不完整");
                    return candidate;
                });
                result.set("plan", JSON.valueToTree(plan));
                result.set("taskUnderstanding", JSON.valueToTree(plan.taskUnderstanding().resolve(input)));
                // Plan 响应没有生产阶段记录字段，明确保留未知，不推测阶段或伪造执行事实。
                result.putNull("completedStages");
            }
            success = true;
        } catch (Exception failure) {
            result.put("errorType", failure.getClass().getSimpleName());
            if (failure instanceof ProviderException providerFailure) {
                result.put("failureType", providerFailure.getFailureType().name());
                result.put("message", SENSITIVE.redact(providerFailure.getMessage()));
            } else {
                result.put("failureType", "EVALUATION_INPUT_OR_TOOL_FAILURE");
                // 任意异常可能携带路径、原始响应或连接细节；只保留固定说明和异常类别。
                result.put("message", "评测输入或工具处理失败，请检查冻结参数及本机 relay 状态");
            }
        }
        result.put("success", success);
        result.put("elapsedMs", (System.nanoTime() - started) / 1_000_000);
        try {
            Files.createDirectories(output.getParent());
            Files.writeString(output, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result) + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (Exception failure) {
            LOG.error("评测结果写入失败，类型={}", failure.getClass().getSimpleName());
            System.exit(3);
        }
        LOG.info("Provider 定向评测结束，成功={}", success);
        if (!success) System.exit(2);
    }

    /** 文件作为完整的合成来源输入；此处不模拟真实索引召回，也不截断资料以改善候选成绩。 */
    private static NaturalPromptInput input(JsonNode item, String version) throws Exception {
        String raw = item.path("rawPrompt").asText("");
        if (raw.isBlank()) throw new IllegalArgumentException("原始提示词为空");
        List<FileSnippet> files = new ArrayList<>();
        for (JsonNode file : item.path("context").path("files")) {
            String path = file.path("path").asText("");
            if (path.isBlank() || !file.path("content").isTextual()) throw new IllegalArgumentException("材料格式无效");
            files.add(new FileSnippet(path, file.path("language").asText("text"),
                    file.path("content").asText(), "", false));
        }
        var context = new ContextSnapshot(item.path("context").path("customDescription").asText(""),
                List.of(), List.of(), files.stream().map(FileSnippet::path).toList(), files,
                List.of(), List.of(), "eval-direct-materials-v1");
        List<PlanAnswer> answers = new ArrayList<>();
        for (JsonNode answer : item.path("actualAnswers")) answers.add(JSON.treeToValue(answer, PlanAnswer.class));
        List<ConversationMessage> conversation = new ArrayList<>();
        for (JsonNode message : item.path("conversation")) conversation.add(JSON.treeToValue(message, ConversationMessage.class));
        return new NaturalPromptInput(raw, context, answers, conversation, List.of(), null, null, version);
    }

    /** 使用 deepseek 路由键复用生产参数组装；真实密钥及外部地址仅由本机 relay 管理。 */
    private static OpenAiCompatibleNaturalPromptProvider provider(int port) {
        var route = new OpenAiCompatibleRouteProperties();
        route.setProviderName("deepseek");
        route.setEndpoint(URI.create("http://127.0.0.1:" + port + "/chat/completions"));
        route.setApiKey("fixture");
        route.setModel("deepseek-flash");
        route.setModels(List.of("deepseek-flash"));
        var properties = new OpenAiCompatibleProperties();
        properties.setMultiProviderEnabled(true);
        properties.setDefaultProvider("deepseek");
        properties.setProviders(Map.of("deepseek", route));
        properties.setTemperature(0.2);
        properties.setMaxTokens(8192);
        properties.setJsonResponseFormatEnabled(true);
        properties.setConnectTimeout(Duration.ofSeconds(5));
        properties.setReadTimeout(Duration.ofSeconds(240));
        var transport = new SimpleClientHttpRequestFactory();
        transport.setConnectTimeout(Duration.ofSeconds(5));
        transport.setReadTimeout(Duration.ofSeconds(240));
        return new OpenAiCompatibleNaturalPromptProvider(RestClient.builder().requestFactory(transport).build(),
                JSON, properties, null);
    }

    /** 复用生产异常分类，允许 Provider 自带的一次协议修复；入口本身不增加质量重试。 */
    private static ProviderException invalid(String message) {
        return new ProviderException(ProviderFailureType.INVALID_RESPONSE, message, true);
    }
}
