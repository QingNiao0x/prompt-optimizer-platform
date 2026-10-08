package com.promptoptimizer.policy.service.impl;

import com.promptoptimizer.policy.service.ConstraintCompleter;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.dto.PermissionPolicyInput;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.template.domain.TaskIntentResolver;
import com.promptoptimizer.template.domain.TaskIntent;
import com.promptoptimizer.policy.domain.ConstraintBundle;
import com.promptoptimizer.policy.domain.PlatformConstraintRules;
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
        return grouped(context, permissionPolicy, templateCode, TaskIntentResolver.software(templateCode)).visibleConstraints();
    }

    @Override
    public ConstraintBundle completeForTask(ContextSnapshot context, PermissionPolicyInput permissionPolicy,
                                           boolean includePermissionBoundaries, TaskIntent intent) {
        return grouped(context, permissionPolicy, intent.templateCode(), intent.engineeringConstraints());
    }

    /** 可见条款按来源组装；兼容显示开关不参与服务端路径过滤，也不能关闭代码任务的五条固定规则。 */
    private ConstraintBundle grouped(ContextSnapshot context, PermissionPolicyInput permissionPolicy,
                                     TemplateCode templateCode, boolean engineering) {
        Set<String> constraints = new LinkedHashSet<>();

        if (templateCode == TemplateCode.RESEARCH_ANALYSIS) {
            constraints.add("明确数据来源、研究对象、指标定义和统计口径，无法核实的数据与引用不得编造。");
            constraints.add("说明缺失数据、偏倚、不确定性和方法适用条件，保证分析过程可复现。");
        } else if (TaskIntentResolver.software(templateCode)) {
            constraints.add("校验所有外部输入，并明确处理空值、非法值和边界条件。");
            constraints.add("处理可预期异常，返回清晰错误信息，不吞掉或伪造错误。");
            constraints.add("为核心逻辑补充正常、异常和边界场景测试。");
        }

        // 技术栈用于理解材料；辅助分析脚本不能被背景项目的 Java/Vue 工程规范污染。
        List<String> stackNames = context.technologyStack().stream()
                .filter(item -> TaskIntentResolver.software(templateCode))
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

        if (engineering && !TaskIntentResolver.software(templateCode)) {
            constraints.add("仅对本次明确交付的代码：校验输入并处理空值、非法值和边界条件。");
            constraints.add("仅对本次明确交付的代码：处理可预期异常，返回清晰错误信息，不吞掉或伪造错误。");
            constraints.add("仅对本次明确交付的代码：补充必要的正常、异常和边界验证，不把软件测试要求扩展到报告正文。");
        }
        PermissionPolicyInput policy = permissionPolicy == null ? PermissionPolicyInput.empty() : permissionPolicy;
        List<String> permissions = new java.util.ArrayList<>();
        addCustomPolicy(permissions, PlatformConstraintRules.PATH_PREFIX, policy.protectedPaths());
        addCustomPolicy(permissions, PlatformConstraintRules.ACTION_PREFIX, policy.requireConfirmationFor());
        return new ConstraintBundle(PlatformConstraintRules.forTask(engineering), List.copyOf(constraints), permissions);
    }

    /**
     * 辅助代码沿用输入、异常与测试保护，但不将背景项目技术栈强加给分析脚本。
     * 具体语言和框架继续来自用户明确要求及本次代码证据。
     */
    @Override
    public List<String> completeWithAuxiliaryCode(
            ContextSnapshot context,
            PermissionPolicyInput permissionPolicy,
            boolean includePermissionBoundaries,
            TemplateCode templateCode
    ) {
        return grouped(context, permissionPolicy, templateCode, true).visibleConstraints();
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
     * 用户显式权限单独保留，即使与默认条款重合，也不能在非代码任务中丢失。
     */
    private void addCustomPolicy(List<String> target, String prefix, List<String> customValues) {
        List<String> values = customValues.stream().map(String::trim).filter(value -> !value.isEmpty()).distinct().toList();
        if (!values.isEmpty()) target.add(prefix + String.join("、", values) + "。");
    }

    private boolean containsStack(List<String> stackNames, String expected) {
        return stackNames.stream().anyMatch(name -> name.contains(expected));
    }
}
