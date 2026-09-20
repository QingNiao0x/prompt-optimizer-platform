package com.promptoptimizer.identity.api;

import org.springframework.security.web.csrf.CsrfToken;

/**
 * 前端初始化双提交 CSRF Cookie 时使用的元数据。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record CsrfTokenView(
        String headerName,
        String parameterName,
        String token
) {

    public static CsrfTokenView from(CsrfToken csrfToken) {
        return new CsrfTokenView(
                csrfToken.getHeaderName(),
                csrfToken.getParameterName(),
                csrfToken.getToken()
        );
    }
}
