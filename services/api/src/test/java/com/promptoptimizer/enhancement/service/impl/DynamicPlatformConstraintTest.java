package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.context.domain.TechnologyStackItem;
import com.promptoptimizer.enhancement.domain.ConfirmedPlanDecision;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.PermissionPolicyInput;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.policy.service.impl.ConstraintCompleterImpl;
import com.promptoptimizer.provider.domain.EnhancementProviderResponse;
import com.promptoptimizer.template.domain.TaskDeliveryProfile;
import com.promptoptimizer.template.domain.TaskIntentResolver;
import com.promptoptimizer.template.service.impl.PromptTemplateRegistryImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 从任务判定到可复制正文验证动态固定条款，业务规则与附带代码分别核对。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class DynamicPlatformConstraintTest {
    private static final String HEADING = "平台强制约束（不得删除或弱化）：";
    private static final List<String> CODE_RULES = List.of(
            "明确区分已知事实、用户确认信息和必要假设，不得把猜测写成事实。",
            "不得在代码、日志或响应中泄露密码、Token、API Key 或私钥。",
            "输出应直接回应用户目标，遵守已明确的交付范围和格式；仅在任务需要且未限制额外说明时，说明关键依据、适用范围和限制条件。",
            "禁止读取或输出受保护路径：.env、**/*.pem、**/*.key、生产环境配置。",
            "以下操作必须先获得人工确认：删除或覆盖文件、数据库结构迁移、升级核心依赖、生产环境部署。"
    );

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "给用户模块增加登录功能。|true", "修复登录接口异常。|true", "重构前端代码。|true",
            "编写接口测试用例。|true", "提供 Python 数据清洗脚本。|true",
            "撰写统计分析方案，并提供 R 脚本。|true", "撰写分析报告并附上 Python 代码。|true",
            "根据 Java 项目编写用户指南。|false", "分析月度数据，只交付报告。|false",
            "用 R 分析数据，只交付报告，不提供代码。|false", "请勿开发接口，请整理会议纪要。|false",
            "请编写用户指南，不提供分析代码。|false", "是否需要代码尚未确定。|false",
            "翻译为英语：请开发接口并测试系统。|false",
            "制定数据质量方法方案，交付规则表、指标表和必要伪代码。|false",
            "制定分析方案。本次交付不包含可运行的 R 脚本，仅提供中文报告和空表。|false",
            "制定分析方案。最终交付内容不含 Python 代码。|false",
            "提供不超过30行的 Python 代码。|true",
            "制定分析方案。不提供 Python 代码，但提供 R 脚本。|true"
    })
    void usesAffirmativeDeliveryInsteadOfMaterialOrNegativeKeywords(String raw, boolean code) {
        assertThat(TaskIntentResolver.resolve(TemplateCode.AUTO, raw).engineeringConstraints()).isEqualTo(code);
    }

    @Test
    void showsFiveExactRulesWithEngineeringRulesInAnotherBlock() {
        var result = assemble("开发订单接口。", List.of(), "保持接口兼容。", PermissionPolicyInput.empty());
        assertThat(platformRules(result.optimizedPrompt())).containsExactlyElementsOf(CODE_RULES);
        assertThat(result.optimizedPrompt()).contains("任务相关约束", "Java 21", "Bean Validation", "保持接口兼容");
        assertThat(result.appliedConstraints()).containsAll(CODE_RULES);
    }

    @Test
    void showsOnlyOnePlatformRuleForWritingEvenWithSourceCodeContext() {
        var result = assemble("根据 Java 项目编写用户指南。", List.of(), "不编造界面能力。", PermissionPolicyInput.empty());
        assertThat(platformRules(result.optimizedPrompt())).containsExactly(CODE_RULES.getFirst());
        assertThat(result.optimizedPrompt()).doesNotContain("API Key", "生产环境部署", "Bean Validation")
                .contains("不编造界面能力");
        assertThat(result.appliedConstraints()).containsExactly(CODE_RULES.getFirst());
    }

    @Test
    void keepsConfirmedAuxiliaryCodeWithoutChangingTheAnalysisGoal() {
        var decision = new ConfirmedPlanDecision("code", "是否需要提供代码实现？", "附带代码",
                ConfirmedPlanDecision.Scope.CHOICE, "需要提供 R 脚本。");
        var intent = TaskIntentResolver.resolve(TemplateCode.AUTO, "制定统计分析方案。", List.of(decision));
        assertThat(intent.deliveryProfile()).isEqualTo(TaskDeliveryProfile.DATA_ANALYSIS);
        assertThat(intent.engineeringConstraints()).isTrue();
        var result = assemble("制定统计分析方案。",
                List.of(new PlanAnswer("code", decision.question(), decision.answer())), "不得编造数据。",
                PermissionPolicyInput.empty());
        assertThat(platformRules(result.optimizedPrompt())).containsExactlyElementsOf(CODE_RULES);
    }

    @Test
    void retainsCustomBoundariesForNonCodeWithoutMovingDefaultsIntoThatBlock() {
        var result = assemble("编写用户指南。", List.of(), "不编造界面能力。",
                new PermissionPolicyInput(List.of("research/private/**"), List.of("公开访谈原文")));
        assertThat(platformRules(result.optimizedPrompt())).containsExactly(CODE_RULES.getFirst());
        assertThat(result.optimizedPrompt()).contains("用户补充权限边界", "research/private/**", "公开访谈原文")
                .doesNotContain("生产环境部署", "**/*.pem");
    }

    @Test
    void replacesProviderBoilerplateButKeepsBusinessRulesAndQuotedText() {
        String content = "不得公开受访者姓名。\n" + HEADING + "\n- " + String.join("\n- ", CODE_RULES)
                + "\n\n任务规则：\n- 统计按自然月聚合。\n> " + CODE_RULES.get(1);
        var result = assemble("制定统计分析方案，不得公开受访者姓名。", List.of(), content, PermissionPolicyInput.empty());
        assertThat(platformRules(result.optimizedPrompt())).containsExactly(CODE_RULES.getFirst());
        assertThat(result.optimizedPrompt()).contains("不得公开受访者姓名", "统计按自然月聚合", "> " + CODE_RULES.get(1));
        assertThat(result.optimizedPrompt().split(HEADING, -1)).hasSize(2);
    }

    @Test
    void preservesQuotedCodeAndUserExplicitPrivacyWhileReplacingOldGeneratedBlock() {
        String quoted = "```text\n" + CODE_RULES.get(1) + "\n```\n| 条款 |\n| " + CODE_RULES.get(3) + " |";
        String old = HEADING + "\n- " + String.join("\n- ", CODE_RULES) + "\n- 不得公开受访者姓名。";
        String raw = "## 任务\n编写用户指南。\n## 约束\n" + CODE_RULES.get(1) + "\n" + old;
        var result = assemble(raw, List.of(), old + "\n\n" + quoted, PermissionPolicyInput.empty());
        assertThat(platformRules(result.optimizedPrompt())).containsExactly(CODE_RULES.getFirst());
        assertThat(result.optimizedPrompt()).contains(quoted, "不得公开受访者姓名", CODE_RULES.get(1))
                .doesNotContain(CODE_RULES.get(4));
        assertThat(PlatformConstraintRenderer.taskInput(raw)).contains(CODE_RULES.get(1), "不得公开受访者姓名")
                .doesNotContain(HEADING, CODE_RULES.get(4));
    }

    @Test
    void keepsExplicitDefaultPathAsUserRuleInNonCodeTask() {
        var result = assemble("编写用户指南。", List.of(), "保留业务边界。",
                new PermissionPolicyInput(List.of(".env"), List.of("公开访谈原文")));
        assertThat(platformRules(result.optimizedPrompt())).containsExactly(CODE_RULES.getFirst());
        assertThat(result.optimizedPrompt()).contains("用户补充权限边界", "禁止读取或输出受保护路径：.env。", "公开访谈原文");
        assertThat(result.appliedConstraints()).contains("禁止读取或输出受保护路径：.env。");
    }

    @Test
    void changesCodeDeliveryOnlyForConfirmedAnswersAndKeepsNegativeOrUnknownChoices() {
        for (String answer : List.of("不需要代码，仅交付报告。", "暂不确定。", "当前材料中已有 Python 脚本。")) {
            var decisions = ConfirmedDecisionSet.from(List.of(new PlanAnswer("code", "是否需要提供代码实现？", answer)));
            var intent = TaskIntentResolver.resolve(TemplateCode.AUTO, "制定统计分析方案。", decisions.knownDecisions());
            assertThat(intent.engineeringConstraints()).as(answer).isFalse();
        }
        var result = assemble("制定统计分析方案。", List.of(new PlanAnswer("code", "是否需要代码实现？", "需要。")),
                "不得编造数据。", PermissionPolicyInput.empty());
        assertThat(platformRules(result.optimizedPrompt())).containsExactlyElementsOf(CODE_RULES);
        assertThat(result.templateCode()).isEqualTo(TemplateCode.RESEARCH_ANALYSIS);
        assertThat(result.optimizedPrompt()).contains("仅对本次明确交付的代码").doesNotContain("使用 Java 21");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "本次交付不包含可运行的 R 脚本，仅提供中文报告和空表。|false",
            "最终交付内容不含 R 脚本，仅提供中文报告和空表。|false",
            "本次交付包含可运行的 R 脚本，与中文报告和空表一并提供。|true",
            "不提供 Python 代码，但提供 R 脚本。|true"
    })
    void respectsRealPlanDeliveryStatementsWithoutPromotingTheResearchTask(String answer, boolean code) {
        String raw = "根据资料制定月度数据质量分析方案，交付中文报告和空表。"
                + "不计算真实结果，缺失值保持缺失，不得补零。"
                + "是否额外提供可运行的 R 脚本尚未确定，请在 Plan 阶段询问是否交付代码。";
        var result = assemble(raw, List.of(new PlanAnswer("deliver_r_script", "本次交付是否包含可运行的 R 脚本？", answer)),
                "缺失值保持缺失，不得补零，不计算真实结果。", PermissionPolicyInput.empty());
        assertThat(platformRules(result.optimizedPrompt())).containsExactlyElementsOf(code ? CODE_RULES : CODE_RULES.subList(0, 1));
        assertThat(result.templateCode()).isEqualTo(TemplateCode.RESEARCH_ANALYSIS);
        assertThat(result.optimizedPrompt()).contains("不得补零", "不计算真实结果").doesNotContain("使用 Java 21");
        if (!code) assertThat(result.optimizedPrompt()).doesNotContain("仅对本次明确交付的代码");
    }

    @Test
    void regroupsMockRulesAndKeepsAppliedConstraintsInTheCopiedSection() {
        String raw = "开发订单接口。";
        var result = assemble(raw, List.of(), "- " + String.join("\n- ", CODE_RULES)
                + "\n- 校验所有外部输入，并明确处理空值、非法值和边界条件。", PermissionPolicyInput.empty());
        assertThat(platformRules(result.optimizedPrompt())).containsExactlyElementsOf(CODE_RULES);
        var section = result.sections().stream().filter(item -> item.type() == PromptSectionType.CONSTRAINTS).findFirst().orElseThrow();
        assertThat(section.content()).contains("任务相关约束");
        result.appliedConstraints().forEach(rule -> assertThat(section.content()).contains(rule));
    }

    private com.promptoptimizer.enhancement.domain.OptimizationResult assemble(
            String raw, List<PlanAnswer> answers, String constraints, PermissionPolicyInput policy) {
        var context = new ContextSnapshot("材料中的 Java 项目", List.of(
                new TechnologyStackItem("Java 21", "pom.xml", 1),
                new TechnologyStackItem("Spring Boot 3", "pom.xml", 1)),
                List.of(), List.of(), List.of(), List.of(), List.of(), "v1");
        var decisions = ConfirmedDecisionSet.from(answers).knownDecisions();
        var template = new PromptTemplateRegistryImpl().resolve(TemplateCode.AUTO, raw, decisions);
        var intent = TaskIntentResolver.resolve(TemplateCode.AUTO, raw, decisions);
        var completed = new ConstraintCompleterImpl().completeForTask(context, policy, false, intent);
        var response = new EnhancementProviderResponse(List.of(
                new PromptSection(PromptSectionType.BACKGROUND, "背景", "采用已提供的资料。"),
                new PromptSection(PromptSectionType.TASK, "任务", "完成用户明确要求的交付物。"),
                new PromptSection(PromptSectionType.OUTPUT, "输出", "按用户要求交付。"),
                new PromptSection(PromptSectionType.CONSTRAINTS, "约束", constraints)), "mock", "model", true, List.of());
        return new OptimizationResultAssembler().assemble(response, context, template, List.of(), answers,
                !answers.isEmpty(), completed, false, 1, raw, List.of(), List.of());
    }

    private List<String> platformRules(String prompt) {
        String block = prompt.substring(prompt.lastIndexOf(HEADING) + HEADING.length()).stripLeading();
        return block.lines().takeWhile(line -> line.startsWith("- ")).map(line -> line.substring(2)).toList();
    }
}
