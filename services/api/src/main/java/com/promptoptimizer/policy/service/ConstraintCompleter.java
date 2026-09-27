package com.promptoptimizer.policy.service;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.PermissionPolicyInput;

import java.util.List;

/**
 * 根据项目技术栈和权限策略补充工程约束。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public interface ConstraintCompleter {

    /** 生成去重且顺序稳定的约束列表，便于模型和用户审查。 */
    List<String> complete(
            ContextSnapshot context,
            PermissionPolicyInput permissionPolicy,
            boolean includePermissionBoundaries,
            TemplateCode templateCode
    );

    /** 按通用任务补全约束。 */
    List<String> complete(
            ContextSnapshot context,
            PermissionPolicyInput permissionPolicy,
            boolean includePermissionBoundaries
    );
}
