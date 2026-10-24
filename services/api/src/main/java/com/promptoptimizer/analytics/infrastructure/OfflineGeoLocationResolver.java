package com.promptoptimizer.analytics.infrastructure;

import com.promptoptimizer.analytics.domain.GeoLocation;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.net.InetAddress;

/**
 * 统一离线位置来源：已有 MaxMind 配置优先，没有归属结果时使用随包城市库。
 * 一个结果只来自一个数据源，避免将两个库的国家、省、市拼成不一致的位置。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Primary
@Component
public class OfflineGeoLocationResolver implements GeoLocationResolver {

    private final MaxMindGeoLocationResolver maxMind;
    private final Ip2RegionGeoLocationResolver ip2Region;

    /** 保留现有 MaxMind 的注入配置；所有业务方仍通过原 GeoLocationResolver 接口调用。 */
    public OfflineGeoLocationResolver(MaxMindGeoLocationResolver maxMind, Ip2RegionGeoLocationResolver ip2Region) {
        this.maxMind = maxMind;
        this.ip2Region = ip2Region;
    }

    /** 先验证公网字面量，再按来源优先级选择完整结果；合法的部分位置同样保持原来源。 */
    @Override
    public GeoLocation resolve(String ipAddress) {
        InetAddress address = PublicGeoIpAddress.parse(ipAddress);
        if (address == null) return GeoLocation.unavailable();
        GeoLocation preferred = maxMind.resolve(address.getHostAddress());
        if (hasLocation(preferred)) return preferred;
        GeoLocation fallback = ip2Region.resolve(address.getHostAddress());
        return fallback == null ? GeoLocation.unavailable() : fallback;
    }

    /** 只有三个字段均未知才切换来源，不把已知国家误当作完全未命中。 */
    private boolean hasLocation(GeoLocation location) {
        return location != null && (known(location.country()) || known(location.province()) || known(location.city()));
    }

    private boolean known(String value) {
        return value != null && !value.isBlank();
    }
}
