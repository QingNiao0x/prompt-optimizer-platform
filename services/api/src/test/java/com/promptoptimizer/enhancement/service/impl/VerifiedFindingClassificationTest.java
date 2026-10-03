package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.context.domain.FileSnippet;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.provider.domain.EnhancementProviderResponse;
import com.promptoptimizer.template.domain.PromptTemplate;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回放真实模型的已知职责和排除范围提醒；额外业务选择与相反证据必须仍可见。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class VerifiedFindingClassificationTest {
    private final PlanFindingClassifier classifier = new PlanFindingClassifier();

    @Test
    void shouldUseReceivedBusinessTextWithoutPromotingTestOrUnrelatedStatements() {
        String finding = "地区范围过滤由服务端负责，前端按服务端返回结果处理，不自行实现或放宽过滤。";
        String irrelevant = "开票请求由客户端实现。";
        for (String path : List.of("docs/基线匹配.md", "test-fixtures/示例.md")) {
            var response = new EnhancementProviderResponse(List.of(
                    new PromptSection(PromptSectionType.BACKGROUND, "背景", "修改基线匹配。"),
                    new PromptSection(PromptSectionType.TASK, "任务", "完善当前用户地区的基线匹配。"),
                    new PromptSection(PromptSectionType.OUTPUT, "输出", "交付实现和测试。"),
                    new PromptSection(PromptSectionType.CONSTRAINTS, "约束", "不扩大业务范围。")),
                    "test", "test", false, List.of(finding, irrelevant));
            var context = new ContextSnapshot("", List.of(), List.of(), List.of(),
                    List.of(new FileSnippet(path, "markdown", "服务端负责地区范围过滤。开票请求由客户端实现。", "基线匹配资料", false)),
                    List.of(), List.of(), "test");
            var result = new OptimizationResultAssembler().assemble(response, context,
                    new PromptTemplate(TemplateCode.FEATURE_DEVELOPMENT, "实现", "符合需求", ""),
                    List.of(), List.of(), false, List.of("不得泄露凭据"), false, 1,
                    "完善按当前用户所属地区匹配患者基线的实现。");
            assertThat(result.ambiguities()).contains(irrelevant);
            if (path.startsWith("docs/")) assertThat(result.ambiguities()).doesNotContain(finding);
            else assertThat(result.ambiguities()).contains(finding);
            assertThat(result.optimizedPrompt()).contains(finding, irrelevant);
        }
    }

    @Test
    void shouldRecognizeAnExistingServerResponsibilityAndItsClientBoundary() {
        String finding = "地区范围过滤由服务端负责，前端按服务端返回结果处理，不自行实现或放宽过滤。";
        assertThat(classifier.classify(finding, List.of("服务端负责地区范围过滤。")))
                .isEqualTo(PlanFindingClassifier.Kind.KNOWN_RULE);
        assertThat(classifier.classify(finding, List.of("服务端负责地区范围过滤。", "前端负责地区范围过滤。")))
                .isEqualTo(PlanFindingClassifier.Kind.UNRESOLVED);
        assertThat(classifier.classify(finding + "退款订单是否例外需确认。", List.of("服务端负责地区范围过滤。")))
                .isEqualTo(PlanFindingClassifier.Kind.UNRESOLVED);
    }

    @Test
    void shouldRecognizeOnlyASourcedRuleExclusionWithoutDroppingAdditionalConditions() {
        String finding = "职业信息模块的日期与 queryParam 规则与本次任务无关，不得套用到基线匹配。";
        String source = "该模块列表默认查询当前日期；空查询参数从 queryParam 读取。该规则仅适用于职业信息模块，不是基线匹配规则。";
        assertThat(classifier.classify(finding, List.of(source))).isEqualTo(PlanFindingClassifier.Kind.KNOWN_RULE);
        assertThat(classifier.classify(finding, List.of("规则仅适用于其他模块，不是基线匹配规则。")))
                .isEqualTo(PlanFindingClassifier.Kind.UNRESOLVED);
        assertThat(classifier.classify(finding + "但退款应放宽过滤。", List.of(source)))
                .isEqualTo(PlanFindingClassifier.Kind.UNRESOLVED);
    }
}
