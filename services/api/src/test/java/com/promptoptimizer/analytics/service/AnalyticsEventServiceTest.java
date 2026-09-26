package com.promptoptimizer.analytics.service;

import com.promptoptimizer.analytics.domain.AnalyticsEventType;
import com.promptoptimizer.analytics.domain.GeoLocation;
import com.promptoptimizer.analytics.infrastructure.AnalyticsSessionContext;
import com.promptoptimizer.analytics.mapper.AuditEventMapper;
import com.promptoptimizer.analytics.infrastructure.ClientIpResolver;
import com.promptoptimizer.analytics.infrastructure.DeviceTypeResolver;
import com.promptoptimizer.analytics.infrastructure.GeoLocationResolver;
import com.promptoptimizer.common.web.RequestIdFilter;
import com.promptoptimizer.identity.service.ActorIdentity;
import com.promptoptimizer.identity.service.CurrentActor;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证审计日志由当前账号派生，GeoIP 失败不阻断记录且持久化故障不会中断主业务。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class AnalyticsEventServiceTest {

    private static final ActorIdentity ACTOR = new ActorIdentity(
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
            UUID.fromString("33333333-3333-4333-8333-333333333333"),
            "admin@example.com",
            "Platform Admin"
    );

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
        AnalyticsEventService service = new AnalyticsEventService(
                actor, provider(audit), new ClientIpResolver(""), unavailableGeo,
                new DeviceTypeResolver(), new AnalyticsSessionContext()
        );
        MockHttpServletRequest request = authenticatedRequest();

        service.recordLogin(request);

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
    void auditDatabaseFailureDoesNotFailTheUserOperation() {
        CurrentActor actor = mock(CurrentActor.class);
        when(actor.require()).thenReturn(ACTOR);
        AuditEventMapper audit = mock(AuditEventMapper.class);
        doThrow(new DataAccessResourceFailureException("database unavailable"))
                .when(audit).insert(any(UUID.class), any(UUID.class), any(UUID.class), any(), any(), any());
        AnalyticsEventService service = new AnalyticsEventService(
                actor,
                provider(audit),
                new ClientIpResolver(""),
                ip -> GeoLocation.unavailable(),
                new DeviceTypeResolver(),
                new AnalyticsSessionContext()
        );

        assertThatCode(() -> service.record(AnalyticsEventType.OPTIMIZATION_SUBMITTED, authenticatedRequest()))
                .doesNotThrowAnyException();
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
