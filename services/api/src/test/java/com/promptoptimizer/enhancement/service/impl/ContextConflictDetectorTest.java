package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.context.domain.FileSnippet;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ContextConflictDetectorTest {
    @Test
    void shouldNotLetAnEngineeringLookupDirectiveReopenTheSelectedThreshold() {
        var context = new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(
                new FileSnippet("docs/现行审批.md", "markdown", "审批金额阈值：30000元", "", false),
                new FileSnippet("docs/候选审批方案.md", "markdown", "审批金额阈值：50000元", "", false),
                new FileSnippet("docs/二次收到的审批意见.md", "markdown", "审批金额阈值：80000元", "", false)
        ), List.of(), List.of(), "v1");
        String question = "资料对“审批金额阈值”存在不同取值：docs/现行审批.md（30000元）与 docs/候选审批方案.md（50000元）。请确认本次采用哪一项。";
        String answer = "本次采用50000元阈值；仅金额严格大于50000元才财务复核，等于50000元不复核；30000元不再作为本次规则。只交付最小修改方案，不直接修改文件，其他审批行为兼容；未提供的工程细节先核查。";
        var answers = List.of(new PlanAnswer("context-conflict-1", question, answer));
        assertThat(new ContextConflictDetector().detect(context, answers, "订单审批"))
                .singleElement().asString().contains("50000元", "80000元").doesNotContain("30000元");
        // 具体业务未决仍需保留，不能因为同题含明确的阈值就把生效时间也当成已定。
        assertThat(PlanAnswerSemantics.unresolved("本次采用50000元；生效日期尚未确定。" )).isTrue();
    }

    @Test
    void shouldNotReintroduceTestFixtureThroughConflictWarnings() {
        var context = new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(
                new FileSnippet("docs/统计日志规则.txt", "text", "统计口径：按上海时区", "", false),
                new FileSnippet("tests/fixtures/统计样例.txt", "text", "统计口径：按UTC时区", "", false)
        ), List.of(), List.of(), "v1");
        assertThat(new ContextConflictDetector().detect(context, List.of(), "设计统计日志模块")).isEmpty();
        assertThat(new ContextConflictDetector().detect(context, List.of(), "修复统计口径测试用例"))
                .singleElement().asString().contains("统计口径");
    }

    @Test
    void shouldExposeNewCrossFileConflictUnlessUserAlreadyResolvedTheSameField() {
        ContextSnapshot context = new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(
                new FileSnippet("docs/现行规则.txt", "text", "审批阈值：50000", "", false),
                new FileSnippet("docs/新方案.txt", "text", "审批阈值：100000", "", false)
        ), List.of(), List.of(), "v1");
        ContextConflictDetector detector = new ContextConflictDetector();
        assertThat(detector.detect(context, List.of())).singleElement()
                .asString().contains("审批阈值", "现行规则.txt", "新方案.txt", "请确认");
        assertThat(detector.detect(context, List.of(new PlanAnswer("threshold", "审批阈值采用哪一个？", "50000"))))
                .isNotEmpty();
        // 只有服务端签发且绑定了本次两个取值的冲突题，才有资格消除旧冲突。
        String question = detector.detect(context, List.of()).getFirst();
        assertThat(detector.detect(context, List.of(new PlanAnswer("context-conflict-1", question, "50000"))))
                .isEmpty();
    }
}
