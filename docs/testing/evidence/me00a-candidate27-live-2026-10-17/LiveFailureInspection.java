import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.PermissionPolicyInput;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.enhancement.service.impl.EffectiveTaskViewFactory;
import com.promptoptimizer.enhancement.service.impl.OptimizationResultAssembler;
import com.promptoptimizer.policy.service.impl.ConstraintCompleterImpl;
import com.promptoptimizer.provider.domain.EnhancementProviderResponse;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException;
import com.promptoptimizer.provider.domain.RequirementBoundaryContract;
import com.promptoptimizer.provider.domain.TaskEnhancementAssessment;
import com.promptoptimizer.template.domain.TaskIntentResolver;
import com.promptoptimizer.template.service.impl.PromptTemplateRegistryImpl;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** 本批结束后只读回放冻结生产类，分开观测新增追加、边界误拒与模型声明，不调用供应商。 */
public final class LiveFailureInspection {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path PUBLIC = Path.of("docs/testing/evidence/me00a-candidate27-live-2026-10-17");
    private static final Path LOCAL = Path.of("tmp/me00a-candidate27-live-20261017/real");
    private static final Path TRACES = Path.of("tmp/me00a-20261012/traces");

    /** 保存原句与冻结方法的实际结果，不从异常枚举推断全部语义校验均已执行。 */
    public static void main(String[] args) throws Exception {
        if (traceCount() != 345) throw new IllegalStateException("Ledger changed before offline observation");
        var append = new ArrayList<Object>();
        for (int number : new int[]{334, 337, 339}) {
            String id = number == 334 ? "Q05-NATURAL" : number == 337 ? "Q05-PARTIAL-SCORING" : "Q05-PARTIAL-SCORING-R2";
            JsonNode input = JSON.readTree(LOCAL.resolve(id + "/input.json").toFile());
            List<PlanAnswer> answers = JSON.readerForListOf(PlanAnswer.class).readValue(input.path("planConfirmation").path("answers"));
            var task = EffectiveTaskViewFactory.create(input.path("rawPrompt").asText(), TemplateCode.AUTO, answers, List.of(), false);
            var sections = sections(response(number));
            var original = new EnumMap<>(sections);
            var integrate = Class.forName("com.promptoptimizer.enhancement.service.impl.PlanDecisionIntegrator")
                    .getDeclaredMethod("integrate", Map.class, com.promptoptimizer.enhancement.domain.EffectiveTaskView.class);
            integrate.setAccessible(true); integrate.invoke(null, sections, task);
            var changes = new ArrayList<Object>();
            sections.forEach((type, value) -> {
                String before = original.get(type).content(), after = value.content();
                if (!after.equals(before)) changes.add(Map.of("section", type.name(), "before", before, "after", after,
                        "appended", after.startsWith(before) ? after.substring(before.length()) : "NON_APPEND_CHANGE"));
            });
            append.add(Map.of("trace", number, "caseId", id, "changes", changes, "taskView", task.modelInput()));
        }
        JsonNode input = JSON.readTree(LOCAL.resolve("Q05-PARTIAL-SCORING/input.json").toFile());
        String raw = input.path("rawPrompt").asText();
        List<PlanAnswer> answers = JSON.readerForListOf(PlanAnswer.class).readValue(input.path("planConfirmation").path("answers"));
        var task = EffectiveTaskViewFactory.create(raw, TemplateCode.AUTO, answers, List.of(), false);
        var boundary = RequirementBoundaryContract.from(raw, task.confirmedDecisions(), List.of());
        var validations = new ArrayList<Object>();
        for (int number : new int[]{335, 336, 338}) {
            JsonNode model = response(number);
            for (var section : sections(model).values()) {
                try { boundary.validate(section.content(), "sections." + section.type()); }
                catch (ProviderResponseValidationException failure) {
                    validations.add(Map.of("trace", number, "kind", "INDEPENDENT_BOUNDARY_REPLAY", "reason", failure.getReason().name(),
                            "field", failure.getField(), "source", section.content()));
                }
            }
            // 缺presentation只调用原生产归一化；不补USER_DECISION、不改正文、不清空任何问题。
            JsonNode normalized = model.deepCopy();
            var provider = new com.promptoptimizer.provider.infrastructure.openai.OpenAiCompatiblePromptEnhancementProvider(
                    org.springframework.web.client.RestClient.create(), JSON,
                    new com.promptoptimizer.provider.infrastructure.openai.OpenAiCompatibleProperties());
            var normalize = provider.getClass().getDeclaredMethod("normalizeMissingPresentation", JsonNode.class);
            normalize.setAccessible(true); normalize.invoke(provider, normalized);
            var assessment = JSON.treeToValue(normalized.path("assessment"), TaskEnhancementAssessment.class);
            List<String> ambiguities = JSON.readerForListOf(String.class).readValue(model.path("ambiguities"));
            String assessmentResult = "ACCEPTED_BY_THIS_CHECK";
            try { assessment.validateModelDecisions(ambiguities); }
            catch (ProviderResponseValidationException failure) { assessmentResult = failure.getReason().name(); }
            var response = new EnhancementProviderResponse(List.copyOf(sections(model).values()), "offline-live", "deepseek-flash", false,
                    ambiguities, List.of(), assessment);
            var empty = new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "EMPTY");
            String result = "ACCEPTED_BY_ASSEMBLER"; String field = ""; String site = "";
            try {
                new OptimizationResultAssembler().assemble(response, empty,
                        new PromptTemplateRegistryImpl().resolve(TemplateCode.AUTO, raw), List.of(), answers, true,
                        new ConstraintCompleterImpl().completeForTask(empty, PermissionPolicyInput.empty(), true,
                                TaskIntentResolver.resolve(TemplateCode.AUTO, raw)), false, 0, raw, List.of(), List.of(), task);
            } catch (ProviderResponseValidationException failure) {
                result = failure.getReason().name(); field = failure.getField();
                site = java.util.Arrays.stream(failure.getStackTrace()).limit(4).map(StackTraceElement::toString)
                        .collect(java.util.stream.Collectors.joining("\n"));
            }
            validations.add(Map.of("trace", number, "kind", "ASSEMBLER_REPLAY", "result", result, "field", field,
                    "failureSite", site, "independentAssessmentCheck", assessmentResult,
                    "missingPresentationNormalizedByProduction", !model.path("assessment").has("presentation"), "originalModelUnchanged", true));
        }
        if (traceCount() != 345) throw new IllegalStateException("Offline observation changed ledger");
        Files.writeString(PUBLIC.resolve("live-frozen-method-observations.json"), JSON.writerWithDefaultPrettyPrinter()
                .writeValueAsString(Map.of("kind", "POST_BATCH_OFFLINE_FROZEN_PRODUCTION_REPLAY", "modelCalls", 0,
                        "integratorChanges", append, "validationReplays", validations)), java.nio.file.StandardOpenOption.CREATE_NEW);
        System.out.println("Saved frozen production observations; 0 new model calls");
    }

    /** 只读固定编号原件，不提取围栏、不编辑模型返回的正文。 */
    private static JsonNode response(int number) throws Exception {
        JsonNode trace = JSON.readTree(TRACES.resolve(number + ".json").toFile());
        return JSON.readTree(trace.path("response").path("choices").get(0).path("message").path("content").asText());
    }

    private static EnumMap<PromptSectionType, PromptSection> sections(JsonNode response) throws Exception {
        var result = new EnumMap<PromptSectionType, PromptSection>(PromptSectionType.class);
        for (var node : response.path("sections")) { var section = JSON.treeToValue(node, PromptSection.class); result.put(section.type(), section); }
        return result;
    }

    private static long traceCount() throws Exception {
        try (var files = Files.list(TRACES)) { return files.filter(path -> path.getFileName().toString().matches("[0-9]+\\.json")).count(); }
    }
}
