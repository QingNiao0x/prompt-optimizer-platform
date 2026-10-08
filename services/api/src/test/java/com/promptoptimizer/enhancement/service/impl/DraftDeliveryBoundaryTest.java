package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.ConfirmedPlanDecision;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.provider.domain.DraftDeliveryContract;
import com.promptoptimizer.template.service.impl.PromptTemplateRegistryImpl;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 起草策略不能越过明确审批，也不能将计算前提扩大为整份报告的停写条件。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class DraftDeliveryBoundaryTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "请撰写年度研究报告，不能只给提纲。",
            "请撰写分析报告。寿命表确认前不得计算YLL，不得编造数字。",
            "请撰写研究报告，只交付完整报告和空表。",
            "请撰写分析报告，只交付分析报告和指标表。",
            "请撰写分析报告。计算前先向我确认参考寿命表，其他正文继续起草。",
            "请撰写分析报告。先确认参考寿命表再计算YLL，同时撰写有依据的正文。",
            "请撰写分析报告。先确认寿命表后再计算，继续撰写正文。",
            "请撰写分析报告，同时附带R脚本。",
            "请撰写研究报告。\n> 原文：先确认后再撰写报告。",
            "请撰写研究报告。\n```text\n确认前不得起草报告。\n```",
            "## 原文\n先问我再撰写报告。\n## 任务\n撰写研究报告。",
            "## 参考资料\n只交付报告提纲。\n## 任务\n撰写研究报告。",
            "## 示例\n禁止占位，先向我提问。\n## 任务\n撰写研究报告。",
            "请撰写报告，并附CSV数据表。",
            "不要只输出CSV，请撰写分析报告。",
            "请编写邮件。",
            "## 原文\n只输出CSV。\n## 任务\n请撰写报告。",
            "请撰写分析报告，只输出JSON，报告正文放在body字段中。"
    })
    void shouldProgressOnlyTheRequestedDraft(String raw) {
        assertThat(contract(raw, List.of()).active()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "制定分析方案，只交付方法提纲和空表，不写报告。",
            "请撰写研究报告，但在用户确认前不得起草报告。",
            "请撰写研究报告，先问我研究目标再写。",
            "请撰写研究报告。请先向我提问，确认研究范围。",
            "请撰写研究报告，禁止占位；缺数据先向我确认。",
            "请撰写研究报告，不使用占位符。",
            "请撰写研究报告，本轮只要报告提纲。",
            "请撰写研究报告，本轮只交付报告大纲和空表。",
            "请编写规则对照表，只读比较，不写正文。",
            "请编写规则对照表，只读比较，不生成报告。",
            "请编写规则对照表，只读比较，保留双方完整规则。",
            "## 任务\n撰写研究报告。\n## 约束\n请先向我确认研究范围。",
            "只翻译下面的话为英语：请撰写研究报告。",
            "实现Python排序函数，并补充独立单元测试。"
    })
    void shouldKeepPreparationTranslationAndApprovalBoundaries(String raw) {
        assertThat(contract(raw, List.of()).active()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "请编写CSV字段映射表，只输出CSV。",
            "请编写纯JSON配置，只输出JSON。",
            "请写一段纯JSON配置。",
            "请编写字段映射表，仅返回有效json。"
    })
    void exclusiveDataFormatsDoNotReceiveADraftBody(String raw) {
        var draft = contract(raw, List.of());
        assertThat(draft.active()).isFalse();
        assertThat(draft.guidance()).isEmpty();
        assertThat(draft.modelGuidance()).isEmpty();
    }

    @Test
    void theModelKeepsConcreteDeliverablesWithoutCopyingThePlatformDraftParagraph() {
        var draft = contract("请撰写分析报告。", List.of());
        assertThat(draft.modelGuidance()).contains("TASK 只保留用户要完成的正文目标", "OUTPUT 只说明本次具体交付内容",
                "平台统一追加", "不要复制通用起草段落或待补机制", "由执行者自行完成", "实证数值和结论",
                "不新增用户未要求的空表、附录或指标", "用户明确要求的表格继续交付", "不能冒充已确认事实")
                .doesNotContain(draft.guidance(), "[待补：具体内容]");
    }

    @Test
    void onlyAnActualDeliveryDecisionCanRestrictTheCurrentDraft() {
        String raw = "请撰写研究报告。";
        var chosen = new ConfirmedPlanDecision("delivery", "本次交付什么？", "交付",
                ConfirmedPlanDecision.Scope.CHOICE, "本次只交付提纲。 ");
        assertThat(contract(raw, List.of(chosen)).active()).isFalse();
        var undecided = new ConfirmedPlanDecision("delivery", "本次交付什么？", "交付",
                ConfirmedPlanDecision.Scope.UNRESOLVED, "暂不确定是否只交付提纲。");
        assertThat(contract(raw, List.of(undecided)).active()).isTrue();
        var current = new ConfirmedPlanDecision("delivery", "本次交付什么？", "交付",
                ConfirmedPlanDecision.Scope.CURRENT_STATE, "当前只交付提纲。");
        assertThat(contract(raw, List.of(current)).active()).isTrue();
    }

    @Test
    void unrelatedOrAppendixDecisionsCannotStopTheReportDraft() {
        String raw = "请撰写研究报告。";
        for (String question : List.of("审批材料如何提供？", "附表的交付形式是什么？", "下一阶段交付什么？")) {
            var unrelated = new ConfirmedPlanDecision("other", question, "材料",
                    ConfirmedPlanDecision.Scope.CHOICE, "只提供方案和空表。");
            assertThat(contract(raw, List.of(unrelated)).active()).as(question).isTrue();
        }
        var calculation = new ConfirmedPlanDecision("delivery", "本次交付什么？", "交付",
                ConfirmedPlanDecision.Scope.CHOICE, "撰写研究报告；计算前先确认寿命表。");
        assertThat(contract(raw, List.of(calculation)).active()).isTrue();
    }

    private DraftDeliveryContract contract(String raw, List<ConfirmedPlanDecision> decisions) {
        return DraftDeliveryContract.from(raw,
                new PromptTemplateRegistryImpl().resolve(TemplateCode.AUTO, raw, decisions), decisions);
    }
}
