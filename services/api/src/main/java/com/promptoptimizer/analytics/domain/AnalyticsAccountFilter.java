package com.promptoptimizer.analytics.domain;

import com.promptoptimizer.common.exception.InvalidOptimizationRequestException;

import java.util.Locale;
import java.util.UUID;

/**
 * 管理员统计共用的账号条件；ID 精确匹配，邮箱与显示名称按当前资料进行字面关键词匹配。
 * 多个条件取交集，空白关键词表示不筛选；不会改变统计的稳定账号 ID 或平台管理员范围。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record AnalyticsAccountFilter(UUID userId, String email, String displayName) {

    /** 统一各查询入口的空白、长度及大小写处理，禁止将超长条件带入聚合查询。 */
    public AnalyticsAccountFilter {
        email = normalize(email, 320, "登录邮箱");
        displayName = normalize(displayName, 80, "显示名称");
    }

    /** 保留原有按单个账号查询的语义，供内部调用方构造条件。 */
    public static AnalyticsAccountFilter forUser(UUID userId) {
        return new AnalyticsAccountFilter(userId, null, null);
    }

    /** 对关键词本身做归一化；SQL 使用参数绑定和 strpos，百分号、下划线不会充当通配符。 */
    private static String normalize(String value, int maxLength, String label) {
        if (value == null) {
            return null;
        }
        if (value.length() > maxLength) {
            throw new InvalidOptimizationRequestException(label + "筛选最多 " + maxLength + " 个字符");
        }
        String normalized = value.strip();
        return normalized.isEmpty() ? null : normalized.toLowerCase(Locale.ROOT);
    }
}
