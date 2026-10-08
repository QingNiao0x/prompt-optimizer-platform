package com.promptoptimizer.policy.domain;

import com.promptoptimizer.policy.service.PlatformPermissionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 本次生成的约束来源分组。仅内部传递，公共接口继续返回扁平的 appliedConstraints。
 *
 * @param platformMandatory 由平台按任务选择的固定条款
 * @param taskSpecific 任务、工程及资料对象规则
 * @param additionalPermissions 用户主动补充的权限边界，不用于关闭平台过滤器
 * @author QingNiao
 * @since 0.1.0
 */
public record ConstraintBundle(List<String> platformMandatory, List<String> taskSpecific,
                               List<String> additionalPermissions) {
    public ConstraintBundle {
        platformMandatory = List.copyOf(platformMandatory);
        taskSpecific = List.copyOf(taskSpecific);
        additionalPermissions = List.copyOf(additionalPermissions);
    }

    /** Provider 和 API 使用同一份实际可见规则，保持顺序稳定并去重。 */
    public List<String> visibleConstraints() {
        return Stream.of(platformMandatory, taskSpecific, additionalPermissions)
                .flatMap(List::stream).distinct().toList();
    }

    /** 添加本次资料的明确边界，不改变平台固定条款的数量和顺序。 */
    public ConstraintBundle withTaskRule(String rule) {
        return rule == null || rule.isBlank() ? this : new ConstraintBundle(platformMandatory,
                Stream.concat(taskSpecific.stream(), Stream.of(rule)).distinct().toList(), additionalPermissions);
    }

    /**
     * 适配旧的内部列表调用。只识别固定完整条款和已知权限前缀，其他业务规则逐字保留。
     * 正常编排直接传分组，不依靠正文或模型输出猜测规则来源。
     */
    public static ConstraintBundle fromLegacy(List<String> constraints, boolean engineering) {
        List<String> task = new ArrayList<>();
        List<String> permissions = new ArrayList<>();
        for (String rule : constraints == null ? List.<String>of() : constraints) {
            if (PlatformConstraintRules.CODE_RULES.contains(rule)) continue;
            if (rule.startsWith(PlatformConstraintRules.PATH_PREFIX)) {
                addCustomPolicy(permissions, rule, PlatformConstraintRules.PATH_PREFIX, PlatformPermissionPolicy.PROTECTED_PATHS);
            } else if (rule.startsWith(PlatformConstraintRules.ACTION_PREFIX)) {
                addCustomPolicy(permissions, rule, PlatformConstraintRules.ACTION_PREFIX, PlatformPermissionPolicy.CONFIRMATION_ACTIONS);
            } else {
                task.add(rule);
            }
        }
        return new ConstraintBundle(PlatformConstraintRules.forTask(engineering), task, permissions);
    }

    /** 旧调用曾合并默认项与用户项；仅剥离完全相同的默认项，不删除包含默认词语的自定义条件。 */
    private static void addCustomPolicy(List<String> target, String rule, String prefix, List<String> defaults) {
        String body = rule.substring(prefix.length()).replaceFirst("。$", "");
        List<String> custom = Stream.of(body.split("、")).map(String::trim)
                .filter(value -> !value.isBlank() && !defaults.contains(value)).distinct().toList();
        if (!custom.isEmpty()) target.add(prefix + String.join("、", custom) + "。");
    }
}
