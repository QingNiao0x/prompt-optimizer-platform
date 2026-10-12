package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.*;
import com.promptoptimizer.provider.domain.*;
import com.promptoptimizer.template.domain.PromptTemplate;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 从最终组装入口固定收入跨指标推断，保护独立证据和非分析任务。 */
class EvidenceInferenceRegressionTest {
    @Test void copiedPromptCarriesTheSpecificEvidenceBoundaryWithoutAQuestionnaire() {
        var result = assemble("分析甲公司收入变化。", "上年收入100，本年收入120。", "根据收入数据分析。", "计算变化并解释依据。");
        assertThat(result.optimizedPrompt()).contains("无法从现有资料推断", "同期市场总量", "销量");
        assertThat(result.ambiguities()).isEmpty();
    }
    @Test void rejectsTheObservedClaimEvenWithATrailingCaveat() {
        assertThatThrownBy(() -> assemble("分析收入增长。", "收入增长20%。", "收入增长说明市场份额或需求端未出现萎缩迹象。", "输出分析。存在假设。"))
                .isInstanceOf(ProviderResponseValidationException.class);
    }
    @Test void independentMarketEvidenceCanSupportItsOwnConclusion() {
        assertThatCode(() -> assemble("分析收入及市场份额。", "收入增长20%。市场份额由10%升至12%。", "市场份额提升。", "分别引用收入及市场份额依据。"))
                .doesNotThrowAnyException();
    }
    @Test void conditionalCandidateIsNotPresentedAsAnObservedConclusion() {
        assertThatCode(() -> assemble("分析收入增长。", "收入增长20%。", "若取得可比市场数据，可判断市场份额是否提升。", "先完成已有收入分析。"))
                .doesNotThrowAnyException();
    }
    @Test void aTranslationDoesNotAcquireFinancialAnalysisInstructions() {
        assertThat(assemble("将收入增长翻译为英文，只输出译文。", "", "将收入增长译为英文。", "只输出译文。")
                .optimizedPrompt()).doesNotContain("同期市场总量");
    }
    private OptimizationResult assemble(String raw, String material, String task, String output) {
        var context = new ContextSnapshot(material, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "test");
        var response = new EnhancementProviderResponse(List.of(
                new PromptSection(PromptSectionType.BACKGROUND,"背景",material.isBlank()?"依据用户原文。":material),
                new PromptSection(PromptSectionType.TASK,"任务",task),
                new PromptSection(PromptSectionType.OUTPUT,"输出",output),
                new PromptSection(PromptSectionType.CONSTRAINTS,"约束","不编造事实。")),"test","test",false,List.of());
        return new OptimizationResultAssembler().assemble(response,context,
                new PromptTemplate(TemplateCode.GENERAL,"交付所需结果。","结果有依据。","相关示例。"),
                List.of(),List.of(),false,List.of(),false,1,raw);
    }
}
