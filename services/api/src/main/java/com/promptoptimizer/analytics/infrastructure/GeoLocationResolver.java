package com.promptoptimizer.analytics.infrastructure;

import com.promptoptimizer.analytics.domain.GeoLocation;

/**
 * 负责把 IP 地址解析为粗粒度位置，不应执行网络请求或阻断审计写入。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@FunctionalInterface
public interface GeoLocationResolver {

    /** 查询本地地理地址库；数据缺失或解析失败时返回空字段。 */
    GeoLocation resolve(String ipAddress);
}
