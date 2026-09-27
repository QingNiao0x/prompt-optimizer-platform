package com.promptoptimizer.common.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.autoconfigure.SqlSessionFactoryBeanCustomizer;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 配置 MyBatis-Plus Mapper 扫描和 PostgreSQL 分页拦截器。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Configuration
@MapperScan(basePackages = "com.promptoptimizer", annotationClass = Mapper.class)
public class MybatisPlusConfiguration {

    /**
     * 在 SqlSessionFactory 创建时挂上分页插件。
     * 配置类上的 ConditionalOnBean 看不到自动配置的 DataSource，单独声明的拦截器 Bean 不会进入工厂，分页查询不会追加 LIMIT。
     */
    @Bean
    public SqlSessionFactoryBeanCustomizer mybatisPlusPaginationCustomizer() {
        return factory -> factory.setPlugins(paginationInterceptor());
    }

    private MybatisPlusInterceptor paginationInterceptor() {
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.POSTGRE_SQL);
        pagination.setOverflow(false);
        pagination.setMaxLimit(100L);
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}
