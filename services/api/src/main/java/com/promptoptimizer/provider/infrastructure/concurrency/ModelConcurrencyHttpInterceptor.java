package com.promptoptimizer.provider.infrastructure.concurrency;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.io.InputStream;

/**
 * 在模型 HTTP 传输层共享平台名额，覆盖增强、Plan、文档摘要、Embedding 及每次修复尝试。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class ModelConcurrencyHttpInterceptor implements ClientHttpRequestInterceptor {

    private final ModelConcurrencyLimiter limiter;

    /** 使用与账号请求保护相同的共享存储。 */
    public ModelConcurrencyHttpInterceptor(ModelConcurrencyLimiter limiter) {
        this.limiter = limiter;
    }

    /** 收到响应头不代表模型已经生成完毕；直到响应体关闭才释放上游名额。 */
    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        ModelConcurrencyLimiter.Permit permit = limiter.acquireGlobal();
        try {
            return new LimitedResponse(execution.execute(request, body), permit);
        } catch (IOException | RuntimeException | Error exception) {
            permit.close();
            throw exception;
        }
    }

    /** 保留原响应全部协议行为，只增加幂等释放。 */
    private record LimitedResponse(ClientHttpResponse delegate, ModelConcurrencyLimiter.Permit permit)
            implements ClientHttpResponse {
        @Override
        public HttpStatusCode getStatusCode() throws IOException { return delegate.getStatusCode(); }
        @Override
        public String getStatusText() throws IOException { return delegate.getStatusText(); }
        @Override
        public HttpHeaders getHeaders() { return delegate.getHeaders(); }
        @Override
        public InputStream getBody() throws IOException { return delegate.getBody(); }
        @Override
        public void close() {
            try {
                delegate.close();
            } finally {
                permit.close();
            }
        }
    }
}
