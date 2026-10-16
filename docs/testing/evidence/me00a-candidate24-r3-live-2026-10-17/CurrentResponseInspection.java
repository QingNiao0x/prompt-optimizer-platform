import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.provider.domain.TaskEnhancementAssessment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;

/** 对325原件执行冻结业务类的只读覆盖判断，输出诊断证据；不启动服务或访问模型。 */
public class CurrentResponseInspection {
    /** 保留原始声明，只观测既有判断，不修改增强响应、复制正文或验收阈值。 */
    public static void main(String[] args) throws Exception {
        var json = new ObjectMapper();
        var trace = json.readTree(Path.of("tmp/me00a-20261012/traces/325.json").toFile());
        var raw = json.readTree(trace.path("response").path("choices").get(0).path("message").path("content").asText());
        var sections = new EnumMap<PromptSectionType, PromptSection>(PromptSectionType.class);
        for (var node : raw.path("sections")) {
            var section = json.treeToValue(node, PromptSection.class);
            sections.put(section.type(), section);
        }
        var integrator = Class.forName("com.promptoptimizer.enhancement.service.impl.PlanDecisionIntegrator");
        var scoring = integrator.getDeclaredMethod("currentScoringDeclaration", TaskEnhancementAssessment.Gap.class);
        scoring.setAccessible(true);
        var declarations = new ArrayList<Object>();
        for (var node : raw.path("assessment").path("gaps")) {
            var gap = json.treeToValue(node, TaskEnhancementAssessment.Gap.class);
            declarations.add(java.util.Map.of("kind", gap.kind().name(), "missing", gap.missing(),
                    "completion", gap.completion(), "recognizedCurrentScoringDeclaration", scoring.invoke(null, gap)));
        }
        var covered = integrator.getDeclaredMethod("delegatedQuestionCovered", java.util.Map.class, String.class);
        covered.setAccessible(true);
        var questions = new ArrayList<Object>();
        var cases = json.readTree(Path.of("docs/testing/evidence/me00a-candidate24-r3-live-2026-10-17/cases.json").toFile());
        for (var answer : cases.get(0).path("planAnswers")) {
            String question = answer.path("question").asText();
            questions.add(java.util.Map.of("question", question, "recognizedCoverageInOriginalModelSections", covered.invoke(null, sections, question)));
        }
        var result = java.util.Map.of("sourceTrace", 325, "kind", "OFFLINE_FROZEN_METHOD_OBSERVATION",
                "modelCalls", 0, "responseModified", false, "scoringDeclarations", declarations, "delegatedCoverage", questions);
        var output = Path.of("docs/testing/evidence/me00a-candidate24-r3-live-2026-10-17/frozen-method-observations.json");
        Files.writeString(output, json.writerWithDefaultPrettyPrinter().writeValueAsString(result), java.nio.file.StandardOpenOption.CREATE_NEW);
        System.out.println("Frozen method observations saved; 0 model calls");
    }
}
