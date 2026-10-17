import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.enhancement.service.impl.EffectiveTaskViewFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** 只从冻结任务和已保存的实际题答构建白名单，不发送模型请求或创建业务数据。 */
public final class TaskViewFixture {
    /** 第五参数--scope导出有效原文、任务视图及确认状态的成对约束，避免分别匹配串任务。 */
    public static void main(String[] args) throws Exception {
        if (args.length < 4 || args.length > 5 || args.length == 5 && !"--scope".equals(args[4]))
            throw new IllegalArgumentException("cases, caseId, actualAnswers, output, optional --scope required");
        var json = new ObjectMapper();
        var cases = json.readTree(Path.of(args[0]).toFile());
        var sample = java.util.stream.StreamSupport.stream(cases.spliterator(), false)
                .filter(item -> args[1].equals(item.path("id").asText())).findFirst().orElseThrow();
        List<PlanAnswer> answers = json.readerForListOf(PlanAnswer.class).readValue(Path.of(args[2]).toFile());
        if (!"natural-plan".equals(sample.path("mode").asText()) && !answers.isEmpty())
            throw new IllegalArgumentException("Direct tasks cannot receive fabricated Plan answers");
        var view = EffectiveTaskViewFactory.create(sample.path("rawPrompt").asText(), TemplateCode.AUTO,
                answers, List.of(), sample.path("includeExamples").asBoolean());
        Object output = view.modelInput();
        if (args.length == 5) {
            var scope = json.createObjectNode();
            scope.put("caseId", sample.path("id").asText());
            scope.put("rawPrompt", view.effectivePrompt());
            scope.put("planConfirmed", !answers.isEmpty());
            var format=com.promptoptimizer.enhancement.domain.PromptFormat.valueOf(sample.path("promptFormat").asText());
            scope.put("promptFormat", format.name());
            scope.set("rewriteStrategy", json.valueToTree(com.promptoptimizer.provider.domain.PromptRewriteStrategy.forTask(view, format)));
            scope.set("taskView", json.valueToTree(view.modelInput()));
            output = scope;
        }
        Files.writeString(Path.of(args[3]), json.writerWithDefaultPrettyPrinter().writeValueAsString(output));
    }
}
