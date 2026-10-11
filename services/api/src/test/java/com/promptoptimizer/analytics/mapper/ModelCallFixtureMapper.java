package com.promptoptimizer.analytics.mapper;

import org.apache.ibatis.annotations.Param;
import java.util.UUID;

/** 仅测试事务内的合成租户；不创建或修改数据库结构，结束时回滚。 */
interface ModelCallFixtureMapper {
    /** 插入合成租户以验证匿名跨租户范围。 */
    int insertTenant(@Param("id") UUID id, @Param("name") String name);
}
