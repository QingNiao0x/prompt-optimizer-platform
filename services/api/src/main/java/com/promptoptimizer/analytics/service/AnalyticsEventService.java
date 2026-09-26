package com.promptoptimizer.analytics.service;

import com.promptoptimizer.analytics.domain.AnalyticsEventType;
import com.promptoptimizer.analytics.domain.AnalyticsDeviceType;
import com.promptoptimizer.analytics.domain.ClientAnalyticsEventType;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * 从认证主体和当前 HTTP 请求采集关键操作；GeoIP 与审计写入均采用不阻断业务的显式降级策略。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Service
public class AnalyticsEventService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AnalyticsEventService.class);
    private final CurrentActor currentActor;
    private final AuditEventMapper auditMapper;
    private final ClientIpResolver clientIpResolver;
    private final GeoLocationResolver geoLocationResolver;
    private final DeviceTypeResolver deviceTypeResolver;
    private final AnalyticsSessionContext sessionContext;

    public AnalyticsEventService(
            CurrentActor currentActor,
            ObjectProvider<AuditEventMapper> auditMapperProvider,
            ClientIpResolver clientIpResolver,
            GeoLocationResolver geoLocationResolver,
            DeviceTypeResolver deviceTypeResolver,
            AnalyticsSessionContext sessionContext
    ) {
        this.currentActor = currentActor;
        this.auditMapper = auditMapperProvider.getIfAvailable();
        this.clientIpResolver = clientIpResolver;
        this.geoLocationResolver = geoLocationResolver;
        this.deviceTypeResolver = deviceTypeResolver;
        this.sessionContext = sessionContext;
    }

    /** 记录一次成功登录，并把登录位置快照绑定到当前服务端会话。 */
    public void recordLogin(HttpServletRequest request) {
        String clientIp = clientIpResolver.resolve(request);
        GeoLocation location = locate(clientIp);
        AnalyticsDeviceType deviceType = deviceTypeResolver.resolve(request.getHeader("User-Agent"));
        AnalyticsSessionContext.Snapshot snapshot = sessionContext.beginLogin(
                request, clientIp, location, deviceType);
        persist(AnalyticsEventType.LOGIN, request, clientIp, location, deviceType, snapshot);
    }

    /** 记录退出事件后清理服务端会话中的统计关联信息。 */
    public void recordLogout(HttpServletRequest request) {
        record(AnalyticsEventType.LOGOUT, request);
        sessionContext.clear(request);
    }

    /** 记录由后端确认成功的关键业务操作。 */
    public void record(AnalyticsEventType eventType, HttpServletRequest request) {
        String clientIp = clientIpResolver.resolve(request);
        GeoLocation location = locate(clientIp);
        AnalyticsDeviceType deviceType = deviceTypeResolver.resolve(request.getHeader("User-Agent"));
        AnalyticsSessionContext.Snapshot snapshot = sessionContext.currentOrCreate(request);
        persist(eventType, request, clientIp, location, deviceType, snapshot);
    }

    /** 把经过浏览器事件白名单验证的遥测类型映射为审计事件。 */
    public void recordClientEvent(ClientAnalyticsEventType eventType, HttpServletRequest request) {
        AnalyticsEventType mapped = switch (eventType) {
            case APP_VISIT -> AnalyticsEventType.APP_VISIT;
            case RESULT_EXPORTED -> AnalyticsEventType.RESULT_EXPORTED;
        };
        record(mapped, request);
    }

    private GeoLocation locate(String clientIp) {
        try {
            return geoLocationResolver.resolve(clientIp);
        } catch (RuntimeException exception) {
            LOGGER.warn("event=analytics.geo_lookup_unavailable reason={}", exception.getClass().getSimpleName());
            return GeoLocation.unavailable();
        }
    }

    private void persist(
            AnalyticsEventType eventType,
            HttpServletRequest request,
            String clientIp,
            GeoLocation location,
            AnalyticsDeviceType deviceType,
            AnalyticsSessionContext.Snapshot snapshot
    ) {
        ActorIdentity actor = currentActor.require();
        String requestId = (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        if (auditMapper == null) {
            LOGGER.warn("event=analytics.persistence_disabled requestId={} reason=mapper_unavailable", requestId);
            return;
        }
        try {
            auditMapper.insert(
                    UUID.randomUUID(),
                    actor.tenantId(),
                    actor.userId(),
                    eventType,
                    sessionContext.details(snapshot, clientIp, location, deviceType, requestId),
                    OffsetDateTime.now(ZoneOffset.UTC)
            );
        } catch (DataAccessException exception) {
            // 统计存储暂不可用时保留主业务结果，同时留下不含 SQL 参数、IP 或用户正文的运维告警。
            LOGGER.error("event=analytics.audit_write_failure requestId={} eventType={} reason={}",
                    requestId, eventType, exception.getClass().getSimpleName());
        }
    }
}
