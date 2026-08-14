package com.promptoptimizer.policy.application;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.api.PermissionPolicyInput;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 根据项目技术栈和权限策略补充工程约束。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Component
public class ConstraintCompleter {

    private static final List<String> DEFAULT_PROTECTED_PATHS = List.of(
            ".env", "**/*.pem", "**/*.key", "生产环境配置"
    );
    private static final List<String> DEFAULT_CONFIRMATION_ACTIONS = List.of(
            "删除或覆盖文件", "数据库结构迁移", "升级核心依赖", "生产环境部署"
    );

    /**
     * 生成去重且顺序稳定的约束列表，便于模型和用户审查。
     */
    public List<String> complete(
            ContextSnapshot context,
            PermissionPolicyInput permissionPolicy,
            boolean includePermissionBoundaries
    ) {
        Set<String> constraints = new LinkedHashSet<>();
        constraints.add("校验所有外部输入，并明确处理空值、非法值和边界条件。");
        constraints.add("处理可预期异常，返回清晰错误信息，不吞掉或伪造错误。");
        constraints.add("不得在代码、日志或响应中泄露密码、Token、API Key 或私钥。");
        constraints.add("为核心逻辑补充正常、异常和边界场景测试。");

        Set<String> stackNames = context.technologyStack().stream()
                .map(item -> item.name().toLowerCase())
                .collect(java.util.stream.Collectors.toSet());
        if (stackNames.contains("java")) {
        constraints.add("使用 Java 21 兼容语法，遵循清晰分层、单一职责和不可变数据优先原则。");
        }
        if (stackNames.contains("spring boot")) {
            constraints.add("遵循 Spring Boot 3 约定，Controller 负责协议转换，业务逻辑放在应用服务中。");
            constraints.add("接口请求使用 Bean Validation，异常通过统一异常处理返回。");
        }
        if (stackNames.contains("vue") || stackNames.contains("typescript")) {
            constraints.add("前端保持 TypeScript 类型安全，避免使用 any，并处理加载、空状态和失败状态。");
        }
        if (stackNames.contains("postgresql")) {
            constraints.add("数据库访问使用参数化查询或 ORM，防止 SQL 注入，并明确事务边界。");
        }
        if (stackNames.contains("redis")) {
            constraints.add("Redis 数据必须设置合理 TTL，并说明缓存一致性和失效策略。");
        }

        if (includePermissionBoundaries) {
            // 默认红线始终生效，用户规则只能追加，不能削弱平台安全边界。
            constraints.add("禁止读取或输出受保护路径：" + joinPolicies(DEFAULT_PROTECTED_PATHS, permissionPolicy.protectedPaths()) + "。");
            constraints.add("以下操作必须先获得人工确认：" + joinPolicies(DEFAULT_CONFIRMATION_ACTIONS, permissionPolicy.requireConfirmationFor()) + "。");
        }
        return List.copyOf(constraints);
    }

    /**
     * 合并默认策略和用户自定义策略，按顺序去重。
     */
    private String joinPolicies(List<String> defaults, List<String> customValues) {
        Set<String> values = new LinkedHashSet<>(defaults);
        customValues.stream()
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .forEach(values::add);
        return String.join("、", values);
    }
}
