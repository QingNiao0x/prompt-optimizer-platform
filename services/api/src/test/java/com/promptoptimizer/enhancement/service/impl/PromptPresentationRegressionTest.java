package com.promptoptimizer.enhancement.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.*;
import com.promptoptimizer.provider.domain.*;
import com.promptoptimizer.template.domain.PromptTemplate;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 验证复制正文的紧凑表达和历史兼容，不能靠删四要素或独立规则变短。 */
class PromptPresentationRegressionTest {
    @Test void compactPromptRetainsInternalElementsAndStrictOutputWithoutGenericAcceptance() {
        var result=assemble("翻译为英文，只输出译文：会议取消。",TaskEnhancementAssessment.Presentation.COMPACT);
        assertThat(result.sections()).extracting(PromptSection::type)
                .contains(PromptSectionType.BACKGROUND,PromptSectionType.TASK,PromptSectionType.OUTPUT,PromptSectionType.CONSTRAINTS);
        assertThat(result.optimizedPrompt()).doesNotContain("## 背景","## 任务","通用验收指导")
                .contains("会议取消", "只输出译文", "甲机构", "乙机构");
    }
    @Test void complexTasksRetainStructuredPresentation() {
        assertThat(assemble("撰写跨机构研究设计。",TaskEnhancementAssessment.Presentation.STRUCTURED).optimizedPrompt())
                .contains("## 背景","## 任务");
    }
    @Test void modelAndLatencyUpdatesAndHistorySerializationKeepCompactMetadata() throws Exception {
        var original=assemble("翻译为英文，只输出译文：会议取消。",TaskEnhancementAssessment.Presentation.COMPACT);
        var mapper=new ObjectMapper().findAndRegisterModules();
        var restored=mapper.readValue(mapper.writeValueAsString(original.withModelVersion("version").withLatencyMs(23)),OptimizationResult.class);
        assertThat(restored.promptFormat()).isEqualTo(PromptFormat.COMPACT);
        assertThat(restored.optimizedPrompt()).isEqualTo(original.optimizedPrompt());
        var legacy=mapper.valueToTree(original);((com.fasterxml.jackson.databind.node.ObjectNode)legacy).remove("promptFormat");
        assertThat(mapper.treeToValue(legacy,OptimizationResult.class).promptFormat()).isEqualTo(PromptFormat.STRUCTURED);
    }
    private OptimizationResult assemble(String raw,TaskEnhancementAssessment.Presentation presentation) {
        var response=new EnhancementProviderResponse(List.of(
                new PromptSection(PromptSectionType.BACKGROUND,"背景","待处理材料：会议取消。"),
                new PromptSection(PromptSectionType.TASK,"任务",raw),
                new PromptSection(PromptSectionType.OUTPUT,"输出","按原定格式交付。"),
                new PromptSection(PromptSectionType.CONSTRAINTS,"约束","甲机构：保留原值。\n乙机构：保留原值。")),
                "test","test",false,List.of(),List.of(),new TaskEnhancementAssessment(TaskEnhancementAssessment.Sufficiency.SUFFICIENT,List.of(),presentation));
        return new OptimizationResultAssembler().assemble(response,new ContextSnapshot("",List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),"test"),
                new PromptTemplate(TemplateCode.GENERAL,"原定交付。","通用验收指导。"),List.of(),List.of(),false,List.of(),1,raw);
    }
}
