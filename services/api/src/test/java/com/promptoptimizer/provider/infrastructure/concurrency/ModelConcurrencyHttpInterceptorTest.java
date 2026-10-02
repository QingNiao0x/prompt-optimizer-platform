package com.promptoptimizer.provider.infrastructure.concurrency;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证名额覆盖响应体读取，而非只覆盖建立连接和接收响应头。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class ModelConcurrencyHttpInterceptorTest {

    @Test
    void holdsCapacityAfterHeadersAndReleasesItWhenResponseCloses() throws Exception {
        ModelConcurrencyProperties properties = new ModelConcurrencyProperties();
        properties.setGlobalLimit(1);
        try (var limiter = new ModelConcurrencyLimiter(new InMemoryModelConcurrencyStore(), properties)) {
            var interceptor = new ModelConcurrencyHttpInterceptor(limiter);
            var response = interceptor.intercept(new MockClientHttpRequest(), new byte[0],
                    (request, body) -> new MockClientHttpResponse(new byte[]{1, 2}, HttpStatus.OK));
            try {
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThatThrownBy(limiter::acquireGlobal).isInstanceOf(ModelConcurrencyException.class);
                assertThat(response.getBody().readAllBytes()).containsExactly(1, 2);
                assertThatThrownBy(limiter::acquireGlobal).isInstanceOf(ModelConcurrencyException.class);
            } finally {
                response.close();
                response.close();
            }
            try (var next = limiter.acquireGlobal()) {
                assertThat(next).isNotNull();
            }
        }
    }

    @Test
    void releasesCapacityWhenConnectionFailsBeforeReceivingAnyResponse() {
        ModelConcurrencyProperties properties = new ModelConcurrencyProperties();
        properties.setGlobalLimit(1);
        try (var limiter = new ModelConcurrencyLimiter(new InMemoryModelConcurrencyStore(), properties)) {
            var interceptor = new ModelConcurrencyHttpInterceptor(limiter);
            for (int attempt = 0; attempt < 4; attempt++) {
                assertThatThrownBy(() -> interceptor.intercept(new MockClientHttpRequest(), new byte[0],
                        (request, body) -> { throw new IOException("test connection failure"); }))
                        .isInstanceOf(IOException.class);
            }
            try (var next = limiter.acquireGlobal()) {
                assertThat(next).isNotNull();
            }
        }
    }

    @Test
    void releasesCapacityEvenIfResponseCloseThrows() throws Exception {
        ModelConcurrencyProperties properties = new ModelConcurrencyProperties();
        properties.setGlobalLimit(1);
        try (var limiter = new ModelConcurrencyLimiter(new InMemoryModelConcurrencyStore(), properties)) {
            var interceptor = new ModelConcurrencyHttpInterceptor(limiter);
            var response = interceptor.intercept(new MockClientHttpRequest(), new byte[0],
                    (request, body) -> new MockClientHttpResponse(new byte[0], HttpStatus.OK) {
                        @Override public void close() { throw new IllegalStateException("test close failure"); }
                    });
            assertThatThrownBy(response::close).isInstanceOf(IllegalStateException.class);
            try (var next = limiter.acquireGlobal()) {
                assertThat(next).isNotNull();
            }
        }
    }
}
