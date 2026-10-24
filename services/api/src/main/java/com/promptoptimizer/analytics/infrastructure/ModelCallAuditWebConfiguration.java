package com.promptoptimizer.analytics.infrastructure;

import com.promptoptimizer.analytics.service.impl.ModelCallAuditService;
import com.promptoptimizer.common.logging.ModelCallAuditContext;
import com.promptoptimizer.identity.service.CurrentActor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 在受认证保护的模型业务路由绑定服务端租户；不向模型计量传递用户身份。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Configuration
public class ModelCallAuditWebConfiguration implements WebMvcConfigurer {
    private static final String SCOPE_ATTRIBUTE = ModelCallAuditWebConfiguration.class.getName() + ".scope";
    private final ObjectProvider<CurrentActor> actorProvider;
    private final ObjectProvider<ModelCallAuditService> auditProvider;

    /** 注入认证适配器和白名单采集服务，不采信请求里的 tenantId。 */
    public ModelCallAuditWebConfiguration(ObjectProvider<CurrentActor> actorProvider, ObjectProvider<ModelCallAuditService> auditProvider) {
        this.actorProvider = actorProvider;
        this.auditProvider = auditProvider;
    }

    /** 先于并发保护建立作用域；权限仍由现有 Spring Security 和业务所有权校验负责。 */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // MVC 切片测试可能没有业务服务；在所有 Bean 注册完成后判断，避免配置解析顺序使完整应用漏装采集。
        ModelCallAuditService auditService = auditProvider.getIfAvailable();
        CurrentActor currentActor = actorProvider.getIfAvailable();
        if (auditService == null || currentActor == null) return;
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                var snapshot = new ModelCallAuditContext.Snapshot(currentActor.require().tenantId(), auditService);
                request.setAttribute(SCOPE_ATTRIBUTE, ModelCallAuditContext.bind(snapshot));
                return true;
            }

            @Override
            public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                        Object handler, Exception exception) {
                if (request.getAttribute(SCOPE_ATTRIBUTE) instanceof ModelCallAuditContext.Scope scope) {
                    scope.close();
                    request.removeAttribute(SCOPE_ATTRIBUTE);
                }
            }
        }).order(-100).addPathPatterns("/api/v1/optimizations", "/api/v1/optimizations/**",
                "/api/v1/context/**", "/api/v1/optimization-history/*/re-optimize",
                "/api/v2/optimizations", "/api/v2/optimizations/**",
                "/api/v2/context/**", "/api/v2/optimization-history/*/re-optimize",
                "/api/v1/image-prompts", "/api/v1/image-prompts/**");
    }
}
