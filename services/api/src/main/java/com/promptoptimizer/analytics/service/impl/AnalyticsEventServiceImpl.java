package com.promptoptimizer.analytics.service.impl;

import com.promptoptimizer.analytics.service.AnalyticsEventService;
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

import java.sql.SQLException;
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
public class AnalyticsEventServiceImpl implements AnalyticsEventService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AnalyticsEventServiceImpl.class);
    private final CurrentActor currentActor;
    private final AuditEventMapper auditMapper;
    private final ClientIpResolver clientIpResolver;
    private final GeoLocationResolver geoLocationResolver;
    private final DeviceTypeResolver deviceTypeResolver;
    private final AnalyticsSessionContext sessionContext;

    public AnalyticsEventServiceImpl(
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
            // Spring 会把 SQL 和绑定值放进异常消息。这里只记类名、SQLState 和约束名，避免把正文、IP 或提示词打进日志。
            AuditWriteFailure failure = AuditWriteFailure.from(exception);
            LOGGER.error(
                    "event=analytics.audit_write_failure requestId={} eventType={} reason={} sqlState={} constraint={}",
                    requestId,
                    eventType,
                    exception.getClass().getSimpleName(),
                    failure.sqlState(),
                    failure.constraint()
            );
        }
    }

    /**
     * 从数据库异常链取出可公开的诊断码。没有 SQLState 或约束名时记为 unavailable，不回退到异常消息。
     */
    private record AuditWriteFailure(String sqlState, String constraint) {

        private static final String UNAVAILABLE = "unavailable";

        private static AuditWriteFailure from(Throwable failure) {
            SQLException sqlException = findSqlException(failure);
            return new AuditWriteFailure(sqlState(sqlException), constraint(sqlException));
        }

        private static SQLException findSqlException(Throwable failure) {
            Throwable current = failure;
            for (int depth = 0; current != null && depth < 8; depth++) {
                if (current instanceof SQLException sqlException) {
                    return sqlException;
                }
                Throwable cause = current.getCause();
                if (cause == current) {
                    return null;
                }
                current = cause;
            }
            return null;
        }

        private static String sqlState(SQLException sqlException) {
            for (SQLException current = sqlException; current != null; current = current.getNextException()) {
                if (current.getSQLState() != null && !current.getSQLState().isBlank()) {
                    return current.getSQLState();
                }
            }
            return UNAVAILABLE;
        }

        private static String constraint(SQLException sqlException) {
            for (SQLException current = sqlException; current != null; current = current.getNextException()) {
                String name = postgresConstraint(current);
                if (name != null) {
                    return name;
                }
            }
            return UNAVAILABLE;
        }

        /**
         * 驱动是运行时依赖。只读取约束名，不读取异常消息，避免把 SQL 或键值打进日志。
         */
        private static String postgresConstraint(SQLException sqlException) {
            if (!"org.postgresql.util.PSQLException".equals(sqlException.getClass().getName())) {
                return null;
            }
            try {
                Object serverError = sqlException.getClass().getMethod("getServerErrorMessage").invoke(sqlException);
                if (serverError == null) {
                    return null;
                }
                Object constraint = serverError.getClass().getMethod("getConstraint").invoke(serverError);
                if (constraint instanceof String name && name.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")) {
                    return name;
                }
            } catch (ReflectiveOperationException exception) {
                return null;
            }
            return null;
        }
    }
}
