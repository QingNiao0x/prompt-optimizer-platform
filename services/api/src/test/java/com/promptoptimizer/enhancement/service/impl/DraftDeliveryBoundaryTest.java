package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.ConfirmedPlanDecision;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.provider.domain.DraftDeliveryContract;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException;
import com.promptoptimizer.template.domain.TaskDeliveryProfile;
import com.promptoptimizer.template.domain.TaskIntentResolver;
import com.promptoptimizer.template.service.impl.PromptTemplateRegistryImpl;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 起草策略不能越过明确审批，也不能将计算前提扩大为整份报告的停写条件。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class DraftDeliveryBoundaryTest {
    private static final String SCREENSHOT_ANALYSIS = """
            我是一名科研工作者,我想要撰写2015-2025年某地区心脑血管疾病死亡率特征分析,我需要从以下几个方面进行分析:
            1.时间序列的长期趋势和季节性趋势
            2.分性别\\地区\\分人群\\分亚类去进行比较
            3.进行减寿分析:年度的\\性别的yll和yll率
            4.采用Arriaga分解方法去分析""";

    /** 用户要求撰写分析正文，即使标题不含“报告”，也不能降成只交方案。 */
    @Test
    void theExactScreenshotRequestAlreadySelectsAnalysisWritingWithoutAReportKeyword() {
        var intent = TaskIntentResolver.resolve(TemplateCode.AUTO, SCREENSHOT_ANALYSIS);
        assertThat(intent.deliveryProfile()).isEqualTo(TaskDeliveryProfile.DATA_ANALYSIS);
        assertThat(intent.engineeringConstraints()).isFalse();
        assertThat(contract(SCREENSHOT_ANALYSIS, List.of()).active()).isTrue();
    }

    /** 后处理不能用一段正确指导语掩盖模型已将正文目标改为方案或全篇等待。 */
    @ParameterizedTest
    @ValueSource(strings = {
            "撰写一份2015—2025年某地区心脑血管疾病死亡率特征分析方案，覆盖四个分析方向。",
            "输出：一份死亡率特征分析的撰写方案。",
            "交付用于撰写报告的分析方案。",
            "交付一份结构化的分析方案文档。\n资料充分时交付报告正文，不足时完成有依据的初稿。",
            "撰写研究报告。本轮只交付分析方案。",
            "撰写研究报告，另外本轮只交付分析方案。",
            "暂不提供报告正文，另外只交付方案。",
            "本轮只交付方法提纲和空表。",
            "先补齐全部资料后再撰写分析正文。",
            "请先补齐所有资料，再开始撰写报告。",
            "资料未补齐前不得撰写正文。"
    })
    void rejectsReplacingTheScreenshotDeliverable(String modelText) {
        assertThatThrownBy(() -> contract(SCREENSHOT_ANALYSIS, List.of())
                .validateDelivery(modelText, "sections.TASK.content"))
                .isInstanceOfSatisfying(ProviderResponseValidationException.class, error -> {
                    assertThat(error.getReason()).isEqualTo(ProviderResponseValidationException.Reason.RULE_CONFLICT);
                    assertThat(error.getField()).isEqualTo("sections.TASK.content");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "撰写死亡率特征分析正文，说明候选分析方案的适用条件。",
            "撰写论文方法章节正文，比较不同分解方案。",
            "在报告的方法章节中提供分析方案，正文继续完成。",
            "撰写分析报告并附分析方案。",
            "撰写研究报告，并提供空表。",
            "撰写研究报告，另外提供整改方案。",
            "不得撰写分析方案替代正文。",
            "不能只交付方法提纲和空表。",
            "例如撰写分析方案并不等于完成正文。",
            "如果以后要求撰写分析方案，再调整交付物。",
            "引用：撰写一份分析方案。\n## 任务\n撰写分析正文。",
            "> 先补齐全部资料后再撰写正文。\n撰写现有证据支持的正文。",
            "资料不足仅限制实证结果，不得编造数字，继续完成方法正文。",
            "计算前先确认参考寿命表，其他正文继续起草。",
            "先补齐寿命表后再计算YLL，其他正文继续起草。",
            "先补齐寿命表再计算YLL，继续撰写正文。"
    })
    void allowsProseMethodsReferencesNegationAndLocalPrerequisites(String modelText) {
        assertThatCode(() -> contract(SCREENSHOT_ANALYSIS, List.of())
                .validateDelivery(modelText, "sections.OUTPUT.content")).doesNotThrowAnyException();
    }

    @Test
    void forbiddingPlaceholdersDoesNotPermitChangingTheReportIntoAPlan() {
        var draft = contract("请撰写分析报告，不要占位符。", List.of());
        assertThat(draft.active()).isFalse();
        assertThatThrownBy(() -> draft.validateDelivery("撰写分析方案。", "sections.TASK.content"))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"请撰写分析方案。", "请撰写论文方法提纲。", "本轮只交付提纲。"})
    void explicitPreparationGoalsRemainAllowed(String raw) {
        assertThatCode(() -> contract(raw, List.of()).validateDelivery(raw, "sections.TASK.content"))
                .doesNotThrowAnyException();
    }

    /** 忠实复述用户要求的两项交付，不应因为缺少“并”字被当成替换正文。 */
    @ParameterizedTest
    @ValueSource(strings = {
            "请撰写研究报告。提供统计分析方案。",
            "请写研究报告。输出附录中的方法提纲。"
    })
    void aRequestedReportCanBeFollowedByAnExplicitAdditionalDeliverable(String raw) {
        var draft = contract(raw, List.of());
        assertThat(draft.proseRequired()).isTrue();
        assertThatCode(() -> draft.validateDelivery(raw, "sections.TASK.content")).doesNotThrowAnyException();
    }

    @Test
    void explicitApprovalStillControlsWhenDraftingMayStart() {
        var draft = contract("请撰写研究报告，先向我确认研究范围。", List.of());
        assertThat(draft.active()).isFalse();
        assertThatCode(() -> draft.validateDelivery("先补齐研究范围后再撰写正文。", "sections.TASK.content"))
                .doesNotThrowAnyException();
        var chosen = new ConfirmedPlanDecision("delivery", "本次交付什么？", "交付",
                ConfirmedPlanDecision.Scope.CHOICE, "本次只交付提纲。");
        assertThatCode(() -> contract(SCREENSHOT_ANALYSIS, List.of(chosen))
                .validateDelivery("本轮只交付提纲。", "sections.TASK.content")).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "请撰写年度研究报告，不能只给提纲。",
            "请撰写分析报告。寿命表确认前不得计算YLL，不得编造数字。",
            "请撰写研究报告，只交付完整报告和空表。",
            "请撰写研究报告，并提供空表。",
            "请撰写研究报告，另外提供整改方案。",
            "我平时负责撰写研究方案，今天请撰写一篇研究报告。",
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
        assertThat(draft.guidance()).contains("材料充分时直接成稿", "可修订初稿", "直接交付正文，不先发补材料问卷",
                "重要未决条件集中说明一次", "同一缺口不逐节重复标待确认", "原要求的表格、代码及篇幅继续保留");
        // 四要素仍核对交付；正文目标必须明确，辅助输出槽只补独有信息，不要求重复写一份目标。
        assertThat(draft.modelGuidance()).contains("TASK 明确原定正文目标", "OUTPUT 仅补尚未表达的交付内容、范围和格式",
                "无新增信息可留空", "不把正文改成方案或问卷",
                "平台统一追加", "不要复制通用起草段落或待补机制", "由执行者自行完成", "实证数值和结论",
                "不新增用户未要求的空表、附录或指标", "用户明确要求的表格继续交付", "不能冒充已确认事实")
                .doesNotContain(draft.guidance(), "[待补：具体内容]", "TASK 与 OUTPUT 均须");
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
