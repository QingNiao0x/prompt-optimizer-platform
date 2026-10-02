package com.promptoptimizer.provider.infrastructure.concurrency;

import com.promptoptimizer.identity.service.CurrentActor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 按已认证账号保护整次模型相关业务请求，跨标签页、Session 和设备共用三个名额。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class ModelConcurrencyWebInterceptor implements HandlerInterceptor {

    private static final String PERMIT_ATTRIBUTE = ModelConcurrencyWebInterceptor.class.getName() + ".permit";
    private final ModelConcurrencyLimiter limiter;
    private final CurrentActor currentActor;

    /** 账号必须来自服务端认证上下文，不使用请求正文、Header 或 Session ID 作为计数主体。 */
    public ModelConcurrencyWebInterceptor(ModelConcurrencyLimiter limiter, CurrentActor currentActor) {
        this.limiter = limiter;
        this.currentActor = currentActor;
    }

    /** 只限制模型操作的 POST；请求重派发复用原令牌，避免重复扣减名额。 */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("POST".equals(request.getMethod()) && request.getAttribute(PERMIT_ATTRIBUTE) == null) {
            request.setAttribute(PERMIT_ATTRIBUTE, limiter.acquireUser(currentActor.require().userId()));
        }
        return true;
    }

    /** 业务成功、参数校验失败及模型异常都释放；浏览器断开不会提前释放仍在执行的请求。 */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception exception) {
        if (request.getAttribute(PERMIT_ATTRIBUTE) instanceof ModelConcurrencyLimiter.Permit permit) {
            permit.close();
            request.removeAttribute(PERMIT_ATTRIBUTE);
        }
    }
}
