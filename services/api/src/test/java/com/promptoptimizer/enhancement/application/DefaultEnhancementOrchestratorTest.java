package com.promptoptimizer.enhancement.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.context.api.ContextAnalysisRequest;
import com.promptoptimizer.context.api.ContextFileInput;
import com.promptoptimizer.context.application.BinaryContentExtractor;
import com.promptoptimizer.context.application.DefaultContextAnalyzer;
import com.promptoptimizer.context.application.FileContentSummarizer;
import com.promptoptimizer.enhancement.api.EnhancementOptions;
import com.promptoptimizer.enhancement.api.OptimizationRequest;
import com.promptoptimizer.enhancement.api.PermissionPolicyInput;
import com.promptoptimizer.enhancement.api.PlanAnswer;
import com.promptoptimizer.enhancement.api.PlanConfirmation;
import com.promptoptimizer.enhancement.domain.OptimizationResult;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.policy.application.ConstraintCompleter;
import com.promptoptimizer.provider.infrastructure.MockPromptEnhancementProvider;
import com.promptoptimizer.template.application.PromptTemplateRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultEnhancementOrchestratorTest {

    private final DefaultEnhancementOrchestrator orchestrator = new DefaultEnhancementOrchestrator(
            new DefaultContextAnalyzer(
                    new ObjectMapper(),
                    new BinaryContentExtractor(),
                    new FileContentSummarizer()
            ),
            new AmbiguityDetector(),
            new PromptTemplateRegistry(),
            new ConstraintCompleter(),
            new MockPromptEnhancementProvider(),
            Clock.fixed(Instant.parse("2026-08-10T12:00:00Z"), ZoneOffset.UTC)
    );

    @Test
    void shouldEnhancePromptWithJavaContextAndSafetyConstraints() {
        OptimizationRequest request = new OptimizationRequest(
                "给用户模块添加登录功能",
                new ContextAnalysisRequest(
                        "这是一个 Spring Boot 用户服务。",
                        List.of(new ContextFileInput("backend/pom.xml", """
                                <project>
                                  <parent>spring-boot</parent>
                                  <dependencies>
                                    <dependency>
                                      <groupId>org.springframework.boot</groupId>
                                      <artifactId>spring-boot-starter-web</artifactId>
                                    </dependency>
                                    <dependency>
                                      <groupId>org.postgresql</groupId>
                                      <artifactId>postgresql</artifactId>
                                    </dependency>
                                  </dependencies>
                                </project>
                                """, "xml"))
                ),
                new EnhancementOptions(TemplateCode.AUTO, true, true, true),
                List.of(),
                new PermissionPolicyInput(List.of("config/prod.yml"), List.of("修改认证策略"))
        );

        OptimizationResult result = orchestrator.optimize(request);

        assertThat(result.templateCode()).isEqualTo(TemplateCode.FEATURE_DEVELOPMENT);
        assertThat(result.provider().mock()).isTrue();
        assertThat(result.sections()).extracting("type")
                .contains(
                        PromptSectionType.BACKGROUND,
                        PromptSectionType.TASK,
                        PromptSectionType.OUTPUT,
                        PromptSectionType.CONSTRAINTS,
                        PromptSectionType.CLARIFICATIONS,
                        PromptSectionType.ACCEPTANCE,
                        PromptSectionType.EXAMPLES
                );
        assertThat(result.optimizedPrompt())
                .contains("Spring Boot", "Bean Validation", "SQL 注入", "config/prod.yml")
                .doesNotContain("待确认项", "确认下方待确认项");
        assertThat(result.sections()).extracting("type")
                .contains(PromptSectionType.CLARIFICATIONS);
        assertThat(result.contextReport().technologyStack()).extracting("name")
                .contains("Java", "Spring Boot", "PostgreSQL");
    }

    @Test
    void shouldSelectBugFixTemplateWhenPromptDescribesFailure() {
        OptimizationRequest request = new OptimizationRequest(
                "修复登录接口在用户不存在时出现的空指针异常，并补充测试",
                new ContextAnalysisRequest("", List.of()),
                EnhancementOptions.defaults(),
                List.of(),
                PermissionPolicyInput.empty()
        );

        OptimizationResult result = orchestrator.optimize(request);

        assertThat(result.templateCode()).isEqualTo(TemplateCode.BUG_FIX);
        assertThat(result.optimizedPrompt()).contains("定位根因", "回归测试");
    }

    @Test
    void shouldUseConfirmedResearchAnswersAndReturnFinalPromptWithoutPendingItems() {
        OptimizationRequest request = new OptimizationRequest(
                "分析2015-2025年某地区心脑血管疾病死亡率并进行Arriaga分解",
                new ContextAnalysisRequest("公共卫生研究", List.of()),
                EnhancementOptions.defaults(),
                List.of(),
                PermissionPolicyInput.empty(),
                new PlanConfirmation(List.of(
                        new PlanAnswer("research-region", "这项研究具体覆盖哪个地区？", "广东省"),
                        new PlanAnswer("research-tool", "你希望使用哪种分析工具？", "使用 R 完成分析并提供代码。")
                ))
        );

        OptimizationResult result = orchestrator.optimize(request);

        assertThat(result.templateCode()).isEqualTo(TemplateCode.RESEARCH_ANALYSIS);
        assertThat(result.ambiguities()).isEmpty();
        assertThat(result.sections()).extracting("type")
                .doesNotContain(PromptSectionType.CLARIFICATIONS);
        assertThat(result.optimizedPrompt())
                .contains("广东省", "使用 R 完成分析", "数据来源", "偏倚", "不确定性")
                .doesNotContain("需求描述较短", "尚未明确输入", "开发任务", "未提供项目上下文");
    }
}
