package com.promptoptimizer.provider.domain;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.ProviderMetadata;
import com.promptoptimizer.enhancement.dto.ConversationMessage;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 验证无成果请求的逐字承载，不能用一个片段冒充全部实际题答或把助手建议当用户确认。 */
class NaturalPreservedRequestTest {
    @Test
    void preservesRawRoleConditionsMaterialsAndEveryActualAnswerWithoutAddingDeliverable() {
        String raw = "你是高级开发工程师。\n先熟悉项目。\n之后仅在我明确提出需求时修改。";
        var context = new ContextSnapshot("只有说明片段，未提供运行结果。", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "test");
        var input = new NaturalPromptInput(raw, context,
                List.of(new PlanAnswer("a", "是否已经核对？", "不知道。"), new PlanAnswer("b", "记录是否存在？", "不知道。")),
                List.of(new ConversationMessage("assistant", "建议再写一份说明，用户未确认。")), List.of(), null, context).withStrategyVersion("29");
        var scope = new NaturalTaskScope(List.of(new NaturalTaskScope.Action("熟悉项目", NaturalTaskScope.Timing.CURRENT, "",
                List.of(new NaturalTaskScope.Evidence("rawPrompt", "先熟悉项目")))),
                new NaturalTaskScope.Response(NaturalTaskScope.ResponseBasis.UNSPECIFIED, "", List.of())).resolve(input);
        var draft = NaturalPreservedRequest.create(input, scope, new ProviderMetadata("fixture", "fixture", false));
        assertThat(draft.optimizedPrompt()).startsWith(raw).contains("是否已经核对？\n不知道。", "记录是否存在？\n不知道。")
                .contains("此前助手讨论或建议：\n建议再写一份说明，用户未确认。");
        assertThat(draft.optimizedPrompt().split("只有说明片段", -1)).hasSize(2);
        assertThat(draft.taskUnderstanding().deliverable()).isEmpty();
        assertThat(draft.taskUnderstanding().requirements()).hasSize(3);
        assertThat(draft.taskUnderstanding().requirements().subList(1, 3)).extracting(r -> r.evidence().getFirst().sourceId())
                .containsExactly("actualAnswers.0", "actualAnswers.1");
        draft.taskUnderstanding().review().validateEnhancement(draft.taskUnderstanding(), draft.optimizedPrompt());
    }
}
