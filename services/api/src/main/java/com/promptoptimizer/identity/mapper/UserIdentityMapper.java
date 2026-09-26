package com.promptoptimizer.identity.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.promptoptimizer.identity.entity.UserIdentityEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 登录身份单表访问。按类型、签发方和规范化标识的组合查询在业务层用条件构造器完成。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Mapper
public interface UserIdentityMapper extends BaseMapper<UserIdentityEntity> {
}
