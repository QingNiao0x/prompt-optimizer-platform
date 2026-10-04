package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.template.service.impl.PromptTemplateRegistryImpl;
import com.promptoptimizer.provider.domain.PlanningProviderRequest;
import com.promptoptimizer.provider.infrastructure.MockPromptPlanningProvider;
import org.junit.jupiter.api.Test;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 锁定用户交付目标，防止受众身份、否定要求和材料术语把任务误分为科研或软件测试。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class TaskDeliveryProfileTest {
    private final PromptTemplateRegistryImpl templates = new PromptTemplateRegistryImpl();

    @Test
    void shouldKeepNewsAndTeachingGoalsDespiteBackgroundAndExcludedWork() {
        assertThat(templates.infer("为研究生写一篇平台内测新闻稿，只输出标题和正文，不提供研究设计。"))
                .isEqualTo(TemplateCode.GENERAL);
        assertThat(templates.infer("为六年级设计分数比较教案，不附软件测试用例，只交付课程、练习和答案。"))
                .isEqualTo(TemplateCode.GENERAL);
        assertThat(templates.infer("请为初一学生设计一节四十五分钟的分数大小比较课。交付教师教案、学生练习页和单独答案页。不追加软件测试要求。"))
                .isEqualTo(TemplateCode.GENERAL);
    }

    @Test
    void shouldUseTaskSpecificGuidanceWithoutExpandingExplicitDeliverables() {
        var translation = templates.resolve(TemplateCode.AUTO, "翻译以下内容为英语，仅输出译文，保留原段落。原文：明天开会。");
        assertThat(translation.outputGuidance()).contains("译文").doesNotContain("执行步骤", "判断依据");
        var news = templates.resolve(TemplateCode.AUTO, "写一篇新闻稿，只输出标题和正文，不要附解释。");
        assertThat(news.outputGuidance()).contains("新闻稿").doesNotContain("实现方案", "执行步骤");
    }

    @Test
    void shouldUseWritingQuestionsForNewsAboutResearchAndLoginFeatures() {
        var plan = new MockPromptPlanningProvider().plan(new PlanningProviderRequest(
                "为研究生写一篇平台内测新闻稿，介绍已有登录功能；面向研究生，字数六百字。", "", List.of()));
        assertThat(plan.questions()).noneMatch(question -> question.id().startsWith("research-")
                || question.id().startsWith("software-"));
        assertThat(plan.summary()).contains("交付的内容");
    }

    @Test
    void shouldPreserveResearchAndExplicitSoftwareTemplateChoices() {
        assertThat(templates.infer("研究广东省死亡率长期趋势，并提供分析代码。"))
                .isEqualTo(TemplateCode.RESEARCH_ANALYSIS);
        assertThat(templates.resolve(TemplateCode.BUG_FIX, "为研究生写新闻稿").code())
                .isEqualTo(TemplateCode.BUG_FIX);
        assertThat(templates.infer("修复订单接口异常"))
                .isEqualTo(TemplateCode.BUG_FIX);
        assertThat(templates.infer("请为调查录入系统制定地区匹配的实现方案，覆盖查询失败与取消分支。"))
                .isEqualTo(TemplateCode.FEATURE_DEVELOPMENT);
    }
}
