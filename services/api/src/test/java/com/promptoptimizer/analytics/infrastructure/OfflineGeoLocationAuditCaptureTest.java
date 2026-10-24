package com.promptoptimizer.analytics.infrastructure;

import com.promptoptimizer.analytics.domain.AnalyticsEventType;
import com.promptoptimizer.analytics.domain.GeoLocation;
import com.promptoptimizer.analytics.domain.PendingAuditEvent;
import com.promptoptimizer.analytics.service.impl.AnalyticsEventServiceImpl;
import com.promptoptimizer.common.web.RequestIdFilter;
import com.promptoptimizer.identity.service.ActorIdentity;
import com.promptoptimizer.identity.service.CurrentActor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证真实离线位置经过业务采集进入待投递审计快照，登录地与当前操作地保持各自语义。
 * 仅身份来源和投递出口使用替身，不连接数据库或网络，不等同于真实入库验收。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class OfflineGeoLocationAuditCaptureTest {

    private static final ActorIdentity ACTOR = new ActorIdentity(
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
            UUID.fromString("33333333-3333-4333-8333-333333333333"),
            "geo-fixture@example.test", "Offline Geo Fixture");
    private static MaxMindGeoLocationResolver maxMind;
    private static Ip2RegionGeoLocationResolver ip2Region;
    private static OfflineGeoLocationResolver resolver;

    /** 真实读取打包城市库，复现未提供外部 MMDB 的默认启动情形。 */
    @BeforeAll
    static void openDatabases() {
        maxMind = new MaxMindGeoLocationResolver("");
        ip2Region = new Ip2RegionGeoLocationResolver(new DefaultResourceLoader(),
                "classpath:geoip/ip2region_v4.xdb", "classpath:geoip/ip2region_v6.xdb");
        resolver = new OfflineGeoLocationResolver(maxMind, ip2Region);
    }

    /** 释放库资源，不把真实解析器泄漏给后续测试。 */
    @AfterAll
    static void closeDatabases() throws Exception {
        if (ip2Region != null) ip2Region.close();
        if (maxMind != null) maxMind.close();
    }

    @Test
    void realOfflineLocationsReachOperationMetadataAndRetainedLoginSnapshot() {
        AuditEventDelivery delivery = mock(AuditEventDelivery.class);
        AnalyticsEventServiceImpl service = service(delivery);
        MockHttpServletRequest request = request("114.114.114.114");
        GeoLocation loginLocation = resolver.resolve(request.getRemoteAddr());
        assertThat(loginLocation.country()).isNotBlank();

        service.recordLogin(request);
        // 同一会话切换公网地址后，操作地可以变化，登录地必须继续保持登录时的快照。
        request.setRemoteAddr("8.8.8.8");
        GeoLocation operationLocation = resolver.resolve(request.getRemoteAddr());
        assertThat(operationLocation.country()).isNotBlank();
        service.record(AnalyticsEventType.RESULT_EXPORTED, request);

        ArgumentCaptor<PendingAuditEvent> captured = ArgumentCaptor.forClass(PendingAuditEvent.class);
        verify(delivery, times(2)).accept(captured.capture(), eq(false));
        PendingAuditEvent login = captured.getAllValues().getFirst();
        PendingAuditEvent operation = captured.getAllValues().getLast();
        assertThat(login.eventType()).isEqualTo(AnalyticsEventType.LOGIN);
        assertThat(operation.eventType()).isEqualTo(AnalyticsEventType.RESULT_EXPORTED);
        assertLocation(login.details(), loginLocation, loginLocation);
        assertLocation(operation.details(), operationLocation, loginLocation);
        assertThat(operation.details()).containsEntry("clientIp", "8.8.8.8")
                .containsEntry("loginIp", "114.114.114.114")
                .containsEntry("loginSessionId", login.details().get("loginSessionId"))
                .containsEntry("requestId", "offline-geo-audit-fixture");
        assertThat(captured.getAllValues()).allSatisfy(event -> {
            assertThat(event.tenantId()).isEqualTo(ACTOR.tenantId());
            assertThat(event.actorUserId()).isEqualTo(ACTOR.userId());
        });
    }

    @Test
    void privateAddressStillProducesAuditEventsWithNullOperationAndLoginLocations() {
        AuditEventDelivery delivery = mock(AuditEventDelivery.class);
        AnalyticsEventServiceImpl service = service(delivery);
        MockHttpServletRequest request = request("10.20.30.40");

        service.recordLogin(request);
        service.record(AnalyticsEventType.RESULT_EXPORTED, request);

        ArgumentCaptor<PendingAuditEvent> captured = ArgumentCaptor.forClass(PendingAuditEvent.class);
        verify(delivery, times(2)).accept(captured.capture(), eq(false));
        assertThat(captured.getAllValues()).extracting(PendingAuditEvent::eventType)
                .containsExactly(AnalyticsEventType.LOGIN, AnalyticsEventType.RESULT_EXPORTED);
        assertThat(captured.getAllValues()).allSatisfy(event -> {
            assertLocation(event.details(), GeoLocation.unavailable(), GeoLocation.unavailable());
            assertThat(event.details()).containsEntry("clientIp", "10.20.30.40")
                    .containsEntry("loginIp", "10.20.30.40");
        });
    }

    /** 保留真实来源 IP 解析、离线定位和会话快照，仅隔离认证及外部持久化边界。 */
    private AnalyticsEventServiceImpl service(AuditEventDelivery delivery) {
        CurrentActor actor = mock(CurrentActor.class);
        when(actor.require()).thenReturn(ACTOR);
        return new AnalyticsEventServiceImpl(actor, delivery, new ClientIpResolver(""), resolver,
                new DeviceTypeResolver(), new AnalyticsSessionContext());
    }

    /** 使用公开 DNS 或内网夹具，不把实际用户 IP 保存到测试源码。 */
    private MockHttpServletRequest request(String address) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(address);
        request.setAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE, "offline-geo-audit-fixture");
        return request;
    }

    /** 逐字段比较允许缺省的省市，验证映射真实保留 null 而不是补造地址。 */
    private void assertLocation(Map<String, Object> details, GeoLocation operation, GeoLocation login) {
        assertThat(details).containsEntry("country", operation.country())
                .containsEntry("province", operation.province()).containsEntry("city", operation.city())
                .containsEntry("loginCountry", login.country()).containsEntry("loginProvince", login.province())
                .containsEntry("loginCity", login.city());
    }
}
