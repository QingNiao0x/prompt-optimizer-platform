package com.promptoptimizer.analytics.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 确认来源地址只信任配置的代理，并拒绝伪造或格式错误的转发链。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class ClientIpResolverTest {

    @Test
    void readsForwardedClientIpOnlyWhenRemoteHopIsTrusted() {
        ClientIpResolver resolver = new ClientIpResolver("10.0.0.0/8");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.2");
        request.addHeader("X-Forwarded-For", "198.51.100.7, 10.0.0.9");

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.7");
    }

    @Test
    void ignoresForwardedHeaderFromUntrustedPeerAndMalformedChain() {
        ClientIpResolver resolver = new ClientIpResolver("10.0.0.0/8");
        MockHttpServletRequest untrusted = new MockHttpServletRequest();
        untrusted.setRemoteAddr("198.51.100.4");
        untrusted.addHeader("X-Forwarded-For", "203.0.113.8");
        MockHttpServletRequest malformed = new MockHttpServletRequest();
        malformed.setRemoteAddr("10.0.0.2");
        malformed.addHeader("X-Forwarded-For", "unknown, 10.0.0.9");

        assertThat(resolver.resolve(untrusted)).isEqualTo("198.51.100.4");
        assertThat(resolver.resolve(malformed)).isEqualTo("10.0.0.2");
    }

    @Test
    void returnsNullForInvalidDirectAddress() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("invalid-address");

        assertThat(new ClientIpResolver("").resolve(request)).isNull();
    }
}
