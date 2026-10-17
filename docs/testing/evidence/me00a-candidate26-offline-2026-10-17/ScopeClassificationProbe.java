import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.enhancement.service.impl.EffectiveTaskViewFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** 用同一探针比较父／当前冻结运行，回放二十五的三个独立状态失败；不调用模型。 */
public class ScopeClassificationProbe {
    /** 输出仅包含固定合成回答和状态，不能把本结果替代真实Plan或下游质量门禁。 */
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !List.of("before", "after").contains(args[0])) throw new IllegalArgumentException("Explicit phase required");
        String question = "4 类任务具体指哪四类？";
        var observations = List.of("如果以后批准，再提出拟议设计。", "只委托评分设计，分类由我决定。", "评分量尺以后确认。")
                .stream().map(answer -> {
                    var task = EffectiveTaskViewFactory.create("撰写中文研究设计的方法部分，说明准确性、完整性、清晰度与可执行性评价以及分歧处理。",
                            TemplateCode.AUTO, List.of(new PlanAnswer("q", question, answer)), List.of(), false);
                    boolean gate = task.confirmedDecisions().stream().allMatch(value -> value.scope().name().equals("UNRESOLVED"))
                            && task.pendingDecisions().stream().anyMatch(value -> value.questionId().equals("q") && value.question().equals(question))
                            && task.modelInput().pendingDecisions().stream().noneMatch(value -> value.designDelegated());
                    return Map.of("answer", answer, "scopes", task.confirmedDecisions().stream().map(value -> value.scope().name()).toList(),
                            "pendingCount", task.pendingDecisions().size(), "modelInput", task.modelInput(), "independentStateGatePass", gate);
                }).toList();
        var output = Path.of("docs/testing/evidence/me00a-candidate26-offline-2026-10-17/scope-classification-" + args[0] + ".json");
        Files.writeString(output, new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "kind", "SAME_PROBE_FROZEN_RUNTIME_COMPARISON", "phase", args[0], "realModelCalls", 0, "observations", observations)) + "\n",
                java.nio.file.StandardOpenOption.CREATE_NEW);
        System.out.println("Three fixed state observations saved; zero model calls");
    }
}
