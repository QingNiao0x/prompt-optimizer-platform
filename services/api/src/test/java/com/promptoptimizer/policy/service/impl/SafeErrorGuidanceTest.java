package com.promptoptimizer.policy.service.impl;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.PermissionPolicyInput;
import com.promptoptimizer.policy.domain.PlatformConstraintRules;
import com.promptoptimizer.template.domain.TaskIntentResolver;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 错误处理的安全措辞只作用于本次代码交付，不扩展成报告正文或新的任务职责。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class SafeErrorGuidanceTest {
    private final ContextSnapshot context = new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "EMPTY");

    @Test
    void softwareKeepsThreeTaskRulesAndAllMandatorySafetyRules() {
        var grouped = new ConstraintCompleterImpl().completeForTask(context, PermissionPolicyInput.empty(), false,
                TaskIntentResolver.resolve(TemplateCode.FEATURE_DEVELOPMENT, "使用requests库爬取网页标题。"));
        assertThat(grouped.taskSpecific()).hasSize(3).anySatisfy(rule ->
                assertThat(rule).contains("处理可预期异常", "不含敏感值", "不吞掉或伪造错误"));
        assertThat(grouped.platformMandatory()).containsExactlyElementsOf(PlatformConstraintRules.forTask(true));
    }

    @Test
    void auxiliaryCodeKeepsItsScopeAndNonCodeReportDoesNotAcquireCodeErrorRequirements() {
        var completer = new ConstraintCompleterImpl();
        var auxiliary = completer.completeWithAuxiliaryCode(context, PermissionPolicyInput.empty(), false, TemplateCode.RESEARCH_ANALYSIS);
        assertThat(auxiliary).anySatisfy(rule -> assertThat(rule).startsWith("仅对本次明确交付的代码：").contains("不含敏感值"));
        var report = completer.completeForTask(context, PermissionPolicyInput.empty(), false,
                TaskIntentResolver.resolve(TemplateCode.RESEARCH_ANALYSIS, "撰写一份研究报告。"));
        assertThat(report.taskSpecific()).noneSatisfy(rule -> assertThat(rule).contains("错误信息"));
    }
}
