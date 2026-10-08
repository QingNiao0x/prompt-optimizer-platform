package com.promptoptimizer.policy.domain;

import java.util.List;

/**
 * 用户可复制的固定平台条款；显示范围由已确认的任务交付决定，不承担服务端安全校验。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class PlatformConstraintRules {
    public static final String HEADING = "平台强制约束（不得删除或弱化）：";
    public static final String TRUTH = "明确区分已知事实、用户确认信息和必要假设，不得把猜测写成事实。";
    public static final String PATH_PREFIX = "禁止读取或输出受保护路径：";
    public static final String ACTION_PREFIX = "以下操作必须先获得人工确认：";
    public static final List<String> CODE_RULES = List.of(
            TRUTH,
            "不得在代码、日志或响应中泄露密码、Token、API Key 或私钥。",
            "输出应直接回应用户目标，遵守已明确的交付范围和格式；仅在任务需要且未限制额外说明时，说明关键依据、适用范围和限制条件。",
            PATH_PREFIX + ".env、**/*.pem、**/*.key、生产环境配置。",
            ACTION_PREFIX + "删除或覆盖文件、数据库结构迁移、升级核心依赖、生产环境部署。"
    );

    private PlatformConstraintRules() { }

    /** 含肯定代码交付时固定输出五条，其他任务仅固定输出事实边界。 */
    public static List<String> forTask(boolean engineering) {
        return engineering ? CODE_RULES : List.of(TRUTH);
    }
}
