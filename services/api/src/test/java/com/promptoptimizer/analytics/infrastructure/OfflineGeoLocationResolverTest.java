package com.promptoptimizer.analytics.infrastructure;

import com.promptoptimizer.analytics.domain.GeoLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 验证显式 MaxMind 配置优先、整条结果回退及公共 IP 边界，不拼接不同数据源的行政区。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class OfflineGeoLocationResolverTest {

    private static final String PUBLIC_ADDRESS = "114.114.114.114";
    private final MaxMindGeoLocationResolver maxMind = mock(MaxMindGeoLocationResolver.class);
    private final Ip2RegionGeoLocationResolver ip2Region = mock(Ip2RegionGeoLocationResolver.class);
    private final OfflineGeoLocationResolver resolver = new OfflineGeoLocationResolver(maxMind, ip2Region);

    @Test
    void prefersConfiguredMaxMindResult() {
        GeoLocation preferred = new GeoLocation("国家甲", "省甲", "城市甲");
        when(maxMind.resolve(PUBLIC_ADDRESS)).thenReturn(preferred);

        assertThat(resolver.resolve(PUBLIC_ADDRESS)).isEqualTo(preferred);
        verifyNoInteractions(ip2Region);
    }

    @Test
    void keepsPartialMaxMindLocationWithoutCombiningSources() {
        GeoLocation partial = new GeoLocation("国家甲", null, null);
        when(maxMind.resolve(PUBLIC_ADDRESS)).thenReturn(partial);

        assertThat(resolver.resolve(PUBLIC_ADDRESS)).isEqualTo(partial);
        verifyNoInteractions(ip2Region);
    }

    @Test
    void preservesLocationWhenOnlyASubdivisionIsKnown() {
        GeoLocation partial = new GeoLocation(null, "省甲", null);
        when(maxMind.resolve(PUBLIC_ADDRESS)).thenReturn(partial);

        assertThat(resolver.resolve(PUBLIC_ADDRESS)).isEqualTo(partial);
        verifyNoInteractions(ip2Region);
    }

    @Test
    void preservesLocationWhenOnlyACityIsKnown() {
        GeoLocation partial = new GeoLocation(null, null, "城市甲");
        when(maxMind.resolve(PUBLIC_ADDRESS)).thenReturn(partial);

        assertThat(resolver.resolve(PUBLIC_ADDRESS)).isEqualTo(partial);
        verifyNoInteractions(ip2Region);
    }

    @Test
    void fallsBackWhenMaxMindHasNoLocation() {
        GeoLocation fallback = new GeoLocation("国家乙", "省乙", "城市乙");
        when(maxMind.resolve(PUBLIC_ADDRESS)).thenReturn(GeoLocation.unavailable());
        when(ip2Region.resolve(PUBLIC_ADDRESS)).thenReturn(fallback);

        assertThat(resolver.resolve(PUBLIC_ADDRESS)).isEqualTo(fallback);
        verify(maxMind).resolve(PUBLIC_ADDRESS);
        verify(ip2Region).resolve(PUBLIC_ADDRESS);
    }

    @Test
    void fallsBackWhenPreferredResolverReturnsNull() {
        GeoLocation fallback = new GeoLocation("国家乙", null, null);
        when(ip2Region.resolve(PUBLIC_ADDRESS)).thenReturn(fallback);

        assertThat(resolver.resolve(PUBLIC_ADDRESS)).isEqualTo(fallback);
    }

    @Test
    void fallsBackWhenPreferredFieldsAreOnlyWhitespace() {
        GeoLocation fallback = new GeoLocation("国家乙", null, null);
        when(maxMind.resolve(PUBLIC_ADDRESS)).thenReturn(new GeoLocation(" ", "", null));
        when(ip2Region.resolve(PUBLIC_ADDRESS)).thenReturn(fallback);

        assertThat(resolver.resolve(PUBLIC_ADDRESS)).isEqualTo(fallback);
    }

    @Test
    void normalizesNullFallbackToUnavailable() {
        when(maxMind.resolve(PUBLIC_ADDRESS)).thenReturn(GeoLocation.unavailable());

        assertThat(resolver.resolve(PUBLIC_ADDRESS)).isEqualTo(GeoLocation.unavailable());
    }

    @Test
    void returnsUnavailableWhenBothDatabasesMiss() {
        when(maxMind.resolve(PUBLIC_ADDRESS)).thenReturn(GeoLocation.unavailable());
        when(ip2Region.resolve(PUBLIC_ADDRESS)).thenReturn(GeoLocation.unavailable());

        assertThat(resolver.resolve(PUBLIC_ADDRESS)).isEqualTo(GeoLocation.unavailable());
    }

    @Test
    void documentationAndBenchmarkPrefixesDoNotExcludeTheirNextPublicRange() {
        // 3fff::/20 的第二组仅覆盖 0000—0fff；不能把相邻 1000 段误当作示例地址。
        assertThat(PublicGeoIpAddress.parse("3fff::1")).isNull();
        assertThat(PublicGeoIpAddress.parse("3fff:fff:ffff::1")).isNull();
        assertThat(PublicGeoIpAddress.parse("3fff:1000::1")).isNotNull();
        assertThat(PublicGeoIpAddress.parse("2001:2::1")).isNull();
        assertThat(PublicGeoIpAddress.parse("2001:2:1::1")).isNotNull();
        verifyNoInteractions(maxMind, ip2Region);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "example.com", "114.114.114.114:443", "256.2.3.4", "1.2.3",
            "127.0.0.1", "10.0.0.1", "172.16.1.1", "192.168.1.1", "100.64.0.1", "169.254.1.1",
            "192.0.2.1", "198.51.100.1", "203.0.113.1", "224.0.0.1", "255.255.255.255",
            "::", "::1", "fc00::1", "fe80::1", "fe80::1%eth0", "ff02::1", "2001:db8::1",
            "3fff::1", "3fff:fff:ffff::1", "2001:2::1",
            "::ffff:192.168.1.1", "::ffff:127.0.0.1"})
    void rejectsNonPublicAndNonLiteralInputsBeforeEitherLookup(String input) {
        assertThat(resolver.resolve(input)).isEqualTo(GeoLocation.unavailable());
        verifyNoInteractions(maxMind, ip2Region);
    }
}
