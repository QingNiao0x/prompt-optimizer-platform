package com.promptoptimizer.analytics.service.impl;

import com.promptoptimizer.analytics.service.AnalyticsEventService;
import com.promptoptimizer.analytics.domain.AnalyticsEventType;
import com.promptoptimizer.analytics.domain.AnalyticsDeviceType;
import com.promptoptimizer.analytics.domain.ClientAnalyticsEventType;
import com.promptoptimizer.analytics.domain.GeoLocation;
import com.promptoptimizer.analytics.domain.PendingAuditEvent;
import com.promptoptimizer.analytics.dto.ClientAnalyticsEventRequest;
import com.promptoptimizer.analytics.dto.ClientAnalyticsContext;
import com.promptoptimizer.analytics.infrastructure.AuditEventDelivery;
import com.promptoptimizer.analytics.service.AnalyticsIdentityChangedException;
import com.promptoptimizer.common.exception.InvalidOptimizationRequestException;
import com.promptoptimizer.analytics.infrastructure.AnalyticsSessionContext;
import com.promptoptimizer.analytics.infrastructure.ClientIpResolver;
import com.promptoptimizer.analytics.infrastructure.DeviceTypeResolver;
import com.promptoptimizer.analytics.infrastructure.GeoLocationResolver;
import com.promptoptimizer.common.web.RequestIdFilter;
import com.promptoptimizer.identity.service.ActorIdentity;
import com.promptoptimizer.identity.service.CurrentActor;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 从认证主体和当前 HTTP 请求采集关键操作；GeoIP 与审计写入均采用不阻断业务的显式降级策略。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Service
public class AnalyticsEventServiceImpl implements AnalyticsEventService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AnalyticsEventServiceImpl.class);
    private final CurrentActor currentActor;
    private final AuditEventDelivery delivery;
    private final ClientIpResolver clientIpResolver;
    private final GeoLocationResolver geoLocationResolver;
    private final DeviceTypeResolver deviceTypeResolver;
    private final AnalyticsSessionContext sessionContext;

    /** 装配服务端身份、请求元数据解析和可选审计存储，采集不依赖浏览器传入的用户 ID。 */
    public AnalyticsEventServiceImpl(
            CurrentActor currentActor,
            AuditEventDelivery delivery,
            ClientIpResolver clientIpResolver,
            GeoLocationResolver geoLocationResolver,
            DeviceTypeResolver deviceTypeResolver,
            AnalyticsSessionContext sessionContext
    ) {
        this.currentActor = currentActor;
        this.delivery = delivery;
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

    /** 记录服务端确认的关键行为；优化提交表示请求尝试，不能用来推断模型执行成功。 */
    public void record(AnalyticsEventType eventType, HttpServletRequest request) {
        String clientIp = clientIpResolver.resolve(request);
        GeoLocation location = locate(clientIp);
        AnalyticsDeviceType deviceType = deviceTypeResolver.resolve(request.getHeader("User-Agent"));
        AnalyticsSessionContext.Snapshot snapshot = sessionContext.currentOrCreate(request);
        persist(eventType, request, clientIp, location, deviceType, snapshot);
    }

    /** 提交在 Provider 调用前采集；生成失败仍是一次尝试，Provider 内部重试不会再次经过此入口。 */
    public void recordOptimizationSubmission(boolean direct, HttpServletRequest request) {
        PendingAuditEvent submitted = requestEvent(currentActor.require(), AnalyticsEventType.OPTIMIZATION_SUBMITTED,
                request, UUID.randomUUID());
        delivery.accept(submitted, false);
        if (direct) {
            // 同一提交的通用/细分事实共用时刻，避免跨午夜时落入两个不同自然日。
            delivery.accept(new PendingAuditEvent(UUID.randomUUID(), submitted.tenantId(), submitted.actorUserId(),
                    AnalyticsEventType.DIRECT_OPTIMIZATION_SUBMITTED, submitted.details(), submitted.occurredAt()), false);
        }
    }

    /** 完成事实按服务端身份与计划编号去重；重试、并发及跨实例投递最终命中同一数据库主键。 */
    public void recordPlanCompleted(String planId, HttpServletRequest request) {
        if (planId == null || planId.length() != 36) {
            throw new InvalidOptimizationRequestException("完成计划的编号无效");
        }
        UUID planUuid;
        try {
            planUuid = UUID.fromString(planId);
        } catch (IllegalArgumentException invalidId) {
            throw new InvalidOptimizationRequestException("完成计划的编号无效");
        }
        if (!planUuid.toString().equalsIgnoreCase(planId)) throw new InvalidOptimizationRequestException("完成计划的编号无效");
        ActorIdentity actor = currentActor.require();
        String scope = "analytics:plan-completed:v1:" + actor.tenantId() + ":" + actor.userId() + ":" + planUuid;
        UUID eventId = UUID.nameUUIDFromBytes(scope.getBytes(StandardCharsets.UTF_8));
        // 原始 planId 和身份字符串不进入 details，更不能成为 Micrometer 标签。
        delivery.accept(requestEvent(actor, AnalyticsEventType.PLAN_COMPLETED, request, eventId), false);
    }

    /** 只从认证上下文与元数据白名单构造不可变事实，保留服务端采集的 UTC 时刻。 */
    private PendingAuditEvent requestEvent(ActorIdentity actor, AnalyticsEventType eventType, HttpServletRequest request, UUID eventId) {
        String clientIp = clientIpResolver.resolve(request);
        GeoLocation location = locate(clientIp);
        AnalyticsDeviceType deviceType = deviceTypeResolver.resolve(request.getHeader("User-Agent"));
        AnalyticsSessionContext.Snapshot snapshot = sessionContext.currentOrCreate(request);
        String requestId = (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        return new PendingAuditEvent(eventId, actor.tenantId(), actor.userId(), eventType,
                sessionContext.details(snapshot, clientIp, location, deviceType, requestId),
                OffsetDateTime.now(ZoneOffset.UTC));
    }

    /** 校验离线事件所属账号、时间及登录关联号；可靠保存失败返回可重试错误，不能伪造接收成功。 */
    public void recordClientEvent(ClientAnalyticsEventRequest event, HttpServletRequest request) {
        Objects.requireNonNull(event, "client event");
        ActorIdentity actor = currentActor.require();
        // 先比较服务端账号，再读取新会话的 IP/位置，防止旧账号的队列被归属到后来登录的人。
        if (event.expectedUserId() != null && !event.expectedUserId().equals(actor.userId())) {
            throw new AnalyticsIdentityChangedException();
        }
        if (event.eventType() == null) throw new InvalidOptimizationRequestException("统计事件类型不能为空");
        OffsetDateTime occurredAt = clientOccurredAt(event.occurredAt());
        AnalyticsEventType mapped = switch (event.eventType()) {
            case APP_VISIT -> AnalyticsEventType.APP_VISIT;
            case RESULT_EXPORTED -> AnalyticsEventType.RESULT_EXPORTED;
        };
        AnalyticsSessionContext.Snapshot snapshot = sessionContext.currentOrCreate(request);
        boolean hasOriginalContext = event.expectedLoginSessionId() != null
                && event.expectedLoginSessionId().equals(snapshot.loginSessionId());
        boolean legacyLiveEvent = event.occurredAt() == null && event.expectedLoginSessionId() == null;
        String requestId = (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        Map<String, Object> details;
        if (hasOriginalContext || legacyLiveEvent) {
            String clientIp = clientIpResolver.resolve(request);
            details = sessionContext.details(snapshot, clientIp, locate(clientIp),
                    deviceTypeResolver.resolve(request.getHeader("User-Agent")), requestId);
        } else {
            // 无法证明仍是原登录上下文时，保留事实与时刻，但不把当前 IP/登录地伪造为旧事件的位置。
            var unknown = new LinkedHashMap<>(sessionContext.details(snapshot, null,
                    GeoLocation.unavailable(), AnalyticsDeviceType.UNKNOWN, requestId));
            for (String key : new String[]{"loginSessionId", "loginIp", "loginCountry", "loginProvince", "loginCity"}) unknown.put(key, null);
            unknown.put("delayedMetadata", true);
            details = unknown;
        }
        UUID id = event.eventId() == null ? UUID.randomUUID() : scopedClientEventId(actor, mapped, event.eventId());
        delivery.accept(new PendingAuditEvent(id, actor.tenantId(), actor.userId(), mapped, details, occurredAt), true);
    }

    /** 返回无认证能力的审计关联号；旧会话缺少快照时创建未知位置快照，不伪造登录事实。 */
    public ClientAnalyticsContext clientContext(HttpServletRequest request) {
        ActorIdentity actor = currentActor.require();
        return new ClientAnalyticsContext(actor.userId(), sessionContext.currentOrCreate(request).loginSessionId());
    }

    /** 将浏览器时刻归一到 UTC；永久队列允许合法历史时刻，仅限制支持年份与五分钟未来容差。 */
    private OffsetDateTime clientOccurredAt(OffsetDateTime supplied) {
        OffsetDateTime value = supplied == null ? OffsetDateTime.now(ZoneOffset.UTC) : supplied.withOffsetSameInstant(ZoneOffset.UTC);
        if (value.getYear() < 1 || value.getYear() > 9999 || value.toInstant().isAfter(Instant.now().plusSeconds(300))) {
            throw new InvalidOptimizationRequestException("统计事件时间必须在支持的公历年份内，且不能超过当前时间五分钟");
        }
        return value;
    }

    /** 客户端 UUID 只负责幂等，账号、租户与事件类型仍由服务端限定，不能碰撞其他人的事件主键。 */
    private UUID scopedClientEventId(ActorIdentity actor, AnalyticsEventType eventType, UUID clientId) {
        String scope = "analytics:v1:" + actor.tenantId() + ":" + actor.userId() + ":" + eventType + ":" + clientId;
        return UUID.nameUUIDFromBytes(scope.getBytes(StandardCharsets.UTF_8));
    }

    /** 无城市库、未知地址或解析失败时保留空所在地；地理增强失败不得阻止记录操作事实。 */
    private GeoLocation locate(String clientIp) {
        try {
            GeoLocation location = geoLocationResolver.resolve(clientIp);
            return location == null ? GeoLocation.unavailable() : location;
        } catch (RuntimeException exception) {
            LOGGER.warn("event=analytics.geo_lookup_unavailable reason={}", exception.getClass().getSimpleName());
            return GeoLocation.unavailable();
        }
    }

    /**
     * 以服务端认证主体追加审计事实，只保存白名单元数据及 UTC 时刻。
     * 先保存可靠 journal，再由独立工作线程入库；主业务不等待数据库恢复。
     * 磁盘与数据库都不可用时明确告警，不能宣称双存储故障仍保证不丢数据。
     */
    private void persist(
            AnalyticsEventType eventType,
            HttpServletRequest request,
            String clientIp,
            GeoLocation location,
            AnalyticsDeviceType deviceType,
            AnalyticsSessionContext.Snapshot snapshot
    ) {
        // 用户和租户只能来自当前认证主体；请求参数、User-Agent 和 IP 都不能决定操作所有者。
        ActorIdentity actor = currentActor.require();
        String requestId = (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        delivery.accept(new PendingAuditEvent(
                    UUID.randomUUID(),
                    actor.tenantId(),
                    actor.userId(),
                    eventType,
                    sessionContext.details(snapshot, clientIp, location, deviceType, requestId),
                    OffsetDateTime.now(ZoneOffset.UTC)
            ), false);
    }

}
