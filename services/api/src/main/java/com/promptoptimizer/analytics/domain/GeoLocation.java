package com.promptoptimizer.analytics.domain;

import java.io.Serial;
import java.io.Serializable;

/**
 * IP 地理位置的粗粒度结果；国家、省、市信息可能因地址库覆盖范围而为空。
 *
 * <p>该对象会随登录会话写入 Redis，因此必须可序列化。</p>
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record GeoLocation(String country, String province, String city) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 返回全空的地理位置结果，表示地址库未能可靠解析。 */
    public static GeoLocation unavailable() {
        return new GeoLocation(null, null, null);
    }
}
