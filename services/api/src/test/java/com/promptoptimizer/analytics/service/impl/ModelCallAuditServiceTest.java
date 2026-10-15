package com.promptoptimizer.analytics.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.analytics.domain.AnalyticsEventType;
import com.promptoptimizer.analytics.domain.PendingAuditEvent;
import com.promptoptimizer.analytics.infrastructure.AuditEventDelivery;
import com.promptoptimizer.common.logging.ModelCallLogger;
import com.promptoptimizer.common.logging.ModelCallObservation;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证匿名模型事实白名单，不放宽现有账号事件的身份约束。 */
class ModelCallAuditServiceTest {
    @Test
    void storesAnonymousNumericFactAndJournalRoundTripPreservesId() throws Exception {
        var delivery = mock(AuditEventDelivery.class);
        var service = new ModelCallAuditService(delivery);
        UUID tenant = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        service.record(tenant, new ModelCallObservation(id, OffsetDateTime.now(), UUID.randomUUID().toString(),
                "0123456789abcdefabcd", "prompt.optimize", "primary", "test-model", 1, false, false,
                "INVALID_RESPONSE", 200, true, "RESPONSE_RECEIVED", 15,
                new ModelCallLogger.TokenUsage(10L, 3L, 13L, 0L, 10L),
                "ASSESSMENT_DECISION_CONFLICT", "assessment.gaps"));
        var captor = ArgumentCaptor.forClass(PendingAuditEvent.class);
        verify(delivery).accept(captor.capture(), eq(false));
        PendingAuditEvent fact = captor.getValue();
        assertThat(fact.actorUserId()).isNull();
        assertThat(fact.tenantId()).isEqualTo(tenant);
        assertThat(fact.eventType()).isEqualTo(AnalyticsEventType.MODEL_CALL_RECORDED);
        assertThat(fact.details()).doesNotContainKeys("userId", "email", "clientIp", "loginSessionId", "resourceId",
                "prompt", "content", "answer", "apiKey", "authorization");
        assertThat(fact.details()).containsEntry("totalTokens", 13L).containsEntry("cacheState", "MISS");
        assertThat(fact.details()).containsEntry("validationReason", "ASSESSMENT_DECISION_CONFLICT")
                .containsEntry("validationField", "assessment.gaps");
        var json = new ObjectMapper().findAndRegisterModules();
        PendingAuditEvent replay = json.readValue(json.writeValueAsBytes(fact), PendingAuditEvent.class);
        assertThat(replay.id()).isEqualTo(id);
        assertThat(replay.actorUserId()).isNull();
        assertThat(replay.details()).containsEntry("validationReason", "ASSESSMENT_DECISION_CONFLICT")
                .containsEntry("validationField", "assessment.gaps");
    }

    @Test
    void modelEventForbidsAnActorAndOtherEventsStillRequireOne() {
        assertThatThrownBy(() -> new PendingAuditEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                AnalyticsEventType.MODEL_CALL_RECORDED, Map.of(), OffsetDateTime.now())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PendingAuditEvent(UUID.randomUUID(), UUID.randomUUID(), null,
                AnalyticsEventType.LOGIN, Map.of(), OffsetDateTime.now())).isInstanceOf(NullPointerException.class);
    }
}
