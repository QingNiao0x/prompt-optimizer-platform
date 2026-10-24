package com.promptoptimizer.provider.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.context.domain.FileSnippet;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 资料状态只反映本次允许的输入，不把没上传文件解释成用户没有资料。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class MaterialVisibilityTest {
    @Test
    void missingProjectContextStaysExecutionOnlyUntilTheUserStatesAbsence() {
        var unseen = MaterialVisibility.from(input("请查询全球使用AI最多的职业/行业", null, List.of()));
        assertThat(unseen.requestMaterials()).isEqualTo(MaterialVisibility.State.EXECUTION_ONLY);
        assertThat(unseen.sources()).extracting(MaterialVisibility.SourceState::state)
                .containsExactly(MaterialVisibility.State.USER_STATED);

        var absent = MaterialVisibility.from(input("没有相关资料，请写一份社区活动建议。", null, List.of()));
        assertThat(absent.requestMaterials()).isEqualTo(MaterialVisibility.State.EXECUTION_ONLY);
        assertThat(absent.sources().getFirst().state()).isEqualTo(MaterialVisibility.State.USER_ABSENT);
    }

    @Test
    void attachedMaterialAndBoundAnswersKeepTheirOwnStates() {
        var context = new ContextSnapshot("周末活动", List.of(), List.of(), List.of(),
                List.of(new FileSnippet("note.txt", "text", "报名人数已满", "", false)), List.of(), List.of(), "1");
        var visibility = MaterialVisibility.from(input("请写活动建议", context,
                List.of(new PlanAnswer("place", "地点？", "青禾中心二层会议室"))));
        assertThat(visibility.requestMaterials()).isEqualTo(MaterialVisibility.State.VISIBLE_NOW);
        assertThat(visibility.sources()).extracting(MaterialVisibility.SourceState::sourceId, MaterialVisibility.SourceState::state)
                .contains(org.assertj.core.groups.Tuple.tuple("context.fileSnippets.0", MaterialVisibility.State.VISIBLE_NOW),
                        org.assertj.core.groups.Tuple.tuple("actualAnswers.0", MaterialVisibility.State.USER_OPEN));
    }

    private static NaturalPromptInput input(String raw, ContextSnapshot context, List<PlanAnswer> answers) {
        return new NaturalPromptInput(raw, context, answers, List.of(), List.of(), "fixture");
    }
}
