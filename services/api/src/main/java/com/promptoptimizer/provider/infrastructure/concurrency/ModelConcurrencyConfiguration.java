package com.promptoptimizer.provider.infrastructure.concurrency;

import com.promptoptimizer.identity.service.CurrentActor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 装配全平台 50 个上游调用、单账号 3 个业务请求的并发控制。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ModelConcurrencyProperties.class)
public class ModelConcurrencyConfiguration {

    /** 仅显式选择 MEMORY 时使用本地计数，Redis 模式缺少依赖时拒绝启动。 */
    @Bean
    ModelConcurrencyStore modelConcurrencyStore(ModelConcurrencyProperties properties,
                                                ObjectProvider<StringRedisTemplate> redisProvider) {
        if (properties.getStoreMode() == ModelConcurrencyProperties.StoreMode.MEMORY) {
            return new InMemoryModelConcurrencyStore();
        }
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) throw new IllegalStateException("模型并发控制要求 Redis；本地单实例请显式配置 MEMORY");
        return new RedisModelConcurrencyStore(redis);
    }

    /** 管理租约与续租任务，由 Spring 在停止时关闭。 */
    @Bean
    ModelConcurrencyLimiter modelConcurrencyLimiter(ModelConcurrencyStore store, ModelConcurrencyProperties properties) {
        return new ModelConcurrencyLimiter(store, properties);
    }

    /** 两类模型 HTTP 客户端共享同一个全局计数器。 */
    @Bean
    ModelConcurrencyHttpInterceptor modelConcurrencyHttpInterceptor(ModelConcurrencyLimiter limiter) {
        return new ModelConcurrencyHttpInterceptor(limiter);
    }

    /** 覆盖所有现有模型操作入口；历史读取、登录和普通页面查询不占用账号名额。 */
    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    WebMvcConfigurer modelConcurrencyWebConfigurer(ModelConcurrencyLimiter limiter, CurrentActor currentActor) {
        ModelConcurrencyWebInterceptor interceptor = new ModelConcurrencyWebInterceptor(limiter, currentActor);
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(interceptor).addPathPatterns(
                        "/api/v1/optimizations",
                        "/api/v1/optimizations/plan",
                        "/api/v1/context/analyze",
                        "/api/v1/context/planning",
                        "/api/v1/optimization-history/*/re-optimize"
                );
            }
        };
    }
}
