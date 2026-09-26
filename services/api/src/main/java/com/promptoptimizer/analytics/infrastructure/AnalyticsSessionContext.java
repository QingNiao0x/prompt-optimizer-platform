package com.promptoptimizer.analytics.infrastructure;

import com.promptoptimizer.analytics.domain.GeoLocation;
import com.promptoptimizer.analytics.domain.AnalyticsDeviceType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;

import java.io.Serial;
import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;
import java.util.UUID;

/**
 * 在 HttpSession 中保存随机、非认证用途的登录关联号和登录时位置快照，不读取或复制会话 Token。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Component
public class AnalyticsSessionContext {

    private static final String SESSION_ATTRIBUTE = AnalyticsSessionContext.class.getName() + ".context";

    /** 创建成功登录的日志上下文并保存其登录所在地快照。 */
    public Snapshot beginLogin(
            HttpServletRequest request,
            String clientIp,
            GeoLocation location,
            AnalyticsDeviceType deviceType
    ) {
        Snapshot snapshot = new Snapshot(UUID.randomUUID(), clientIp,
                location == null ? GeoLocation.unavailable() : location, deviceType);
        request.getSession(true).setAttribute(SESSION_ATTRIBUTE, snapshot);
        return snapshot;
    }

    /** 读取当前登录上下文；旧会话缺少统计标识时生成内部关联号，但不伪造登录位置。 */
    public Snapshot currentOrCreate(HttpServletRequest request) {
        HttpSession session = request.getSession(true);
        Object stored = session.getAttribute(SESSION_ATTRIBUTE);
        if (stored instanceof Snapshot snapshot) {
            return snapshot;
        }
        Snapshot snapshot = new Snapshot(UUID.randomUUID(), null, GeoLocation.unavailable(), AnalyticsDeviceType.UNKNOWN);
        session.setAttribute(SESSION_ATTRIBUTE, snapshot);
        return snapshot;
    }

    /** 在 HttpSession 失效前移除统计关联信息。 */
    public void clear(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(SESSION_ATTRIBUTE);
        }
    }

    /** 生成白名单审计元数据；所有空位置字段保留为 JSON null。 */
    public Map<String, Object> details(
            Snapshot session,
            String clientIp,
            GeoLocation eventLocation,
            AnalyticsDeviceType deviceType,
            String requestId
    ) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("analyticsVersion", 1);
        details.put("loginSessionId", session.loginSessionId().toString());
        details.put("clientIp", clientIp);
        details.put("country", eventLocation.country());
        details.put("province", eventLocation.province());
        details.put("city", eventLocation.city());
        details.put("loginIp", session.loginIp());
        details.put("loginCountry", session.loginLocation().country());
        details.put("loginProvince", session.loginLocation().province());
        details.put("loginCity", session.loginLocation().city());
        details.put("deviceType", deviceType.name());
        details.put("requestId", requestId);
        return Collections.unmodifiableMap(details);
    }

    /** 仅在 HttpSession 内部传递的登录关联数据，不含原始 Session ID 或认证凭据。 */
    public record Snapshot(
            UUID loginSessionId,
            String loginIp,
            GeoLocation loginLocation,
            AnalyticsDeviceType deviceType
    ) implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;
    }
}
