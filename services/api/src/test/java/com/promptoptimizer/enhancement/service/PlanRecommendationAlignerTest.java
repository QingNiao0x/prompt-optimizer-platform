package com.promptoptimizer.enhancement.service;

import com.promptoptimizer.enhancement.domain.PlanOption;
import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.PlanQuestionType;
import com.promptoptimizer.enhancement.domain.PlanningContextDigest;
import com.promptoptimizer.enhancement.dto.ConversationMessage;
import com.promptoptimizer.provider.domain.PlanningProviderRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlanRecommendationAlignerTest {

    @Test
    void shouldRecommendTheOptionThatMatchesExistingProjectPractice() {
        PlanQuestion question = question(List.of(
                option("plus", "统一使用 MyBatis-Plus", "所有 Mapper 继承 BaseMapper，分页使用 MyBatis-Plus 分页插件", true),
                option("plain", "仅使用 MyBatis", "使用原生 MyBatis，不引入 MyBatis-Plus", false),
                option("mixed", "混合使用", "简单 CRUD 用 MyBatis-Plus，复杂查询用 MyBatis XML", false)
        ));
        var digest = new PlanningContextDigest("",
                List.of("Java 21"),
                List.of("maven:mybatis-plus@3.5.17"),
                List.of(),
                List.of("AdminAnalyticsMapper.xml：复杂查询使用 MyBatis XML，单表访问继承 BaseMapper"),
                "COMPLETE", 1, List.of());

        PlanQuestion aligned = PlanRecommendationAligner.align(question, request("修复统计查询", digest, List.of()));

        assertThat(aligned.options()).filteredOn(PlanOption::recommended).extracting(PlanOption::id)
                .containsExactly("mixed");
    }

    @Test
    void shouldKeepModelRecommendationWhenUserIntentAndProjectEvidenceDisagree() {
        PlanQuestion question = question(List.of(
                option("plus", "统一使用 MyBatis-Plus", "所有 Mapper 继承 BaseMapper", true),
                option("mixed", "混合使用", "复杂查询用 MyBatis XML", false)
        ));
        var digest = new PlanningContextDigest("", List.of(), List.of(), List.of(),
                List.of("复杂查询使用 MyBatis XML"), "COMPLETE", 1, List.of());

        PlanQuestion aligned = PlanRecommendationAligner.align(
                question,
                request("重构持久化", digest, List.of(new ConversationMessage("user", "请统一使用 MyBatis-Plus")))
        );

        assertThat(aligned.options()).filteredOn(PlanOption::recommended).extracting(PlanOption::id)
                .containsExactly("plus");
    }

    private static PlanningProviderRequest request(
            String prompt,
            PlanningContextDigest digest,
            List<ConversationMessage> history
    ) {
        return new PlanningProviderRequest(prompt, "", history, digest);
    }

    private static PlanQuestion question(List<PlanOption> options) {
        return new PlanQuestion("persistence", "使用 MyBatis 还是 MyBatis-Plus？", "",
                PlanQuestionType.SINGLE_CHOICE, options, List.of(), true);
    }

    private static PlanOption option(String id, String label, String description, boolean recommended) {
        return new PlanOption(id, label, description, label + "。" + description, recommended);
    }
}
