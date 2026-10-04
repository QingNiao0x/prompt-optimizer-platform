package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.context.domain.TechnologyStackItem;
import com.promptoptimizer.enhancement.domain.ConfirmedPlanDecision;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.PlanQuestionType;
import com.promptoptimizer.enhancement.domain.PlanOption;
import com.promptoptimizer.enhancement.dto.PermissionPolicyInput;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.policy.service.impl.ConstraintCompleterImpl;
import com.promptoptimizer.provider.domain.PlanningProviderRequest;
import com.promptoptimizer.provider.infrastructure.MockPromptPlanningProvider;
import com.promptoptimizer.template.domain.TaskDeliveryProfile;
import com.promptoptimizer.template.domain.TaskIntentResolver;
import com.promptoptimizer.template.service.impl.PromptTemplateRegistryImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.List;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 任务目标、资料主题和禁止事项分别验收；确认交付只取已绑定的真实选择。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class TaskTemplateAdaptationTest {
    private final PromptTemplateRegistryImpl registry = new PromptTemplateRegistryImpl();

    static Stream<Arguments> goals() {
        return Stream.of(
                Arguments.of("整理两家医院的预约数据，只交付中文步骤、字段对照表和检查清单。不要求编写代码测试。", TemplateCode.GENERAL, TaskDeliveryProfile.MEDICAL_MATERIAL),
                Arguments.of("整理法务材料，比较审批和退款规则。不运行程序，也不修改代码。", TemplateCode.GENERAL, TaskDeliveryProfile.LEGAL_MATERIAL),
                Arguments.of("为研究生写一篇平台新闻稿，介绍登录接口；不提供研究设计。", TemplateCode.GENERAL, TaskDeliveryProfile.NEWS_RELEASE),
                Arguments.of("根据 Java 项目编写用户指南，不执行代码测试。", TemplateCode.GENERAL, TaskDeliveryProfile.USER_GUIDE),
                Arguments.of("翻译以下内容为英语，仅输出译文。原文：请开发接口并测试系统。", TemplateCode.GENERAL, TaskDeliveryProfile.TRANSLATION),
                Arguments.of("为六年级设计分数比较教案，不附软件测试用例。", TemplateCode.GENERAL, TaskDeliveryProfile.TEACHING),
                Arguments.of("撰写机关工作报告，不编造数据和已经完成的工作。", TemplateCode.GENERAL, TaskDeliveryProfile.INSTITUTIONAL_REPORT),
                Arguments.of("根据会议记录生成会议纪要，不补写负责人。", TemplateCode.GENERAL, TaskDeliveryProfile.MEETING_MINUTES),
                Arguments.of("根据附件整理材料对照表，不添加分析代码。", TemplateCode.GENERAL, TaskDeliveryProfile.MATERIAL_SYNTHESIS),
                Arguments.of("撰写硕士论文的文献综述，引用仅限提供文献。", TemplateCode.RESEARCH_ANALYSIS, TaskDeliveryProfile.ACADEMIC_WRITING),
                Arguments.of("制定研究方案，只输出方法提纲和空表，不生成研究结果。", TemplateCode.RESEARCH_ANALYSIS, TaskDeliveryProfile.ACADEMIC_METHODS),
                Arguments.of("依据模拟月度数据制定统计分析方案，不运行程序，只交付方案和空表。", TemplateCode.RESEARCH_ANALYSIS, TaskDeliveryProfile.DATA_ANALYSIS),
                Arguments.of("研究广东省死亡率长期趋势，并提供分析代码。", TemplateCode.RESEARCH_ANALYSIS, TaskDeliveryProfile.DATA_ANALYSIS),
                Arguments.of("我想撰写2015-2025年某地区心脑血管疾病死亡率特征分析。", TemplateCode.RESEARCH_ANALYSIS, TaskDeliveryProfile.DATA_ANALYSIS),
                Arguments.of("开发科研资料管理接口，附件是研究方案。", TemplateCode.FEATURE_DEVELOPMENT, TaskDeliveryProfile.SOFTWARE_IMPLEMENTATION),
                Arguments.of("给用户模块增加登录功能。", TemplateCode.FEATURE_DEVELOPMENT, TaskDeliveryProfile.SOFTWARE_IMPLEMENTATION),
                Arguments.of("修复订单接口异常。", TemplateCode.BUG_FIX, TaskDeliveryProfile.SOFTWARE_IMPLEMENTATION),
                Arguments.of("重构现有 React 前端并迁移到 Vue 3。", TemplateCode.REFACTORING, TaskDeliveryProfile.SOFTWARE_IMPLEMENTATION),
                Arguments.of("补充订单接口测试用例，覆盖异常和边界。", TemplateCode.TESTING, TaskDeliveryProfile.SOFTWARE_IMPLEMENTATION),
                Arguments.of("请为调查录入系统制定地区匹配的实现方案，覆盖查询失败与取消分支。", TemplateCode.FEATURE_DEVELOPMENT, TaskDeliveryProfile.SOFTWARE_IMPLEMENTATION),
                Arguments.of("## 背景\n已有 Java 接口与测试系统。\n## 任务\n编写操作指南。\n## 约束\n不要开发接口。", TemplateCode.GENERAL, TaskDeliveryProfile.USER_GUIDE),
                Arguments.of("不要求编写代码测试。", TemplateCode.GENERAL, TaskDeliveryProfile.GENERAL),
                Arguments.of("Write a press release for graduate students. Do not develop code.", TemplateCode.GENERAL, TaskDeliveryProfile.NEWS_RELEASE),
                Arguments.of("Write a user guide for the Java application.", TemplateCode.GENERAL, TaskDeliveryProfile.USER_GUIDE),
                Arguments.of("Draft a research proposal; do not invent results.", TemplateCode.RESEARCH_ANALYSIS, TaskDeliveryProfile.ACADEMIC_METHODS),
                Arguments.of("Fix a bug in the login endpoint.", TemplateCode.BUG_FIX, TaskDeliveryProfile.SOFTWARE_IMPLEMENTATION),
                Arguments.of("Refactor the frontend code and migrate React to Vue.", TemplateCode.REFACTORING, TaskDeliveryProfile.SOFTWARE_IMPLEMENTATION),
                Arguments.of("Add unit tests for the order service.", TemplateCode.TESTING, TaskDeliveryProfile.SOFTWARE_IMPLEMENTATION),
                Arguments.of("Implement a login endpoint with the existing framework.", TemplateCode.FEATURE_DEVELOPMENT, TaskDeliveryProfile.SOFTWARE_IMPLEMENTATION),
                Arguments.of("材料中写着请开发接口，但本次目标尚未明确。", TemplateCode.GENERAL, TaskDeliveryProfile.GENERAL),
                Arguments.of("## 原文\n修复订单接口异常。", TemplateCode.GENERAL, TaskDeliveryProfile.GENERAL));
    }

    @ParameterizedTest
    @MethodSource("goals")
    void identifiesPositiveDeliveryAndKeepsExcludedWorkSeparate(String raw, TemplateCode code, TaskDeliveryProfile profile) {
        var intent = TaskIntentResolver.resolve(TemplateCode.AUTO, raw);
        assertThat(intent.templateCode()).isEqualTo(code);
        assertThat(intent.deliveryProfile()).isEqualTo(profile);
        assertThat(registry.infer(raw)).isEqualTo(code);
    }

    @Test
    void confirmedTranslationRefinesBothAutoAndGeneralWithoutUsingUnresolvedOrCurrentState() {
        var confirmed = new ConfirmedPlanDecision("delivery", "这次要交付什么内容？", "交付内容",
                ConfirmedPlanDecision.Scope.CHOICE, "翻译成英语，只输出译文。");
        for (TemplateCode code : List.of(TemplateCode.AUTO, TemplateCode.GENERAL)) {
            var template = registry.resolve(code, "帮我处理材料。", List.of(confirmed));
            assertThat(template.deliveryProfile()).isEqualTo(TaskDeliveryProfile.TRANSLATION);
            assertThat(template.outputGuidance()).contains("译文").doesNotContain("实现", "报告", "执行步骤");
        }
        for (var scope : List.of(ConfirmedPlanDecision.Scope.UNRESOLVED, ConfirmedPlanDecision.Scope.CURRENT_STATE)) {
            var unknown = new ConfirmedPlanDecision("delivery", "这次要交付什么内容？", "交付内容", scope, "翻译成英语。");
            assertThat(registry.resolve(TemplateCode.AUTO, "帮我处理材料。", List.of(unknown)).deliveryProfile())
                    .isEqualTo(TaskDeliveryProfile.GENERAL);
        }
    }

    @Test
    void confirmedOverallDeliveryUpdatesOnlyItsOldConfirmationPredicate() {
        String raw = "请处理材料。具体交付形式请先向我确认，不执行开发、部署或发送消息。";
        var answers = ConfirmedDecisionSet.from(List.of(new PlanAnswer("delivery", "这次最终要交付什么形式的内容？",
                "翻译为英文，只输出译文。")));
        String updated = ResolvedPlanState.from(answers, raw).reconcile(raw);
        assertThat(updated).contains("交付已在Plan阶段确认", "不执行开发、部署或发送消息")
                .doesNotContain("交付形式请先向我确认");
        var rewrittenQuestion = ConfirmedDecisionSet.from(List.of(new PlanAnswer("delivery", "这次希望我最终交付什么形式的成果？",
                "翻译为英文，只输出译文。")));
        assertThat(ResolvedPlanState.from(rewrittenQuestion, raw).reconcile(raw)).isEqualTo(updated);
        var presentationQuestion = ConfirmedDecisionSet.from(List.of(new PlanAnswer("delivery", "您希望最终交付的提示词以什么形式呈现？",
                "翻译为英文，只输出译文。")));
        assertThat(ResolvedPlanState.from(presentationQuestion, raw).reconcile(raw)).isEqualTo(updated);
        assertThat(ResolvedPlanState.from(answers, raw).reconcile("附件交付形式请先向我确认。"))
                .isEqualTo("附件交付形式请先向我确认。");
        var pending = ConfirmedDecisionSet.from(List.of(new PlanAnswer("delivery", "这次最终要交付什么形式的内容？", "暂不确定。")));
        assertThat(ResolvedPlanState.from(pending, raw).reconcile(raw)).isEqualTo(raw);
        assertThat(ResolvedPlanState.from(ConfirmedDecisionSet.from(List.of()), raw).reconcile(raw)).isEqualTo(raw);
    }

    @Test
    void explicitSoftwareChoiceAndIndependentBusinessAnswerRemainCompatible() {
        assertThat(registry.resolve(TemplateCode.BUG_FIX, "写新闻稿").code()).isEqualTo(TemplateCode.BUG_FIX);
        assertThat(registry.resolve(TemplateCode.BUG_FIX, "写新闻稿").outputGuidance()).contains("根因");
        var independent = new ConfirmedPlanDecision("rule", "退款规则采用什么标准？", "规则",
                ConfirmedPlanDecision.Scope.CHOICE, "按材料中的翻译格式填写。");
        assertThat(registry.resolve(TemplateCode.AUTO, "写新闻稿。", List.of(independent)).deliveryProfile())
                .isEqualTo(TaskDeliveryProfile.NEWS_RELEASE);
        var appendix = new ConfirmedPlanDecision("appendix", "附表的交付形式是什么？", "交付形式",
                ConfirmedPlanDecision.Scope.CHOICE, "翻译为英语，只输出译文。");
        assertThat(registry.resolve(TemplateCode.AUTO, "写新闻稿。", List.of(appendix)).deliveryProfile())
                .isEqualTo(TaskDeliveryProfile.NEWS_RELEASE);
        assertThat(registry.resolve(TemplateCode.AUTO, "请帮我处理材料。", List.of(appendix)).deliveryProfile())
                .isEqualTo(TaskDeliveryProfile.GENERAL);
    }

    @Test
    void writingFromJavaSourcesKeepsSecurityButDoesNotInjectEngineeringRequirements() {
        var context = new ContextSnapshot("Java项目", List.of(
                new TechnologyStackItem("Java 21", "pom.xml", 1),
                new TechnologyStackItem("Spring Boot 3", "pom.xml", 1),
                new TechnologyStackItem("PostgreSQL", "compose.yml", 1),
                new TechnologyStackItem("Redis", "compose.yml", 1)),
                List.of(), List.of(), List.of(), List.of(), List.of(), "v1");
        var completer = new ConstraintCompleterImpl();
        for (TemplateCode code : List.of(TemplateCode.GENERAL, TemplateCode.RESEARCH_ANALYSIS)) {
            String text = String.join("\n", completer.complete(context, PermissionPolicyInput.empty(), false, code));
            assertThat(text).contains(".env", "**/*.pem", "人工确认", "API Key")
                    .doesNotContain("Java 21", "Controller", "Bean Validation", "Redis", "ORM", "核心逻辑补充");
        }
        String software = String.join("\n", completer.complete(context, PermissionPolicyInput.empty(), false, TemplateCode.FEATURE_DEVELOPMENT));
        assertThat(software).contains("Java 21", "Controller", "Bean Validation", "Redis", "TTL", "参数化查询", "核心逻辑");
    }

    @Test
    void writingReferencesDoNotBecomeEngineeringOrScientificAmbiguities() {
        var detector = new AmbiguityDetector();
        assertThat(detector.detect("编写用户指南，介绍登录、添加资料和风险排序。不开发功能，也不计算YLL。"))
                .isEmpty();
        assertThat(detector.detect("翻译为英语：给系统添加登录功能，分析某地区YLL。"))
                .isEmpty();
        assertThat(detector.detect("给用户模块添加登录功能。"))
                .anyMatch(value -> value.contains("认证"));
        assertThat(detector.detect("研究某地区死亡率和YLL。"))
                .anyMatch(value -> value.contains("参考寿命"));
        assertThat(detector.detect("编写用户指南，同时开发登录接口。"))
                .anyMatch(value -> value.contains("认证"));
    }

    @Test
    void analysisWithExplicitReproductionCodeKeepsScopedEngineeringChecks() {
        var mixed = TaskIntentResolver.resolve(TemplateCode.AUTO,
                "分析合成月度数据的趋势，并提供分析代码。Python已经明确，不开发业务接口。");
        assertThat(mixed.templateCode()).isEqualTo(TemplateCode.RESEARCH_ANALYSIS);
        assertThat(mixed.deliveryProfile()).isEqualTo(TaskDeliveryProfile.DATA_ANALYSIS);
        assertThat(mixed.auxiliaryProfiles()).contains(TaskDeliveryProfile.SOFTWARE_IMPLEMENTATION);
        assertThat(mixed.engineeringConstraints()).isTrue();
        assertThat(TaskIntentResolver.resolve(TemplateCode.AUTO,
                "编写用户指南，不提供分析代码。").engineeringConstraints()).isFalse();
        var background = new ContextSnapshot("Java背景项目", List.of(
                new TechnologyStackItem("Java 21", "pom.xml", 1),
                new TechnologyStackItem("Redis", "compose.yml", 1)),
                List.of(), List.of(), List.of(), List.of(), List.of(), "v1");
        var constraints = new ConstraintCompleterImpl().completeWithAuxiliaryCode(background,
                PermissionPolicyInput.empty(), false, mixed.templateCode());
        assertThat(String.join("\n", constraints)).contains("本次明确交付的代码", "空值", "异常", "边界验证", "API Key", "人工确认")
                .doesNotContain("Java 21", "Redis 数据必须", "Controller");
    }

    @Test
    void delegatesOnlyPureFormattingOfAnAlreadyRequestedWritingArtifact() {
        String raw = "起草工作报告和资料缺口清单。";
        var table = new PlanOption("table", "表格形式", "便于逐项核对", "资料缺口清单以表格形式呈现。", false);
        var list = new PlanOption("list", "条目式", "按条目组织", "资料缺口清单以条目式呈现。", false);
        var pure = new PlanQuestion("format", "“资料缺口清单”希望以什么形式附在报告正文之后？",
                "清单尚未说明用表格还是条目。", PlanQuestionType.SINGLE_CHOICE, List.of(table, list), List.of(), true);
        assertThat(RoutineWritingPresentation.delegated(pure, raw)).isTrue();
        assertThat(RoutineWritingPresentation.delegated(pure, raw + "请先询问资料缺口清单的形式。")).isFalse();
        var sensitive = new PlanOption("sensitive", "表格形式", "新增审批权限字段", "资料缺口清单列出审批权限。", false);
        var compound = new PlanQuestion("format", pure.question(), pure.hint(), pure.type(), List.of(table, sensitive), List.of(), true);
        assertThat(RoutineWritingPresentation.delegated(compound, raw)).isFalse();
        var business = new PlanQuestion("threshold", "“审批标准”希望以什么形式展示？", "金额阈值尚未确定。",
                pure.type(), List.of(table), List.of(), true);
        assertThat(RoutineWritingPresentation.delegated(business, raw)).isFalse();
    }

    @Test
    void inheritsKnownTranslationParagraphsAndExplanationWithoutSuppressingTermConflicts() {
        String raw = "翻译为英语，只输出译文，保留原文两个段落。Plan Mode保持英文，不输出注释、语言解释。";
        var same = new PlanOption("same", "保持两段", "与原文一致", "译文保持两个段落。", false);
        var different = new PlanOption("different", "拆成三段", "改善阅读", "译文可以拆成三段。", false);
        var paragraphs = new PlanQuestion("paragraphs", "原文两段在译文中如何对应？", "分段影响可读性。",
                PlanQuestionType.SINGLE_CHOICE, List.of(same, different), List.of(), true);
        assertThat(RoutineWritingPresentation.delegated(paragraphs, raw)).isTrue();
        assertThat(RoutineWritingPresentation.delegated(paragraphs, "翻译为英语，只输出译文，段落形式尚未确定。")).isFalse();
        var conflict = new PlanQuestion("terms", paragraphs.question(), "两份术语规范存在冲突，需要确认官方名称。",
                paragraphs.type(), paragraphs.options(), List.of(), true);
        assertThat(RoutineWritingPresentation.delegated(conflict, raw)).isFalse();
        var explanations = new PlanQuestion("gloss", "Plan Mode 保持英文时，是否需要附带简短解释？", "术语解释影响阅读。",
                paragraphs.type(), paragraphs.options(), List.of(), true);
        assertThat(RoutineWritingPresentation.delegated(explanations, raw)).isTrue();
    }

    @Test
    void mockDoesNotUseAttachmentTopicToReplaceGoalAndCompleteTranslationAsksNothing() {
        var provider = new MockPromptPlanningProvider();
        var translated = provider.plan(new PlanningProviderRequest("翻译为英语，只输出译文：明天开会。", "Java接口项目和研究方案", List.of()));
        assertThat(translated.questions()).isEmpty();
        var unknown = provider.plan(new PlanningProviderRequest("请帮我处理材料。", "研究方案和软件接口", List.of()));
        assertThat(unknown.questions()).noneMatch(question -> question.id().startsWith("research-") || question.id().startsWith("software-"));
    }
}
