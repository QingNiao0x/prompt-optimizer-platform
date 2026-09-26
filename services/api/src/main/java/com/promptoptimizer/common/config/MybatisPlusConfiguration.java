package com.promptoptimizer.common.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * 配置 MyBatis-Plus Mapper 扫描和 PostgreSQL 分页拦截器。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Configuration
@ConditionalOnBean(DataSource.class)
@MapperScan(basePackages = "com.promptoptimizer", annotationClass = Mapper.class)
public class MybatisPlusConfiguration {

    /** 为 MyBatis-Plus 的分页查询安装 PostgreSQL 方言拦截器并限制单页最大行数。 */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.POSTGRE_SQL);
        pagination.setOverflow(false);
        pagination.setMaxLimit(100L);
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}
