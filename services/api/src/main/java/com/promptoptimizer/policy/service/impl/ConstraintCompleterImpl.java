package com.promptoptimizer.policy.service.impl;

import com.promptoptimizer.policy.service.ConstraintCompleter;
import com.promptoptimizer.policy.service.PlatformPermissionPolicy;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.dto.PermissionPolicyInput;
import com.promptoptimizer.enhancement.domain.TemplateCode;
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
public class ConstraintCompleterImpl implements ConstraintCompleter {

    /**
     * 生成去重且顺序稳定的约束列表，便于模型和用户审查。
     */
    public List<String> complete(
            ContextSnapshot context,
            PermissionPolicyInput permissionPolicy,
            boolean includePermissionBoundaries,
            TemplateCode templateCode
    ) {
        Set<String> constraints = new LinkedHashSet<>();
        constraints.add("明确区分已知事实、用户确认信息和必要假设，不得把猜测写成事实。");
        constraints.add("不得在代码、日志或响应中泄露密码、Token、API Key 或私钥。");

        if (templateCode == TemplateCode.RESEARCH_ANALYSIS) {
            constraints.add("明确数据来源、研究对象、指标定义和统计口径，无法核实的数据与引用不得编造。");
            constraints.add("说明缺失数据、偏倚、不确定性和方法适用条件，保证分析过程可复现。");
        } else if (templateCode == TemplateCode.GENERAL) {
            constraints.add("输出应直接回应用户目标，并说明关键依据、适用范围和限制条件。");
        } else {
            constraints.add("校验所有外部输入，并明确处理空值、非法值和边界条件。");
            constraints.add("处理可预期异常，返回清晰错误信息，不吞掉或伪造错误。");
            constraints.add("为核心逻辑补充正常、异常和边界场景测试。");
        }

        List<String> stackNames = context.technologyStack().stream()
                .map(item -> item.name().toLowerCase(java.util.Locale.ROOT))
                .toList();
        if (containsStack(stackNames, "java")) {
            constraints.add("使用 Java 21 兼容语法，遵循清晰分层、单一职责和不可变数据优先原则。");
        }
        if (containsStack(stackNames, "spring boot")) {
            constraints.add("遵循 Spring Boot 3 约定，Controller 负责协议转换，业务逻辑放在应用服务中。");
            constraints.add("接口请求使用 Bean Validation，异常通过统一异常处理返回。");
        }
        if (containsStack(stackNames, "vue") || containsStack(stackNames, "typescript")) {
            constraints.add("前端保持 TypeScript 类型安全，避免使用 any，并处理加载、空状态和失败状态。");
        }
        if (containsStack(stackNames, "postgresql")) {
            constraints.add("数据库访问使用参数化查询或 ORM，防止 SQL 注入，并明确事务边界。");
        }
        if (containsStack(stackNames, "redis")) {
            constraints.add("Redis 数据必须设置合理 TTL，并说明缓存一致性和失效策略。");
        }

        // 兼容字段不能关闭默认红线；用户规则只能追加，不能削弱平台安全边界。
        constraints.add("禁止读取或输出受保护路径：" + joinPolicies(PlatformPermissionPolicy.PROTECTED_PATHS, permissionPolicy.protectedPaths()) + "。");
        constraints.add("以下操作必须先获得人工确认：" + joinPolicies(PlatformPermissionPolicy.CONFIRMATION_ACTIONS, permissionPolicy.requireConfirmationFor()) + "。");
        return List.copyOf(constraints);
    }

    /**
     * 兼容原有调用方，按通用任务补全约束。
     */
    public List<String> complete(
            ContextSnapshot context,
            PermissionPolicyInput permissionPolicy,
            boolean includePermissionBoundaries
    ) {
        return complete(context, permissionPolicy, includePermissionBoundaries, TemplateCode.GENERAL);
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

    private boolean containsStack(List<String> stackNames, String expected) {
        return stackNames.stream().anyMatch(name -> name.contains(expected));
    }
}
