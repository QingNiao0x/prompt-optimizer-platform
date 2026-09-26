package com.promptoptimizer.analytics.infrastructure;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * 仅在直连代理属于显式信任网段时解析 X-Forwarded-For，避免客户端伪造来源地址。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Component
public class ClientIpResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientIpResolver.class);
    private final List<IpAddressMatcher> trustedProxies;

    public ClientIpResolver(@Value("${app.analytics.trusted-proxies:}") String trustedProxyList) {
        this.trustedProxies = parseTrustedProxies(trustedProxyList);
    }

    /** 解析客户端 IP 字面量；无法验证的地址返回 null。 */
    public String resolve(HttpServletRequest request) {
        String remoteAddress = literal(request.getRemoteAddr());
        if (remoteAddress == null || !isTrusted(remoteAddress)) {
            return remoteAddress;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) {
            return remoteAddress;
        }
        String[] hops = forwarded.split(",", -1);
        List<String> parsedHops = new ArrayList<>(hops.length);
        for (String hop : hops) {
            String parsed = literal(hop.trim());
            if (parsed == null) {
                return remoteAddress;
            }
            parsedHops.add(parsed);
        }
        for (int index = parsedHops.size() - 1; index >= 0; index--) {
            String candidate = parsedHops.get(index);
            if (!isTrusted(candidate)) {
                return candidate;
            }
        }
        return parsedHops.getFirst();
    }

    private List<IpAddressMatcher> parseTrustedProxies(String value) {
        List<IpAddressMatcher> result = new ArrayList<>();
        if (value == null || value.isBlank()) {
            return List.of();
        }
        for (String cidr : value.split(",")) {
            String candidate = cidr.trim();
            if (candidate.isEmpty()) {
                continue;
            }
            try {
                result.add(new IpAddressMatcher(candidate));
            } catch (IllegalArgumentException exception) {
                LOGGER.warn("event=analytics.invalid_trusted_proxy_configuration");
            }
        }
        return List.copyOf(result);
    }

    private String literal(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String address = value.trim();
        boolean ipv4 = address.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}");
        boolean ipv6 = address.indexOf(':') >= 0 && address.matches("(?i)[0-9a-f:.]+");
        if (!ipv4 && !ipv6) {
            return null;
        }
        try {
            return InetAddress.getByName(address).getHostAddress();
        } catch (Exception exception) {
            return null;
        }
    }

    private boolean isTrusted(String address) {
        for (IpAddressMatcher matcher : trustedProxies) {
            try {
                if (matcher.matches(address)) {
                    return true;
                }
            } catch (IllegalArgumentException ignored) {
                return false;
            }
        }
        return false;
    }
}
