package com.promptoptimizer.analytics.service.impl;

import com.promptoptimizer.analytics.domain.AnalyticsEventType;
import com.promptoptimizer.analytics.domain.ClientAnalyticsEventType;
import com.promptoptimizer.analytics.domain.GeoLocation;
import com.promptoptimizer.analytics.domain.PendingAuditEvent;
import com.promptoptimizer.analytics.dto.ClientAnalyticsEventRequest;
import com.promptoptimizer.analytics.infrastructure.AnalyticsSessionContext;
import com.promptoptimizer.analytics.infrastructure.AuditEventDelivery;
import com.promptoptimizer.analytics.infrastructure.ClientIpResolver;
import com.promptoptimizer.analytics.infrastructure.DeviceTypeResolver;
import com.promptoptimizer.analytics.infrastructure.GeoLocationResolver;
import com.promptoptimizer.analytics.service.AnalyticsDeliveryUnavailableException;
import com.promptoptimizer.analytics.service.AnalyticsIdentityChangedException;
import com.promptoptimizer.common.exception.InvalidOptimizationRequestException;
import com.promptoptimizer.identity.service.ActorIdentity;
import com.promptoptimizer.identity.service.CurrentActor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 验证浏览器离线重放的账号绑定、原发生时刻、登录位置与可靠接收错误契约。
 * 文件和数据库投递另由故障演练及真实库验收覆盖，本类聚焦采集边界。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class AnalyticsClientEventServiceTest {
    private final ActorIdentity actor = new ActorIdentity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            "fixture@example.test", "Fixture");
    private final AuditEventDelivery delivery = mock(AuditEventDelivery.class);
    private final GeoLocationResolver geo = mock(GeoLocationResolver.class);
    private final AnalyticsEventServiceImpl service = service(actor);

    @Test
    void retiredPageVisitsDoNotResolveMetadataCreateSessionsOrEnterDelivery() {
        MockHttpServletRequest request = request();
        service.recordClientEvent(event(ClientAnalyticsEventType.APP_VISIT, UUID.randomUUID(), historicalTime(),
                actor.userId(), null), request);
        service.record(AnalyticsEventType.APP_VISIT, request);
        verifyNoInteractions(delivery, geo);
        assertThat(request.getSession(false)).isNull();
    }

    @Test
    void accountChangeRejectsBeforeReadingCurrentLocationOrAcceptingOldEvent() {
        var event = event(ClientAnalyticsEventType.APP_VISIT, UUID.randomUUID(), historicalTime(), UUID.randomUUID(), null);
        assertThatThrownBy(() -> service.recordClientEvent(event, request()))
                .isInstanceOf(AnalyticsIdentityChangedException.class);
        verifyNoInteractions(delivery, geo);
    }

    @Test
    void nullTypeAndUnsupportedOrFutureTimesAreRejectedBeforeDelivery() {
        assertThatThrownBy(() -> service.recordClientEvent(event(null, UUID.randomUUID(), historicalTime(), actor.userId(), null), request()))
                .isInstanceOf(InvalidOptimizationRequestException.class);
        for (OffsetDateTime invalid : new OffsetDateTime[]{
                OffsetDateTime.of(0, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC),
                OffsetDateTime.of(10000, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC),
                OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10)}) {
            assertThatThrownBy(() -> service.recordClientEvent(
                    event(ClientAnalyticsEventType.APP_VISIT, UUID.randomUUID(), invalid, actor.userId(), null), request()))
                    .isInstanceOf(InvalidOptimizationRequestException.class);
        }
        verifyNoInteractions(delivery, geo);
    }

    @Test
    void historicalReplayKeepsOriginalInstantAndDoesNotInventNewSessionLocation() {
        for (UUID oldSession : new UUID[]{null, UUID.randomUUID()}) {
            service.recordClientEvent(event(ClientAnalyticsEventType.RESULT_EXPORTED, UUID.randomUUID(), historicalTime(),
                    actor.userId(), oldSession), request());
        }
        var accepted = ArgumentCaptor.forClass(PendingAuditEvent.class);
        verify(delivery, times(2)).accept(accepted.capture(), eq(true));
        for (PendingAuditEvent pending : accepted.getAllValues()) {
            assertThat(pending.actorUserId()).isEqualTo(actor.userId());
            assertThat(pending.tenantId()).isEqualTo(actor.tenantId());
            assertThat(pending.occurredAt().toInstant()).isEqualTo(historicalTime().toInstant());
            assertThat(pending.occurredAt().getOffset()).isEqualTo(ZoneOffset.UTC);
            assertThat(pending.details()).containsEntry("clientIp", null).containsEntry("country", null)
                    .containsEntry("province", null).containsEntry("city", null).containsEntry("loginSessionId", null)
                    .containsEntry("loginIp", null).containsEntry("loginCountry", null)
                    .containsEntry("loginProvince", null).containsEntry("loginCity", null)
                    .containsEntry("deviceType", "UNKNOWN").containsEntry("delayedMetadata", true);
        }
        verifyNoInteractions(geo);
    }

    @Test
    void matchingLoginContextRetainsLoginSnapshotWithoutAuthenticationCredentials() {
        MockHttpServletRequest request = request();
        when(geo.resolve("198.51.100.24")).thenReturn(new GeoLocation("Country", "Province", "City"));
        service.recordLogin(request);
        UUID loginSession = service.clientContext(request).loginSessionId();
        clearInvocations(delivery);
        service.recordClientEvent(event(ClientAnalyticsEventType.RESULT_EXPORTED, UUID.randomUUID(), historicalTime(),
                actor.userId(), loginSession), request);
        var accepted = ArgumentCaptor.forClass(PendingAuditEvent.class);
        verify(delivery).accept(accepted.capture(), eq(true));
        assertThat(accepted.getValue().details()).containsEntry("clientIp", "198.51.100.24")
                .containsEntry("loginIp", "198.51.100.24").containsEntry("loginCountry", "Country")
                .containsEntry("loginProvince", "Province").containsEntry("loginCity", "City")
                .containsEntry("loginSessionId", loginSession.toString())
                .doesNotContainKeys("password", "token", "apiKey", "sessionId", "userAgent");
        assertThat(loginSession.toString()).isNotEqualTo(request.getSession().getId());
    }

    @Test
    void stableClientIdIsScopedByServerAccountAndTenant() {
        UUID clientId = UUID.randomUUID();
        service.recordClientEvent(event(ClientAnalyticsEventType.RESULT_EXPORTED, clientId, historicalTime(), actor.userId(), null), request());
        service.recordClientEvent(event(ClientAnalyticsEventType.RESULT_EXPORTED, clientId, historicalTime().plusDays(1), actor.userId(), null), request());
        ActorIdentity otherUser = new ActorIdentity(UUID.randomUUID(), actor.tenantId(), actor.workspaceId(), "other@example.test", "Other");
        service(otherUser).recordClientEvent(event(ClientAnalyticsEventType.RESULT_EXPORTED, clientId, historicalTime(), otherUser.userId(), null), request());
        ActorIdentity otherTenant = new ActorIdentity(actor.userId(), UUID.randomUUID(), actor.workspaceId(), "other@example.test", "Other");
        service(otherTenant).recordClientEvent(event(ClientAnalyticsEventType.RESULT_EXPORTED, clientId, historicalTime(), otherTenant.userId(), null), request());
        var accepted = ArgumentCaptor.forClass(PendingAuditEvent.class);
        verify(delivery, times(4)).accept(accepted.capture(), eq(true));
        var ids = accepted.getAllValues().stream().map(PendingAuditEvent::id).toList();
        assertThat(ids.get(0)).isEqualTo(ids.get(1)).isNotEqualTo(clientId);
        assertThat(ids.subList(1, 4)).doesNotHaveDuplicates();
    }

    @Test
    void legacyRealtimeEventRemainsCompatibleAndUsesServerTime() {
        MockHttpServletRequest request = request();
        OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC);
        service.recordClientEvent(ClientAnalyticsEventType.RESULT_EXPORTED, request);
        var accepted = ArgumentCaptor.forClass(PendingAuditEvent.class);
        verify(delivery).accept(accepted.capture(), eq(true));
        assertThat(accepted.getValue().eventType()).isEqualTo(AnalyticsEventType.RESULT_EXPORTED);
        assertThat(accepted.getValue().occurredAt()).isAfterOrEqualTo(before).isBeforeOrEqualTo(OffsetDateTime.now(ZoneOffset.UTC));
        assertThat(accepted.getValue().details()).containsEntry("clientIp", "198.51.100.24");
    }

    @Test
    void reliableReceiptFailureRemainsRetryableInsteadOfPretendingSuccess() {
        doThrow(new AnalyticsDeliveryUnavailableException(new IllegalStateException("private details")))
                .when(delivery).accept(any(), eq(true));
        assertThatThrownBy(() -> service.recordClientEvent(
                event(ClientAnalyticsEventType.RESULT_EXPORTED, UUID.randomUUID(), historicalTime(), actor.userId(), null), request()))
                .isInstanceOf(AnalyticsDeliveryUnavailableException.class)
                .hasMessage("统计事件暂未接收，请稍后重试。");
    }

    @Test
    void toolEventsUseStableAccountScopedIdsAndOnlySafeHttpMetadata() {
        UUID operation = UUID.randomUUID();
        var request = request();
        request.setAttribute(com.promptoptimizer.common.web.RequestIdFilter.REQUEST_ID_ATTRIBUTE, "fixture-request");
        request.setContent("private article and image content".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            service.recordToolOperation(AnalyticsEventType.IMAGE_PROMPT_SUBMITTED, operation);
            service.recordToolOperation(AnalyticsEventType.IMAGE_PROMPT_SUBMITTED, operation);
            service.recordToolOperation(AnalyticsEventType.IMAGE_PROMPT_GENERATED, operation);
            service(new ActorIdentity(UUID.randomUUID(), actor.tenantId(), actor.workspaceId(), "other@local", "Other"))
                    .recordToolOperation(AnalyticsEventType.IMAGE_PROMPT_SUBMITTED, operation);
            service(new ActorIdentity(actor.userId(), UUID.randomUUID(), actor.workspaceId(), "other@local", "Other"))
                    .recordToolOperation(AnalyticsEventType.IMAGE_PROMPT_SUBMITTED, operation);
            service(new ActorIdentity(actor.userId(), actor.tenantId(), UUID.randomUUID(), "other@local", "Other"))
                    .recordToolOperation(AnalyticsEventType.IMAGE_PROMPT_SUBMITTED, operation);
        } finally { RequestContextHolder.resetRequestAttributes(); }
        var facts = ArgumentCaptor.forClass(PendingAuditEvent.class);
        verify(delivery, times(6)).accept(facts.capture(), eq(false));
        var events = facts.getAllValues();
        assertThat(events.get(0).id()).isEqualTo(events.get(1).id()).isNotEqualTo(operation);
        assertThat(events.subList(1, 6)).extracting(PendingAuditEvent::id).doesNotHaveDuplicates();
        assertThat(events.get(0).actorUserId()).isEqualTo(actor.userId());
        assertThat(events.get(0).tenantId()).isEqualTo(actor.tenantId());
        assertThat(events.get(0).details()).containsEntry("clientIp", "198.51.100.24").containsEntry("requestId", "fixture-request")
                .doesNotContainKeys("operationId", "userId", "prompt", "sourceText", "image", "apiKey");
        assertThat(events.get(0).details().toString()).doesNotContain("private article", operation.toString());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = AnalyticsEventType.class, names = {
            "IMAGE_PROMPT_DIRECT_SUBMITTED", "IMAGE_PROMPT_PLAN_COMPLETED", "IMAGE_PROMPT_REOPTIMIZED"
    })
    void materialStageFactsAreAccountScopedIdempotentAndDoNotCaptureSourceContent(AnalyticsEventType type) {
        UUID operation = UUID.randomUUID();
        service.recordToolOperation(type, operation);
        service.recordToolOperation(type, operation);
        var facts = ArgumentCaptor.forClass(PendingAuditEvent.class);
        verify(delivery, times(2)).accept(facts.capture(), eq(false));
        assertThat(facts.getAllValues()).extracting(PendingAuditEvent::id).containsOnly(facts.getValue().id());
        assertThat(facts.getValue().eventType()).isEqualTo(type);
        assertThat(facts.getValue().actorUserId()).isEqualTo(actor.userId());
        assertThat(facts.getValue().details()).isEmpty();
        assertThat(facts.getValue().id()).isNotEqualTo(operation);
    }

    @Test
    void nonHttpToolCallsKeepIdentityButDoNotInventLocationAndCaptureFailureDoesNotFailBusiness() {
        service.recordToolOperation(AnalyticsEventType.TEXT_POLISH_GENERATED, UUID.randomUUID());
        var event = ArgumentCaptor.forClass(PendingAuditEvent.class);
        verify(delivery).accept(event.capture(), eq(false));
        assertThat(event.getValue().details()).isEmpty();
        assertThat(event.getValue().actorUserId()).isEqualTo(actor.userId());
        verifyNoInteractions(geo);
        doThrow(new IllegalStateException("private underlying details")).when(delivery).accept(any(), eq(false));
        org.assertj.core.api.Assertions.assertThatCode(() -> service.recordToolOperation(
                AnalyticsEventType.TEXT_POLISH_SUBMITTED, UUID.randomUUID())).doesNotThrowAnyException();
    }

    @Test
    void unavailableRequestMetadataStillDeliversTheConfirmedAccountFact() {
        var request = request();
        // 模拟请求组件异常，不使用私有正文制造故障；不能让元数据错误抹去实际发生的业务提交。
        request.setAttribute(com.promptoptimizer.common.web.RequestIdFilter.REQUEST_ID_ATTRIBUTE, new Object());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try { service.recordToolOperation(AnalyticsEventType.TEXT_POLISH_SUBMITTED, UUID.randomUUID()); }
        finally { RequestContextHolder.resetRequestAttributes(); }
        var fact = ArgumentCaptor.forClass(PendingAuditEvent.class);
        verify(delivery).accept(fact.capture(), eq(false));
        assertThat(fact.getValue().actorUserId()).isEqualTo(actor.userId());
        assertThat(fact.getValue().details()).isEmpty();
    }

    @Test
    void toolBoundaryRejectsPageEventsOtherTypesAndMissingIdentifiers() {
        for (AnalyticsEventType type : new AnalyticsEventType[]{null, AnalyticsEventType.APP_VISIT,
                AnalyticsEventType.LOGIN, AnalyticsEventType.MODEL_CALL_RECORDED}) {
            assertThatThrownBy(() -> service.recordToolOperation(type, UUID.randomUUID()))
                    .isInstanceOf(InvalidOptimizationRequestException.class);
        }
        assertThatThrownBy(() -> service.recordToolOperation(AnalyticsEventType.TEXT_POLISH_SUBMITTED, null))
                .isInstanceOf(InvalidOptimizationRequestException.class);
        verifyNoInteractions(delivery, geo);
    }

    /** 使用固定合法历史时刻，避免永久队列测试依赖当天日期。 */
    private OffsetDateTime historicalTime() {
        return OffsetDateTime.parse("2020-01-01T08:00:00+08:00");
    }

    /** 采集输入只含测试账号标识、类型和原时刻，不包含认证凭据。 */
    private ClientAnalyticsEventRequest event(ClientAnalyticsEventType type, UUID id, OffsetDateTime time, UUID userId, UUID sessionId) {
        return new ClientAnalyticsEventRequest(type, id, time, userId, sessionId);
    }

    /** 服务端请求使用文档保留测试 IP，避免真实用户信息进入测试输出。 */
    private MockHttpServletRequest request() {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.24");
        request.addHeader("User-Agent", "Fixture desktop");
        return request;
    }

    /** 仅替换投递边界和地理解析；身份、元数据白名单及会话算法使用实际生产代码。 */
    private AnalyticsEventServiceImpl service(ActorIdentity identity) {
        CurrentActor current = mock(CurrentActor.class);
        when(current.require()).thenReturn(identity);
        return new AnalyticsEventServiceImpl(current, delivery, new ClientIpResolver(""), geo,
                new DeviceTypeResolver(), new AnalyticsSessionContext());
    }
}
