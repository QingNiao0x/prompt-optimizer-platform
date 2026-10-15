package com.promptoptimizer.common.logging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Executors;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** 验证逐次计量、失败响应保留、线程边界和不携带用户正文的采集契约。 */
class ModelCallReconciliationTest {
    @AfterEach void clearMdc() { MDC.clear(); }

    @Test
    void failedParsingKeepsUsageAndRetriesPublishDistinctFactsOnlyOnce() {
        var facts = new ArrayList<ModelCallObservation>();
        UUID tenant = UUID.randomUUID();
        try (var context = ModelCallAuditContext.bind(new ModelCallAuditContext.Snapshot(tenant, (id, fact) -> {
            assertThat(id).isEqualTo(tenant);
            facts.add(fact);
        }))) {
            try (var attempt = ModelCallLogger.beginAttempt()) {
                ModelCallLogger.captureTokenUsage(new ModelCallLogger.TokenUsage(10L, 5L, 15L, 4L, 6L));
                ModelCallLogger.captureValidationFailure(
                        com.promptoptimizer.provider.domain.ProviderResponseValidationException.Reason.ASSESSMENT_DECISION_CONFLICT,
                        "assessment.gaps");
                ModelCallLogger.failed("prompt.optimize", "primary", "test-model", "PLATFORM_CATALOG",
                        "INVALID_RESPONSE", false, null, true, 1, 1, 10);
                // 同一终态的误重复上报不增加事实；下次修复尝试必须生成新的 UUID。
                ModelCallLogger.failed("prompt.optimize", "primary", "test-model", "PLATFORM_CATALOG",
                        "INVALID_RESPONSE", false, null, true, 1, 1, 10);
            }
            try (var attempt = ModelCallLogger.beginAttempt()) {
                ModelCallLogger.completed("prompt.optimize", "primary", "test-model", "PLATFORM_CATALOG", false,
                        2, 1, 15, new ModelCallLogger.TokenUsage(20L, 10L, 30L));
            }
        }
        assertThat(facts).hasSize(2).extracting(ModelCallObservation::attempt).containsExactly(1, 2);
        assertThat(facts).extracting(ModelCallObservation::eventId).doesNotHaveDuplicates();
        assertThat(facts.getFirst().usage().totalTokens()).isEqualTo(15);
        assertThat(facts.getFirst().usage().cacheState()).isEqualTo("MIXED");
        assertThat(facts.getFirst().successful()).isFalse();
        assertThat(facts.getFirst().validationReason()).isEqualTo("ASSESSMENT_DECISION_CONFLICT");
        assertThat(facts.getFirst().validationField()).isEqualTo("assessment.gaps");
        assertThat(facts.getLast().validationReason()).isNull();
        assertThat(facts.getLast().validationField()).isNull();
        assertThat(ModelCallAuditContext.capture()).isNull();
    }

    @Test
    void diagnosticsRejectUnknownTextAndDoNotAttachFailureMetadataToSuccess() {
        var now = java.time.OffsetDateTime.now();
        var unknown = new ModelCallObservation(UUID.randomUUID(), now, null, null, "prompt.optimize", "primary", "model",
                1, false, false, "INVALID_RESPONSE", 200, false, "RESPONSE_RECEIVED", 1, null,
                "private-model-response", "assessment.privateData");
        assertThat(unknown.validationReason()).isNull();
        assertThat(unknown.validationField()).isNull();
        var unsafeField = new ModelCallObservation(UUID.randomUUID(), now, null, null, "prompt.optimize", "primary", "model",
                1, false, false, "INVALID_RESPONSE", 200, false, "RESPONSE_RECEIVED", 1, null,
                "ASSESSMENT_DECISION_CONFLICT", "assessment.privateData");
        assertThat(unsafeField.validationReason()).isEqualTo("ASSESSMENT_DECISION_CONFLICT");
        assertThat(unsafeField.validationField()).isNull();
        var success = new ModelCallObservation(UUID.randomUUID(), now, null, null, "prompt.optimize", "primary", "model",
                2, false, true, null, 200, false, "RESPONSE_RECEIVED", 1, null,
                "ASSESSMENT_DECISION_CONFLICT", "assessment.gaps");
        assertThat(success.validationReason()).isNull();
        assertThat(success.validationField()).isNull();
    }

    @Test
    void unknownFailedAttemptDoesNotReusePreviousTokensAndLocalRejectIsNotSent() {
        var facts = new ArrayList<ModelCallObservation>();
        try (var scope = ModelCallAuditContext.bind(new ModelCallAuditContext.Snapshot(UUID.randomUUID(), (t, c) -> facts.add(c)))) {
            try (var attempt = ModelCallLogger.beginAttempt()) {
                ModelCallLogger.captureTokenUsage(new ModelCallLogger.TokenUsage(1L, 2L, 3L));
                ModelCallLogger.completed("plan.generate", "primary", "test-model", "PLATFORM_DEFAULT", false, 1, 1, 1, null);
            }
            try (var attempt = ModelCallLogger.beginAttempt()) {
                ModelCallLogger.transportPending();
                ModelCallLogger.failed("plan.generate", "primary", "test-model", "PLATFORM_DEFAULT",
                        "MODEL_CONCURRENCY_LIMIT", false, null, false, 2, 1, 1);
            }
        }
        assertThat(facts.get(1).usage()).isNull();
        assertThat(facts.get(1).transportState()).isEqualTo("NOT_SENT");
    }

    @Test
    void asyncSnapshotRestoresThreadAndNoIdentityIsCopied() throws Exception {
        var snapshot = new ModelCallAuditContext.Snapshot(UUID.randomUUID(), (t, c) -> { });
        try (var executor = Executors.newSingleThreadExecutor(); var scope = ModelCallAuditContext.bind(snapshot)) {
            assertThat(executor.submit(() -> ModelCallAuditContext.capture()).get()).isNull();
            assertThat(executor.submit(() -> {
                try (var worker = ModelCallAuditContext.bind(snapshot)) { return ModelCallAuditContext.capture(); }
            }).get()).isSameAs(snapshot);
            assertThat(executor.submit(() -> ModelCallAuditContext.capture()).get()).isNull();
        }
    }

    @Test
    void ledgerSanitizesCorrelationsAndInvalidTokensWithoutInventingUsage() {
        var observation = new ModelCallObservation(UUID.randomUUID(), java.time.OffsetDateTime.now(),
                "raw-user-content", "raw-plan-id", "raw-prompt", "https://private-endpoint", "model\nsecret=value",
                1, false, false, "raw-exception-message", null, false, "UNKNOWN", 0, null);
        assertThat(observation.requestId()).matches("hash-[a-f0-9]{20}");
        assertThat(observation.workflowId()).isNull();
        assertThat(observation.operation()).isEqualTo("unknown");
        assertThat(observation.providerRoute()).isEqualTo("unknown");
        assertThat(observation.model()).isEqualTo("unknown");
        assertThat(observation.failureType()).isEqualTo("UNEXPECTED");
        assertThat(new ModelCallLogger.TokenUsage(-1L, 1L, 0L).usageState()).isEqualTo("INVALID");
        assertThat(new ModelCallLogger.TokenUsage(Long.MAX_VALUE, 1L, Long.MAX_VALUE).usageState()).isEqualTo("INVALID");
        assertThat(new ModelCallLogger.TokenUsage(1L, 2L, 10L).usageState()).isEqualTo("INVALID");
        assertThat(new ModelCallLogger.TokenUsage(null, null, null).usageState()).isEqualTo("UNKNOWN");
        assertThat(new ModelCallLogger.TokenUsage(2L, null, 2L).usageState()).isEqualTo("PARTIAL");
        assertThat(new ModelCallLogger.TokenUsage(2L, 1L, 3L).cacheState()).isEqualTo("UNKNOWN");
    }

    @Test
    void captureFailureDoesNotChangeModelSuccess() {
        try (var scope = ModelCallAuditContext.bind(new ModelCallAuditContext.Snapshot(UUID.randomUUID(),
                (t, c) -> { throw new IllegalStateException("sensitive details never logged"); }))) {
            assertThatCode(() -> ModelCallLogger.completed("prompt.optimize", "primary", "test-model",
                    "PLATFORM_DEFAULT", false, 1, 1, 1, null)).doesNotThrowAnyException();
        }
    }
}
