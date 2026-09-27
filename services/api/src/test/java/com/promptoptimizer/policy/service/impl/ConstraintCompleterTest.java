package com.promptoptimizer.policy.service.impl;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.context.domain.TechnologyStackItem;
import com.promptoptimizer.enhancement.dto.PermissionPolicyInput;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConstraintCompleterTest {

    private final ConstraintCompleter completer = new ConstraintCompleter();

    @Test
    void shouldRecognizeVersionedStackNamesAndKeepPlatformRedlinesEnabled() {
        ContextSnapshot context = new ContextSnapshot(
                "",
                List.of(
                        new TechnologyStackItem("Java 21", "pom.xml", 1),
                        new TechnologyStackItem("Spring Boot 3", "pom.xml", 1),
                        new TechnologyStackItem("PostgreSQL 16", "compose.yml", 1),
                        new TechnologyStackItem("Redis 7", "compose.yml", 1)
                ),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "v1"
        );

        List<String> constraints = completer.complete(
                context,
                new PermissionPolicyInput(List.of("research/private/**"), List.of("发布研究数据")),
                false,
                TemplateCode.FEATURE_DEVELOPMENT
        );

        assertThat(constraints).anySatisfy(value -> assertThat(value).contains("Java 21"));
        assertThat(constraints).anySatisfy(value -> assertThat(value).contains("Spring Boot 3"));
        assertThat(constraints).anySatisfy(value -> assertThat(value).contains("参数化查询或 ORM"));
        assertThat(constraints).anySatisfy(value -> assertThat(value).contains("Redis", "TTL"));
        assertThat(constraints).anySatisfy(value -> assertThat(value)
                .contains(".env", "**/*.pem", "research/private/**"));
        assertThat(constraints).anySatisfy(value -> assertThat(value)
                .contains("数据库结构迁移", "发布研究数据"));
    }
}
