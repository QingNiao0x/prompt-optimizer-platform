package com.promptoptimizer.analytics.infrastructure;

import com.promptoptimizer.analytics.domain.GeoLocation;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 使用随应用打包的真实离线库验证定位、地址边界及损坏资源的无阻断降级。
 * 样例仅使用公共 DNS 地址；人工验收地址由本机系统属性注入，不进入源码或日志。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@ExtendWith(OutputCaptureExtension.class)
class Ip2RegionGeoLocationResolverTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(Ip2RegionGeoLocationResolverTest.class);
    private static final String IPV4_DATABASE = "classpath:geoip/ip2region_v4.xdb";
    private static final String IPV6_DATABASE = "classpath:geoip/ip2region_v6.xdb";
    private static final String IPV4_SAMPLE = "114.114.114.114";
    private static final String IPV6_SAMPLE = "2001:4860:4860::8888";
    private static Ip2RegionGeoLocationResolver resolver;

    /** 共享只读地址库，避免每个输入边界用例都重复加载完整二进制资源。 */
    @BeforeAll
    static void openDatabases() {
        resolver = create(IPV4_DATABASE, IPV6_DATABASE);
    }

    /** 显式释放真实库资源，保持后续测试和 Windows 文件句柄可复用。 */
    @AfterAll
    static void closeDatabases() throws Exception {
        if (resolver != null) resolver.close();
    }

    @Test
    void resolvesPublicIpv4AndIpv6UsingBundledDatabases() {
        assertThat(resolver.resolve(IPV4_SAMPLE).country()).isNotBlank();
        assertThat(resolver.resolve(IPV6_SAMPLE).country()).isNotBlank();
    }

    @Test
    void concurrentQueriesKeepAddressFamiliesAndResultsIndependent() throws Exception {
        GeoLocation expectedIpv4 = resolver.resolve(IPV4_SAMPLE);
        GeoLocation expectedIpv6 = resolver.resolve(IPV6_SAMPLE);
        List<Callable<GeoLocation>> queries = new ArrayList<>();
        for (int index = 0; index < 32; index++) {
            String sample = index % 2 == 0 ? IPV4_SAMPLE : IPV6_SAMPLE;
            queries.add(() -> resolver.resolve(sample));
        }
        // 同一个解析器供多个 HTTP 工作线程共享，交替地址族可以检出共享查询状态被覆盖。
        try (var executor = Executors.newFixedThreadPool(4)) {
            var results = executor.invokeAll(queries);
            for (int index = 0; index < results.size(); index++) {
                assertThat(results.get(index).get()).isEqualTo(index % 2 == 0 ? expectedIpv4 : expectedIpv6);
            }
        }
    }

    @Test
    void doesNotResolveInvalidPrivateOrDocumentationAddresses() {
        assertThat(resolver.resolve(null)).isEqualTo(GeoLocation.unavailable());
        for (String input : List.of("", " ", "localhost", "example.com", "https://example.com",
                "114.114.114.114:80", "999.1.1.1", "1.2.3", "::ffff:invalid", "fe80::1%eth0",
                "0.0.0.0", "127.0.0.1", "10.2.3.4", "172.16.0.1", "192.168.1.1",
                "100.64.0.1", "169.254.1.1", "192.0.2.1", "198.51.100.1", "203.0.113.1",
                "224.0.0.1", "255.255.255.255", "::", "::1", "fc00::1", "fe80::1",
                "ff02::1", "2001:db8::1", "3fff::1", "3fff:fff:ffff::1", "2001:2::1",
                "::ffff:192.168.1.1", "::ffff:127.0.0.1")) {
            assertThat(resolver.resolve(input)).as("non-public input must not acquire a location")
                    .isEqualTo(GeoLocation.unavailable());
        }
    }

    @Test
    void missingIpv4DatabaseDoesNotDisableIpv6() throws Exception {
        Ip2RegionGeoLocationResolver partial = create("classpath:geoip/absent-v4.xdb", IPV6_DATABASE);
        try {
            assertThat(partial.resolve(IPV4_SAMPLE)).isEqualTo(GeoLocation.unavailable());
            assertThat(partial.resolve(IPV6_SAMPLE).country()).isNotBlank();
        } finally {
            partial.close();
        }
    }

    @Test
    void missingIpv6DatabaseDoesNotDisableIpv4() throws Exception {
        Ip2RegionGeoLocationResolver partial = create(IPV4_DATABASE, "classpath:geoip/absent-v6.xdb");
        try {
            assertThat(partial.resolve(IPV4_SAMPLE).country()).isNotBlank();
            assertThat(partial.resolve(IPV6_SAMPLE)).isEqualTo(GeoLocation.unavailable());
        } finally {
            partial.close();
        }
    }

    @Test
    void disabledDatabasesReturnUnavailableWithoutBlockingCalls() throws Exception {
        Ip2RegionGeoLocationResolver disabled = create("", "");
        try {
            assertThat(disabled.resolve(IPV4_SAMPLE)).isEqualTo(GeoLocation.unavailable());
            assertThat(disabled.resolve(IPV6_SAMPLE)).isEqualTo(GeoLocation.unavailable());
        } finally {
            disabled.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://example.invalid/city.xdb", "http://example.invalid/city.xdb",
            "file://server/share/city.xdb", "file:////server/share/city.xdb",
            "\\\\server\\share\\city.xdb", "//server/share/city.xdb"})
    void refusesRemoteDatabaseLocationsWithoutInvokingResourceLoader(String remoteLocation) throws Exception {
        ResourceLoader resources = mock(ResourceLoader.class);
        Ip2RegionGeoLocationResolver remote = new Ip2RegionGeoLocationResolver(resources,
                remoteLocation, remoteLocation);
        try {
            assertThat(remote.resolve(IPV4_SAMPLE)).isEqualTo(GeoLocation.unavailable());
            assertThat(remote.resolve(IPV6_SAMPLE)).isEqualTo(GeoLocation.unavailable());
            verifyNoInteractions(resources);
        } finally {
            remote.close();
        }
    }

    @Test
    void rejectsSwappedAddressFamilyResourcesWithoutThrowing() throws Exception {
        // 文件存在不代表库适用于当前地址族；错配必须降级，不能返回碰巧命中的错误地域。
        Ip2RegionGeoLocationResolver swapped = create(IPV6_DATABASE, IPV4_DATABASE);
        try {
            assertThat(swapped.resolve(IPV4_SAMPLE)).isEqualTo(GeoLocation.unavailable());
            assertThat(swapped.resolve(IPV6_SAMPLE)).isEqualTo(GeoLocation.unavailable());
        } finally {
            swapped.close();
        }
    }

    @Test
    void corruptDatabaseDoesNotExposeInputOrResourceContents(CapturedOutput output) throws Exception {
        String corruptContents = "synthetic-corrupt-database-content";
        DefaultResourceLoader resources = new DefaultResourceLoader() {
            @Override
            public Resource getResource(String location) {
                return new ByteArrayResource(corruptContents.getBytes(StandardCharsets.UTF_8));
            }
        };
        Ip2RegionGeoLocationResolver corrupt = new Ip2RegionGeoLocationResolver(resources,
                "classpath:geoip/corrupt-v4.xdb", "classpath:geoip/corrupt-v6.xdb");
        try {
            assertThat(corrupt.resolve(IPV4_SAMPLE)).isEqualTo(GeoLocation.unavailable());
            assertThat(corrupt.resolve(IPV6_SAMPLE)).isEqualTo(GeoLocation.unavailable());
            assertThat(output).doesNotContain(IPV4_SAMPLE, IPV6_SAMPLE, corruptContents);
        } finally {
            corrupt.close();
        }
    }

    /** 人工指定的公网样本只做本机离线查询，输出有限位置字段供核对，不输出地址本身。 */
    @Test
    @EnabledIfSystemProperty(named = "geoip.acceptance.addresses", matches = ".+")
    void resolvesLocallySuppliedAcceptanceSamples() {
        String[] samples = System.getProperty("geoip.acceptance.addresses").split(",", -1);
        assertThat(samples.length).isBetween(1, 8);
        for (int index = 0; index < samples.length; index++) {
            GeoLocation result = resolver.resolve(samples[index].trim());
            assertThat(result.country()).as("sample %s country", index + 1).isNotBlank();
            assertThat(result.province()).as("sample %s province", index + 1).isNotBlank();
            assertThat(result.city()).as("sample %s city", index + 1).isNotBlank();
            LOGGER.info("event=analytics.geo_offline_acceptance sample={} country={} province={} city={}",
                    index + 1, result.country(), result.province(), result.city());
        }
    }

    /** 所有自动化测试都从 classpath 加载资源，证明打包库无需环境配置即可使用。 */
    private static Ip2RegionGeoLocationResolver create(String ipv4Location, String ipv6Location) {
        return new Ip2RegionGeoLocationResolver(new DefaultResourceLoader(), ipv4Location, ipv6Location);
    }
}
