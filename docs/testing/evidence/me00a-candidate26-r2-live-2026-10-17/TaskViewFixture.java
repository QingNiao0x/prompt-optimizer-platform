import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.enhancement.service.impl.EffectiveTaskViewFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** 从冻结案例与真实题答构建精确白名单；不发请求、不修改生产状态。 */
public final class TaskViewFixture {
    /** 输出只含合成题目的任务视图，路径由本机评测调用者指定，不承接网页输入。 */
    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("cases, caseId, answers, output required");
        var json = new ObjectMapper();
        var cases = json.readTree(Path.of(args[0]).toFile());
        var sample = java.util.stream.StreamSupport.stream(cases.spliterator(), false)
                .filter(item -> args[1].equals(item.path("id").asText())).findFirst().orElseThrow();
        List<PlanAnswer> answers = json.readerForListOf(PlanAnswer.class).readValue(Path.of(args[2]).toFile());
        var view = EffectiveTaskViewFactory.create(sample.path("rawPrompt").asText(), TemplateCode.AUTO,
                answers, List.of(), sample.path("enhancement").path("includeExamples").asBoolean());
        Files.writeString(Path.of(args[3]), json.writerWithDefaultPrettyPrinter().writeValueAsString(view.modelInput()));
    }
}
