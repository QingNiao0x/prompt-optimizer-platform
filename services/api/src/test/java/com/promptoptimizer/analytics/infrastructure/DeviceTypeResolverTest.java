package com.promptoptimizer.analytics.infrastructure;

import com.promptoptimizer.analytics.domain.AnalyticsDeviceType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证设备解析只输出固定类别，不保留原始 User-Agent。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class DeviceTypeResolverTest {

    private final DeviceTypeResolver resolver = new DeviceTypeResolver();

    @Test
    void identifiesMobileTabletDesktopAndMissingAgent() {
        assertThat(resolver.resolve("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) Mobile"))
                .isEqualTo(AnalyticsDeviceType.MOBILE);
        assertThat(resolver.resolve("Mozilla/5.0 (iPad; CPU OS 17_0 like Mac OS X)"))
                .isEqualTo(AnalyticsDeviceType.TABLET);
        assertThat(resolver.resolve("Mozilla/5.0 (Windows NT 10.0; Win64; x64)"))
                .isEqualTo(AnalyticsDeviceType.DESKTOP);
        assertThat(resolver.resolve(null)).isEqualTo(AnalyticsDeviceType.UNKNOWN);
    }
}
