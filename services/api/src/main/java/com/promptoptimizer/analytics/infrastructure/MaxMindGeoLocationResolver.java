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

    public MaxMindGeoLocationResolver(@Value("${app.analytics.geoip.database:}") String databasePath) {
        this.databaseReader = openDatabase(databasePath);
    }

    @Override
    public GeoLocation resolve(String ipAddress) {
        if (databaseReader == null || ipAddress == null || ipAddress.isBlank()) {
            return GeoLocation.unavailable();
        }
        try {
            CityResponse response = databaseReader.city(InetAddress.getByName(ipAddress));
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

    @PreDestroy
    void close() throws IOException {
        if (databaseReader != null) {
            databaseReader.close();
        }
    }

    private DatabaseReader openDatabase(String databasePath) {
        if (databasePath == null || databasePath.isBlank()) {
            LOGGER.info("event=analytics.geo_database_disabled");
            return null;
        }
        try {
            File database = new File(databasePath);
            if (!database.isFile() || !database.canRead()) {
                LOGGER.warn("event=analytics.geo_database_unavailable");
                return null;
            }
            return new DatabaseReader.Builder(database).locales(List.of(Locale.CHINA.toLanguageTag(), "en")).build();
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("event=analytics.geo_database_unavailable reason={}", exception.getClass().getSimpleName());
            return null;
        }
    }

    private String preferredName(java.util.Map<String, String> names, String fallback) {
        if (names == null || names.isEmpty()) {
            return fallback;
        }
        return names.getOrDefault("zh-CN", names.getOrDefault("zh", names.getOrDefault("en", fallback)));
    }
}
