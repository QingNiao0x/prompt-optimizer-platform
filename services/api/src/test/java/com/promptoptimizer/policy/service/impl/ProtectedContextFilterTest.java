package com.promptoptimizer.policy.service.impl;

import com.promptoptimizer.context.dto.ContextAnalysisRequest;
import com.promptoptimizer.context.dto.ContextFileInput;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.dto.PermissionPolicyInput;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProtectedContextFilterTest {

    private final ProtectedContextFilter filter = new ProtectedContextFilter();

    @Test
    void shouldFilterDefaultAndCustomProtectedPathsBeforeAnalysis() {
        var filtered = filter.filter(
                new ContextAnalysisRequest("", List.of(
                        new ContextFileInput(".env", "SECRET=value", "text"),
                        new ContextFileInput("research/private/patients.csv", "sensitive", "csv"),
                        new ContextFileInput("research/summary.csv", "safe", "csv")
                )),
                new PermissionPolicyInput(List.of("research/private/**"), List.of())
        );

        assertThat(filtered.request().files()).extracting("path")
                .containsExactly("research/summary.csv");
        assertThat(filtered.protectedPaths())
                .containsExactly(".env", "research/private/patients.csv");

        ContextSnapshot attached = filter.attachReport(
                new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "v1"),
                filtered
        );
        assertThat(attached.analysisStatus()).isEqualTo("PARTIAL");
        assertThat(attached.redactions()).contains(".env", "research/private/patients.csv");
        assertThat(attached.fileSnippets()).isEmpty();
    }
}
