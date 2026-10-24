package com.promptoptimizer.analytics.infrastructure;

import com.promptoptimizer.analytics.domain.GeoLocation;
import jakarta.annotation.PreDestroy;
import org.lionsoul.ip2region.service.Config;
import org.lionsoul.ip2region.service.Ip2Region;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 加载随应用提供的 IPv4/IPv6 城市库；查询始终离线，外部本地库路径可用于受控更新。
 * 两个协议独立加载，单库损坏不影响另一协议；数据库只提供 IP 归属估算，不证明真实物理位置。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Component
public class Ip2RegionGeoLocationResolver implements GeoLocationResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(Ip2RegionGeoLocationResolver.class);
    private static final long MAX_DATABASE_BYTES = 128L * 1024 * 1024;
    private final Ip2Region database;
    private final AtomicBoolean queryFailureReported = new AtomicBoolean();

    /**
     * 默认资源直接从 JAR 流加载，无需服务器额外挂载；覆盖配置只允许 classpath、本地路径或 file URI。
     * 固定版本的数据格式为国家|省份|城市|ISP|国家代码，更新外部库时必须保持该字段约定。
     */
    public Ip2RegionGeoLocationResolver(
            ResourceLoader resources,
            @Value("${app.analytics.geoip.ip2region.ipv4:classpath:geoip/ip2region_v4.xdb}") String ipv4Location,
            @Value("${app.analytics.geoip.ip2region.ipv6:classpath:geoip/ip2region_v6.xdb}") String ipv6Location) {
        Config ipv4 = load(resources, ipv4Location, false);
        Config ipv6 = load(resources, ipv6Location, true);
        this.database = create(ipv4, ipv6);
    }

    /** 使用已验证 IP 字节查询；空结果及失败保留未知位置，不以网络 API 作为隐式回退。 */
    @Override
    public GeoLocation resolve(String ipAddress) {
        InetAddress address = PublicGeoIpAddress.parse(ipAddress);
        if (address == null || database == null) return GeoLocation.unavailable();
        try {
            String region = database.search(address.getAddress());
            if (region == null || region.isBlank()) return GeoLocation.unavailable();
            String[] parts = region.split("\\|", -1);
            if (parts.length != 5) {
                reportQueryFailure("UNSUPPORTED_REGION_FORMAT");
                return GeoLocation.unavailable();
            }
            return new GeoLocation(locationPart(parts[0]), locationPart(parts[1]), locationPart(parts[2]));
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            reportQueryFailure(failure.getClass().getSimpleName());
            return GeoLocation.unavailable();
        }
    }

    /** 全内存模式没有逐次查询的共享文件指针；只在应用退出时释放 SDK。 */
    @PreDestroy
    void close() {
        if (database == null) return;
        try {
            database.close();
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            LOGGER.warn("event=analytics.geo_database_close_failure source=IP2REGION reason={}",
                    failure.getClass().getSimpleName());
        }
    }

    /** 每个协议独立验证 XDB 格式和版本；1 MiB 分片避免默认 50 MiB 分配造成过大的启动峰值。 */
    private Config load(ResourceLoader resources, String location, boolean ipv6) {
        String protocol = ipv6 ? "IPV6" : "IPV4";
        if (location == null || location.isBlank()) {
            LOGGER.warn("event=analytics.geo_database_unavailable source=IP2REGION protocol={} reason=NOT_CONFIGURED", protocol);
            return null;
        }
        try {
            Resource resource = localResource(resources, location.trim());
            long bytes = resource.contentLength();
            if (bytes <= 0 || bytes > MAX_DATABASE_BYTES) throw new IllegalArgumentException("Invalid database size");
            try (InputStream input = resource.getInputStream()) {
                var builder = Config.custom().setCachePolicy(Config.BufferCache)
                        .setCacheSliceBytes(1024 * 1024).setXdbInputStream(input);
                Config config = ipv6 ? builder.asV6() : builder.asV4();
                LOGGER.info("event=analytics.geo_database_loaded source=IP2REGION protocol={} bytes={} createdAtEpochSeconds={}",
                        protocol, bytes, config.header.createdAt);
                return config;
            }
        } catch (Exception failure) {
            LOGGER.error("event=analytics.geo_database_unavailable source=IP2REGION protocol={} reason={}",
                    protocol, failure.getClass().getSimpleName());
            return null;
        }
    }

    /** 不使用通用 URL Resource 读取远端配置，保证即使配置错误也不发起网络下载。 */
    private Resource localResource(ResourceLoader resources, String location) {
        if (location.startsWith("classpath:")) return resources.getResource(location);
        if (location.startsWith("file:")) {
            URI uri = URI.create(location);
            String path = uri.getPath();
            if (uri.isOpaque() || (uri.getAuthority() != null && !uri.getAuthority().isBlank())
                    || path == null || path.startsWith("//") || path.startsWith("\\\\")) {
                throw new IllegalArgumentException("Network file locations are not supported");
            }
            return resources.getResource(location);
        }
        if (location.startsWith("//") || location.startsWith("\\\\")) {
            throw new IllegalArgumentException("Network file locations are not supported");
        }
        boolean windowsPath = location.matches("^[a-zA-Z]:[\\\\/].*");
        if (!windowsPath && location.matches("^[a-zA-Z][a-zA-Z0-9+.-]*:.*")) {
            throw new IllegalArgumentException("Only local databases are supported");
        }
        return new FileSystemResource(location);
    }

    /** 没有可用库时显式降级；正常双栈及单栈均交给 SDK 的线程安全全内存查询服务。 */
    private Ip2Region create(Config ipv4, Config ipv6) {
        if (ipv4 == null && ipv6 == null) return null;
        try {
            return Ip2Region.create(ipv4, ipv6);
        } catch (Exception failure) {
            LOGGER.error("event=analytics.geo_database_unavailable source=IP2REGION reason={}",
                    failure.getClass().getSimpleName());
            return null;
        }
    }

    /** 数据库占位值不是地理事实；字段缺失可独立返回 null，不能补造省市名称。 */
    private String locationPart(String value) {
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.equals("0") || normalized.equals("未知")
                || normalized.equals("内网IP") || normalized.equalsIgnoreCase("unknown")
                || normalized.equalsIgnoreCase("reserved") || normalized.equalsIgnoreCase("private")) return null;
        if (normalized.length() > 128) throw new IllegalArgumentException("Invalid location length");
        return normalized;
    }

    /** 同一实例的查询故障只告警一次，防止损坏库让每次业务操作刷屏；日志不含用户 IP。 */
    private void reportQueryFailure(String reason) {
        if (queryFailureReported.compareAndSet(false, true)) {
            LOGGER.warn("event=analytics.geo_lookup_unavailable source=IP2REGION reason={}", reason);
        }
    }
}
