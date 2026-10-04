package com.promptoptimizer.enhancement.service.impl;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 区分已给定的材料使用规则与实际未决事项，保留真实冲突和同段新增缺口。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class PlanFindingClassifierTest {
    private final PlanFindingClassifier classifier = new PlanFindingClassifier();

    @Test
    void shouldOnlyReclassifyCompleteSourcedCapabilityBoundaries() {
        String source = "资料关联意味着用户主动提供材料后系统选取相关片段，不能写工具自动读取用户电脑、长期存储整份源码或识别所有文件无遗漏。";
        String finding = "资料关联的边界：仅指用户主动提供材料后系统选取相关片段，不包含自动读取用户电脑、长期存储整份源码或识别所有文件无遗漏。";
        assertThat(classifier.classify(finding, List.of(source))).isEqualTo(PlanFindingClassifier.Kind.KNOWN_RULE);
        assertThat(classifier.classify(finding + "未成年人资料是否允许上传？", List.of(source)))
                .isEqualTo(PlanFindingClassifier.Kind.UNRESOLVED);
        assertThat(classifier.classify(finding.replace("用户主动提供", "平台自动读取"), List.of(source)))
                .isEqualTo(PlanFindingClassifier.Kind.UNRESOLVED);
        assertThat(classifier.classify("结构化结果的边界：可编辑复制交给其他 AI 使用，但不等于 AI 已完成下游工作。",
                List.of("结构化结果可以编辑复制交给其他AI使用，但不是AI已完成下游工作；不能将生成提示词包装成自动完成开发的承诺。")))
                .isEqualTo(PlanFindingClassifier.Kind.KNOWN_RULE);
        assertThat(classifier.classify("需求澄清的边界：只针对真正影响任务的缺口，信息完整时不强制提问。",
                List.of("需求澄清只针对真正影响任务的缺口，信息完整时无需强制提问；新闻稿无需解释内部协议。")))
                .isEqualTo(PlanFindingClassifier.Kind.KNOWN_RULE);
        String excluded = "测试材料 materials/tests/layout-example.txt 仅用于排版与来源识别，不作为本题事实来源。";
        assertThat(classifier.classify(excluded, List.of(), List.of("已排除的排版测试资料：materials/tests/layout-example.txt")))
                .isEqualTo(PlanFindingClassifier.Kind.KNOWN_RULE);
        assertThat(classifier.classify(excluded + "允许用于推算实际准确率吗？", List.of(),
                List.of("已排除的排版测试资料：materials/tests/layout-example.txt")))
                .isEqualTo(PlanFindingClassifier.Kind.UNRESOLVED);
        assertThat(classifier.classify(excluded, List.of(), List.of())).isEqualTo(PlanFindingClassifier.Kind.UNRESOLVED);
    }

    @Test
    void shouldKeepVerifiedConflictHandlingInstructionsOutOfPendingChoices() {
        String rule = "若发现材料间存在冲突，同时保留原意与出处，不按字数、写作风格或文件日期决定哪个值正确。";
        assertThat(classifier.classify(rule, List.of(rule))).isEqualTo(PlanFindingClassifier.Kind.KNOWN_RULE);
        String missing = "不能把文件未提供写成项目中不存在，不能把草稿建议写成已批准制度。";
        assertThat(classifier.classify(missing, List.of(missing))).isEqualTo(PlanFindingClassifier.Kind.KNOWN_RULE);
        assertThat(classifier.classify(rule + "退款审批金额尚未确定。", List.of(rule)))
                .isEqualTo(PlanFindingClassifier.Kind.UNRESOLVED);
    }

    @Test
    void shouldNotTreatAnUnresolvedSourceAssertionAsProofOfResolution() {
        String conflict = "退款金额存在冲突，需要用户确认。";
        assertThat(classifier.classify(conflict, List.of(conflict)))
                .isEqualTo(PlanFindingClassifier.Kind.UNRESOLVED);
        assertThat(classifier.classify("审批金额应采用多少？", List.of("审批金额应采用多少？")))
                .isEqualTo(PlanFindingClassifier.Kind.UNRESOLVED);
    }
}
