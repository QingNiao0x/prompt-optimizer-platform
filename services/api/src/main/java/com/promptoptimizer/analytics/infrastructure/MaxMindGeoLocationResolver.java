package com.promptoptimizer.analytics.infrastructure;

import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.exception.GeoIp2Exception;
import com.maxmind.geoip2.model.CityResponse;
import com.promptoptimizer.analytics.domain.GeoLocation;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.util.List;
import java.util.Locale;

/**
 * 使用可选的本地 MaxMind 城市库做离线定位；无库、私有 IP 或查询失败都返回空位置。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Component
public class MaxMindGeoLocationResolver implements GeoLocationResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(MaxMindGeoLocationResolver.class);
    private final DatabaseReader databaseReader;

    /** 保留外部城市库配置；未配置时由统一离线解析器使用随包库。 */
    public MaxMindGeoLocationResolver(@Value("${app.analytics.geoip.database:}") String databasePath) {
        this.databaseReader = openDatabase(databasePath);
    }

    /** 校验公网 IP 字面量后读取城市库，不能把 SDK 的域名解析能力暴露给输入。 */
    @Override
    public GeoLocation resolve(String ipAddress) {
        if (databaseReader == null || ipAddress == null || ipAddress.isBlank()) {
            return GeoLocation.unavailable();
        }
        InetAddress address = PublicGeoIpAddress.parse(ipAddress);
        if (address == null) return GeoLocation.unavailable();
        try {
            CityResponse response = databaseReader.city(address);
            var country = response.getCountry();
            var subdivision = response.getMostSpecificSubdivision();
            var city = response.getCity();
            return new GeoLocation(
                    country == null ? null : preferredName(country.getNames(), country.getIsoCode()),
                    subdivision == null ? null : preferredName(subdivision.getNames(), subdivision.getIsoCode()),
                    city == null ? null : preferredName(city.getNames(), null)
            );
        } catch (IOException | GeoIp2Exception | RuntimeException exception) {
            LOGGER.debug("event=analytics.geo_lookup_unavailable reason={}", exception.getClass().getSimpleName());
            return GeoLocation.unavailable();
        }
    }

    /** 应用停止时释放外部库文件句柄。 */
    @PreDestroy
    void close() throws IOException {
        if (databaseReader != null) {
            databaseReader.close();
        }
    }

    /** 启动时检查库类型并记录安全元数据，避免误装 Country/ASN 库后所有查询才逐一失败。 */
    private DatabaseReader openDatabase(String databasePath) {
        if (databasePath == null || databasePath.isBlank()) {
            LOGGER.info("event=analytics.geo_database_disabled source=MAXMIND reason=NOT_CONFIGURED");
            return null;
        }
        try {
            // 旧配置仍接受本机文件路径，但不通过 UNC 网络共享隐式访问远端城市库。
            String normalizedPath = databasePath.trim();
            if (normalizedPath.startsWith("\\\\") || normalizedPath.startsWith("//")) {
                LOGGER.warn("event=analytics.geo_database_unavailable source=MAXMIND reason=NETWORK_PATH_REJECTED");
                return null;
            }
            File database = new File(normalizedPath);
            if (!database.isFile() || !database.canRead()) {
                LOGGER.warn("event=analytics.geo_database_unavailable source=MAXMIND reason=NOT_READABLE");
                return null;
            }
            DatabaseReader reader = new DatabaseReader.Builder(database).locales(List.of(Locale.CHINA.toLanguageTag(), "en")).build();
            String databaseType = reader.getMetadata().databaseType();
            if (databaseType == null || !databaseType.contains("City")) {
                reader.close();
                LOGGER.warn("event=analytics.geo_database_unavailable source=MAXMIND reason=NOT_CITY_DATABASE");
                return null;
            }
            LOGGER.info("event=analytics.geo_database_loaded source=MAXMIND buildDate={}", reader.getMetadata().buildTime());
            return reader;
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("event=analytics.geo_database_unavailable source=MAXMIND reason={}", exception.getClass().getSimpleName());
            return null;
        }
    }

    /** 中文优先，依次保留其他可用名称或 ISO 代码，不因翻译缺失丢掉已知位置。 */
    private String preferredName(java.util.Map<String, String> names, String fallback) {
        if (names == null || names.isEmpty()) {
            return fallback;
        }
        return names.getOrDefault("zh-CN", names.getOrDefault("zh", names.getOrDefault("en", fallback)));
    }
}
