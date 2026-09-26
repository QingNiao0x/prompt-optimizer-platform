package com.promptoptimizer.analytics.infrastructure;

import com.promptoptimizer.analytics.domain.AnalyticsDeviceType;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 将 User-Agent 映射为有限设备类别，只保存类别代码，不保存原始请求头。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Component
public class DeviceTypeResolver {

    /** 返回 MOBILE、TABLET、DESKTOP 或 UNKNOWN。 */
    public AnalyticsDeviceType resolve(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return AnalyticsDeviceType.UNKNOWN;
        }
        String normalized = userAgent.toLowerCase(Locale.ROOT);
        if (contains(normalized, "ipad", "tablet", "kindle", "silk")) {
            return AnalyticsDeviceType.TABLET;
        }
        if (contains(normalized, "android", "iphone", "ipod", "mobile", "windows phone")) {
            return AnalyticsDeviceType.MOBILE;
        }
        return AnalyticsDeviceType.DESKTOP;
    }

    private boolean contains(String value, String... markers) {
        for (String marker : markers) {
            if (value.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
