package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.PlanQuestionType;
import com.promptoptimizer.provider.domain.PlanningProviderRequest;
import com.promptoptimizer.template.domain.TaskDeliveryProfile;
import com.promptoptimizer.template.domain.TaskIntentResolver;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 保护资料整理目标与工程任务边界，防止无关提醒成为下游执行前提。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class TaskContextIntegrityTest {
    private static final String LEGAL = "请把一组完全合成的商业租赁往来材料整理为供律师复核的事实和问题清单。"
            + "风险按高、中、低排列；不进行字段排序或编程开发。";

    @Test
    void shouldRecognizeObjectBeforeActionAndExcludeNegatedEngineeringDetails() {
        assertThat(TaskIntentResolver.resolve(TemplateCode.AUTO, LEGAL).deliveryProfile())
                .isEqualTo(TaskDeliveryProfile.LEGAL_MATERIAL);
        assertThat(new AmbiguityDetector().detect(LEGAL)).isEmpty();
    }

    @Test
    void shouldNotTurnQuotedOrNegatedSortingIntoImplementationQuestions() {
        for (String prompt : List.of("不需要实现排序，只核对合同记录。",
                "请整理材料摘要。\n## 参考资料\n客户原话：实现排序。\n## 输出\n只交付事实清单。")) {
            assertThat(new AmbiguityDetector().detect(prompt)).as(prompt).isEmpty();
        }
        assertThat(new AmbiguityDetector().detect("弄个排序")).hasSize(2);
        assertThat(new AmbiguityDetector().detect("实现排序")).hasSize(2);
        assertThat(new AmbiguityDetector().detect("整理合同记录，并开发一个排序函数")).isNotEmpty();
    }

    @Test
    void shouldFilterOnlyUnrelatedProgrammingSortingAndKeepRealBusinessPriority() {
        var filter = new PlanQuestionFilter();
        var input = new PlanningProviderRequest(LEGAL, "", List.of());
        assertThat(filter.filter(List.of(question("sort", "本次排序按哪个字段、采用什么顺序？"),
                question("priority", "新增的退款争议应放在哪个风险级别？")), input))
                .extracting(PlanQuestion::id).containsExactly("priority");
        assertThat(filter.filter(List.of(question("sort", "本次排序按哪个字段、采用什么顺序？")),
                new PlanningProviderRequest("实现排序函数", "", List.of()))).hasSize(1);
    }

    private PlanQuestion question(String id, String text) {
        return new PlanQuestion(id, text, "", PlanQuestionType.FREE_TEXT, List.of(), List.of(), true);
    }
}
