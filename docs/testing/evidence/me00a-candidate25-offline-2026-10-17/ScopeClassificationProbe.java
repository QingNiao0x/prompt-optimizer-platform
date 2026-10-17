import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.enhancement.service.impl.EffectiveTaskViewFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 只读比较父候选与当前候选的短答分类；不修改状态、不启动服务或访问模型。 */
public class ScopeClassificationProbe {
    /** 固定合成反例和输出位置，不能用本诊断批准新确认或替代真实Plan验收。 */
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !List.of("before", "after").contains(args[0])) throw new IllegalArgumentException("Explicit probe phase required");
        var json = new ObjectMapper();
        var observations = new ArrayList<Object>();
        for (String answer : List.of("如果以后批准，再提出拟议设计。", "只委托评分设计，分类由我决定。", "评分量尺以后确认。")) {
            var task = EffectiveTaskViewFactory.create("撰写中文研究设计的方法部分，说明准确性、完整性、清晰度与可执行性评价以及分歧处理。",
                    TemplateCode.AUTO, List.of(new PlanAnswer("q", "4 类任务具体指哪四类？", answer)), List.of(), false);
            observations.add(java.util.Map.of("answer", answer, "scopes", task.confirmedDecisions().stream().map(value -> value.scope().name()).toList(),
                    "pendingCount", task.pendingDecisions().size(), "fullCurrentDesignDelegated", task.executionRequirements().stream()
                    .anyMatch(value -> value.subject().equals("委托要求") && value.statement().startsWith("请根据原始研究目标提出可开展的推荐设计并说明适用条件"))));
        }
        var output = Path.of("docs/testing/evidence/me00a-candidate25-offline-2026-10-17/scope-classification-" + args[0] + ".json");
        Files.writeString(output, json.writerWithDefaultPrettyPrinter().writeValueAsString(java.util.Map.of(
                "kind", "OUT_OF_SCOPE_STATE_CLASSIFICATION_OBSERVATION", "phase", args[0], "realModelCalls", 0, "observations", observations)),
                java.nio.file.StandardOpenOption.CREATE_NEW);
        System.out.println("Three bounded scope observations saved; 0 model calls");
    }
}
