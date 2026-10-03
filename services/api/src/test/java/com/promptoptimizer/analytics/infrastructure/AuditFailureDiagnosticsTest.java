package com.promptoptimizer.analytics.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证审计诊断保留安全错误分类，并拒绝把任意数据库消息带入控制台。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class AuditFailureDiagnosticsTest {
    @Test
    void classifiesForeignKeyFailureAndKeepsCauseLocationsWithoutMessages() {
        var failure = new DataIntegrityViolationException("private row", new SQLException("private SQL", "23503"));
        var diagnostics = AuditFailureDiagnostics.from(failure);
        assertThat(diagnostics.sqlState()).isEqualTo("23503");
        assertThat(diagnostics.requiresSlowRetry(failure)).isTrue();
        assertThat(diagnostics.explanation()).contains("租户或账号");
        assertThat(AuditFailureDiagnostics.safeTrace(failure)).contains("cause[0]", "cause[1]",
                "AuditFailureDiagnosticsTest", System.lineSeparator()).doesNotContain("private row", "private SQL");
    }

    @Test
    void unknownOrInvalidSqlStateDoesNotInventADatabaseDiagnosis() {
        var invalid = new SQLException("private", "unsafe\nstate");
        assertThat(AuditFailureDiagnostics.from(invalid).sqlState()).isEqualTo("unavailable");
        assertThat(AuditFailureDiagnostics.from(null).constraint()).isEqualTo("unavailable");
        var connection = new SQLException("private", "08006");
        assertThat(AuditFailureDiagnostics.from(connection).requiresSlowRetry(connection)).isFalse();
    }

    @Test
    void cyclicExceptionChainIsBounded() {
        RuntimeException first = new RuntimeException("private first");
        RuntimeException second = new RuntimeException("private second", first);
        first.initCause(second);
        assertThat(AuditFailureDiagnostics.safeTrace(first)).contains("cause[1]").doesNotContain("cause[2]", "private");
        assertThat(AuditFailureDiagnostics.from(first).sqlState()).isEqualTo("unavailable");
    }
}
