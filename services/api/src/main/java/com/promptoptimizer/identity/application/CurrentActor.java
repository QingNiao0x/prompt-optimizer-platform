package com.promptoptimizer.identity.application;

/**
 * 读取当前请求认证主体的唯一业务接口。
 *
 * <p>Spring Security、Session 和 Principal 的实现细节被隐藏在基础设施适配器中。</p>
 *
 * @author QingNiao
 * @since 0.1.0
 */
@FunctionalInterface
public interface CurrentActor {

    /**
     * 返回当前已认证主体；未认证请求必须失败，不能回退到匿名或客户端提供的标识。
     */
    ActorIdentity require();
}
