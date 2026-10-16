import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.*;
import com.promptoptimizer.enhancement.dto.PermissionPolicyInput;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.enhancement.service.impl.OptimizationResultAssembler;
import com.promptoptimizer.policy.service.impl.ConstraintCompleterImpl;
import com.promptoptimizer.provider.domain.*;
import com.promptoptimizer.template.domain.TaskIntentResolver;
import com.promptoptimizer.template.service.impl.PromptTemplateRegistryImpl;
import java.nio.file.*;
import java.util.List;

/** 无网络、无数据库地重组真实响应；输出明确标为离线派生物，不冒充新作品。 */
public final class ReplayCandidate22 {
    /** 从当前候选固定原件重放完整组装，记录每个来源，不补入任何候选答案。 */
    public static void main(String[] args) throws Exception {
        var mapper = new ObjectMapper();
        Path input = Path.of(args[0]);
        Path output = Path.of(args[1]);
        JsonNode fixture = mapper.readTree(input.toFile());
        String raw = fixture.path("rawPrompt").asText();
        List<PlanAnswer> answers = mapper.readerForListOf(PlanAnswer.class).readValue(fixture.path("actualAnswers"));
        var value = fixture.path("response");
        ObjectNode assessment = value.path("assessment").deepCopy();
        if (!assessment.has("presentation")) assessment.put("presentation", "STRUCTURED");
        var response = new EnhancementProviderResponse(
                mapper.readerForListOf(PromptSection.class).readValue(value.path("sections")),
                "offline-replay", "deepseek-flash", false,
                mapper.readerForListOf(String.class).readValue(value.path("ambiguities")), List.of(),
                mapper.treeToValue(assessment, TaskEnhancementAssessment.class));
        var context = new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "EMPTY");
        var result = new OptimizationResultAssembler().assemble(response, context,
                new PromptTemplateRegistryImpl().resolve(TemplateCode.AUTO, raw), List.of(), answers, true,
                new ConstraintCompleterImpl().completeForTask(context, PermissionPolicyInput.empty(), true,
                        TaskIntentResolver.resolve(TemplateCode.AUTO, raw)),
                false, 0, raw, List.of(), List.of());
        ObjectNode saved = mapper.createObjectNode();
        saved.put("type", "OFFLINE_DERIVED_ASSEMBLY_NOT_REAL_MODEL_CALL");
        saved.put("source", fixture.path("source").asText());
        saved.put("sourceSha256", fixture.path("sourceSha256").asText());
        saved.put("realModelCalls", 0);
        saved.set("actualAnswers", fixture.path("actualAnswers"));
        saved.set("result", mapper.valueToTree(result));
        Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(saved) + "\n",
                StandardOpenOption.CREATE_NEW);
        Files.writeString(output.resolveSibling("copy-derived-offline.md"), result.optimizedPrompt(),
                StandardOpenOption.CREATE_NEW);
    }
}
