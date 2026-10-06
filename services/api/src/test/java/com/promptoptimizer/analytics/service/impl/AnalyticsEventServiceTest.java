package com.promptoptimizer.analytics.service.impl;

import com.promptoptimizer.analytics.service.AnalyticsEventService;
import com.promptoptimizer.analytics.domain.AnalyticsEventType;
import com.promptoptimizer.analytics.domain.GeoLocation;
import com.promptoptimizer.analytics.infrastructure.AnalyticsSessionContext;
import com.promptoptimizer.analytics.infrastructure.AuditEventDelivery;
import com.promptoptimizer.analytics.infrastructure.AuditEventJournal;
import com.promptoptimizer.analytics.infrastructure.AuditEventDatabaseWriter;
import com.promptoptimizer.analytics.domain.PendingAuditEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import com.promptoptimizer.analytics.mapper.AuditEventMapper;
import com.promptoptimizer.analytics.infrastructure.ClientIpResolver;
import com.promptoptimizer.analytics.infrastructure.DeviceTypeResolver;
import com.promptoptimizer.analytics.infrastructure.GeoLocationResolver;
import com.promptoptimizer.common.web.RequestIdFilter;
import com.promptoptimizer.identity.service.ActorIdentity;
import com.promptoptimizer.identity.service.CurrentActor;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 验证审计日志由当前账号派生，GeoIP 失败不阻断记录且持久化故障不会中断主业务。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@ExtendWith(OutputCaptureExtension.class)
class AnalyticsEventServiceTest {
    @TempDir Path journalDirectory;
    private AuditEventDelivery delivery;

    private static final ActorIdentity ACTOR = new ActorIdentity(
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
            UUID.fromString("33333333-3333-4333-8333-333333333333"),
            "admin@example.com",
            "Platform Admin"
    );

    @Test
    void directSubmissionKeepsGenericEventAndSubtypeAtTheSameUtcInstant() {
        CurrentActor actor = mock(CurrentActor.class);
        when(actor.require()).thenReturn(ACTOR);
        AuditEventDelivery sink = mock(AuditEventDelivery.class);
        AnalyticsEventService service = eventService(actor, sink);
        MockHttpServletRequest request = authenticatedRequest();
        request.setContent("sensitive prompt and answers must not enter audit".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        service.recordOptimizationSubmission(true, request);

        ArgumentCaptor<PendingAuditEvent> captured = ArgumentCaptor.forClass(PendingAuditEvent.class);
        verify(sink, times(2)).accept(captured.capture(), eq(false));
        var generic = captured.getAllValues().get(0);
        var direct = captured.getAllValues().get(1);
        assertThat(generic.eventType()).isEqualTo(AnalyticsEventType.OPTIMIZATION_SUBMITTED);
        assertThat(direct.eventType()).isEqualTo(AnalyticsEventType.DIRECT_OPTIMIZATION_SUBMITTED);
        assertThat(direct.occurredAt()).isEqualTo(generic.occurredAt());
        assertThat(direct.occurredAt().getOffset()).isEqualTo(java.time.ZoneOffset.UTC);
        assertThat(direct.id()).isNotEqualTo(generic.id());
        assertThat(direct.tenantId()).isEqualTo(ACTOR.tenantId());
        assertThat(direct.actorUserId()).isEqualTo(ACTOR.userId());
        assertThat(direct.details()).isEqualTo(generic.details()).doesNotContainKeys("planId", "prompt", "answers", "files");
        assertThat(direct.details().toString()).doesNotContain("sensitive prompt");
    }

    @Test
    void planSubmissionIsOnlyGenericAndCompletionHasStableScopedIdentity() {
        CurrentActor actor = mock(CurrentActor.class);
        when(actor.require()).thenReturn(ACTOR);
        AuditEventDelivery sink = mock(AuditEventDelivery.class);
        AnalyticsEventService service = eventService(actor, sink);
        MockHttpServletRequest request = authenticatedRequest();
        service.recordOptimizationSubmission(false, request);
        String planId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
        String secondPlanId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
        service.recordPlanCompleted(planId, request);
        service.recordPlanCompleted(planId.toUpperCase(java.util.Locale.ROOT), authenticatedRequest());
        service.recordPlanCompleted(secondPlanId, request);
        ActorIdentity anotherAccount = new ActorIdentity(UUID.randomUUID(), ACTOR.tenantId(), ACTOR.workspaceId(), "other@example.test", "Other");
        when(actor.require()).thenReturn(anotherAccount);
        service.recordPlanCompleted(planId, request);
        ActorIdentity anotherTenant = new ActorIdentity(ACTOR.userId(), UUID.randomUUID(), ACTOR.workspaceId(), "other@example.test", "Other");
        when(actor.require()).thenReturn(anotherTenant);
        service.recordPlanCompleted(planId, request);

        ArgumentCaptor<PendingAuditEvent> captured = ArgumentCaptor.forClass(PendingAuditEvent.class);
        verify(sink, times(6)).accept(captured.capture(), eq(false));
        var events = captured.getAllValues();
        assertThat(events.getFirst().eventType()).isEqualTo(AnalyticsEventType.OPTIMIZATION_SUBMITTED);
        assertThat(events.subList(1, 6)).allSatisfy(event -> {
            assertThat(event.eventType()).isEqualTo(AnalyticsEventType.PLAN_COMPLETED);
            assertThat(event.details()).doesNotContainKeys("planId", "prompt", "answers", "files");
            assertThat(event.details().toString()).doesNotContain(planId, secondPlanId);
        });
        assertThat(events.get(1).id()).isEqualTo(events.get(2).id());
        assertThat(java.util.Set.of(events.get(1).id(), events.get(3).id(), events.get(4).id(), events.get(5).id())).hasSize(4);
    }

    @Test
    void invalidPlanIdentifiersNeverCreateAuditEvents() {
        CurrentActor actor = mock(CurrentActor.class);
        AuditEventDelivery sink = mock(AuditEventDelivery.class);
        AnalyticsEventService service = eventService(actor, sink);
        for (String id : new String[]{null, "", " ", "x".repeat(36), "x".repeat(65), "1-1-1-1-1"}) {
            assertThatThrownBy(() -> service.recordPlanCompleted(id, authenticatedRequest()))
                    .isInstanceOf(com.promptoptimizer.common.exception.InvalidOptimizationRequestException.class);
        }
        verifyNoInteractions(actor, sink);
    }

    /** 使用内存投递替身观察采集边界，不能从客户端请求体派生审计字段。 */
    private AnalyticsEventService eventService(CurrentActor actor, AuditEventDelivery sink) {
        return new AnalyticsEventServiceImpl(actor, sink, new ClientIpResolver(""), ip -> GeoLocation.unavailable(),
                new DeviceTypeResolver(), new AnalyticsSessionContext());
    }

    @Test
    void storesIpAndNullGeoWithoutStoringRawAgentOrCredentials() {
        CurrentActor actor = mock(CurrentActor.class);
        when(actor.require()).thenReturn(ACTOR);
        AuditEventMapper audit = mock(AuditEventMapper.class);
        AtomicReference<Map<String, Object>> captured = new AtomicReference<>();
        doAnswer(invocation -> {
            captured.set(invocation.getArgument(4));
            return 1;
        }).when(audit).insert(any(UUID.class), eq(ACTOR.tenantId()), eq(ACTOR.userId()),
                eq(AnalyticsEventType.LOGIN), any(), any(OffsetDateTime.class));
        GeoLocationResolver unavailableGeo = ip -> {
            throw new IllegalStateException("local lookup failed");
        };
        AnalyticsEventService service = service(actor, audit, unavailableGeo);
        MockHttpServletRequest request = authenticatedRequest();

        service.recordLogin(request);
        delivery.replayPending();

        assertThat(captured.get()).containsEntry("clientIp", "198.51.100.23")
                .containsEntry("country", null)
                .containsEntry("province", null)
                .containsEntry("city", null)
                .containsEntry("deviceType", "MOBILE")
                .containsEntry("requestId", "request-safe-id")
                .containsKey("loginSessionId");
        assertThat(captured.get()).doesNotContainKeys("userAgent", "password", "token", "apiKey");
        verify(audit).insert(any(UUID.class), eq(ACTOR.tenantId()), eq(ACTOR.userId()),
                eq(AnalyticsEventType.LOGIN), any(), any(OffsetDateTime.class));
    }

    @Test
    void auditDatabaseFailureDoesNotFailTheUserOperation(CapturedOutput output) {
        CurrentActor actor = mock(CurrentActor.class);
        when(actor.require()).thenReturn(ACTOR);
        AuditEventMapper audit = mock(AuditEventMapper.class);
        doThrow(new DataAccessResourceFailureException("database unavailable INSERT INTO audit_event"))
                .when(audit).insert(any(UUID.class), any(UUID.class), any(UUID.class), any(), any(), any());
        AnalyticsEventService service = service(actor, audit);

        assertThatCode(() -> service.record(AnalyticsEventType.OPTIMIZATION_SUBMITTED, authenticatedRequest()))
                .doesNotThrowAnyException();
        delivery.replayPending();
        assertThat(delivery.status().pendingEvents()).isEqualTo(1);
        assertThat(output).contains("event=analytics.audit_write_failure")
                .contains("requestId=request-safe-id")
                .contains("eventType=OPTIMIZATION_SUBMITTED")
                .contains("reason=DataAccessResourceFailureException")
                .contains("sqlState=unavailable")
                .contains("constraint=unavailable")
                .doesNotContain("database unavailable")
                .doesNotContain("INSERT INTO")
                .doesNotContain("198.51.100.23");
    }

    @Test
    void auditIntegrityFailureLogsSqlStateAndConstraintWithoutSqlOrRowValues(CapturedOutput output) {
        CurrentActor actor = mock(CurrentActor.class);
        when(actor.require()).thenReturn(ACTOR);
        AuditEventMapper audit = mock(AuditEventMapper.class);
        ServerErrorMessage serverError = new ServerErrorMessage(
                "SERROR\0C23503\0Minsert or update on table \"audit_event\" violates foreign key constraint \"audit_event_actor_user_id_fkey\"\0naudit_event_actor_user_id_fkey\0DKey (actor_user_id)=(secret-user) is not present in table \"user_account\".\0"
        );
        DataIntegrityViolationException failure = new DataIntegrityViolationException(
                "PreparedStatementCallback; SQL [INSERT INTO audit_event (raw_prompt) VALUES ('secret prompt')]; ERROR",
                new PSQLException(serverError)
        );
        doThrow(failure).when(audit).insert(any(UUID.class), any(UUID.class), any(UUID.class), any(), any(), any());
        AnalyticsEventService service = service(actor, audit);

        assertThatCode(() -> service.record(AnalyticsEventType.LOGIN, authenticatedRequest()))
                .doesNotThrowAnyException();
        delivery.replayPending();
        assertThat(delivery.status().pendingEvents()).isEqualTo(1);
        assertThat(output).contains("event=analytics.audit_write_failure")
                .contains("eventType=LOGIN")
                .contains("reason=DataIntegrityViolationException")
                .contains("sqlState=23503")
                .contains("constraint=audit_event_actor_user_id_fkey")
                .doesNotContain("INSERT INTO")
                .doesNotContain("secret prompt")
                .doesNotContain("secret-user")
                .doesNotContain("198.51.100.23");
    }

    private AnalyticsEventService service(CurrentActor actor, AuditEventMapper audit) {
        return service(actor, audit, ip -> GeoLocation.unavailable());
    }

    private AnalyticsEventService service(
            CurrentActor actor,
            AuditEventMapper audit,
            GeoLocationResolver geoLocationResolver
    ) {
        delivery = new AuditEventDelivery(new AuditEventJournal(journalDirectory.toString(), new ObjectMapper().findAndRegisterModules()),
                new AuditEventDatabaseWriter(provider(audit)), 100, 100, 1000, 1000, 300, provider(null));
        return new AnalyticsEventServiceImpl(
                actor,
                delivery,
                new ClientIpResolver(""),
                geoLocationResolver,
                new DeviceTypeResolver(),
                new AnalyticsSessionContext()
        );
    }

    private MockHttpServletRequest authenticatedRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.23");
        request.addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile)");
        request.setAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE, "request-safe-id");
        return request;
    }

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
