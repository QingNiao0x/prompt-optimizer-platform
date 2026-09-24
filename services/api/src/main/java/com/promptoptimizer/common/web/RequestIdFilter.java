package com.promptoptimizer.common.web;

import com.promptoptimizer.common.logging.LogFields;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 为每个请求生成或透传请求标识，并写入响应头与日志 MDC。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(RequestIdFilter.class);

    public static final String REQUEST_ID_ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId";
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    /**
     * 设置请求标识后继续执行过滤器链，结束时清理日志上下文。
     */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        long startedAt = System.nanoTime();
        String requestId = normalizeRequestId(request.getHeader(REQUEST_ID_HEADER));
        String previousRequestId = MDC.get("requestId");
        String previousWorkflowId = MDC.get("workflowId");
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        MDC.put("requestId", requestId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            String routeTemplate = route instanceof String value
                    ? LogFields.value(value)
                    : "<unmapped>";
            long durationMs = Math.max(0, (System.nanoTime() - startedAt) / 1_000_000L);
            LOGGER.info("event=http.request.completed requestId={} method={} route={} status={} durationMs={}",
                    requestId,
                    LogFields.value(request.getMethod()),
                    routeTemplate,
                    response.getStatus(),
                    durationMs);
            restoreMdc("requestId", previousRequestId);
            restoreMdc("workflowId", previousWorkflowId);
        }
    }

    /** 恢复过滤器进入前的 MDC 状态，避免嵌套分发污染调用方日志上下文。 */
    private void restoreMdc(String key, String value) {
        if (value == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }

    /**
     * 规范化请求标识，非法或不安全字符统一生成随机 UUID。
     */
    private String normalizeRequestId(String candidate) {
        if (candidate != null && SAFE_REQUEST_ID.matcher(candidate).matches()) {
            return candidate;
        }
        return UUID.randomUUID().toString();
    }
}
