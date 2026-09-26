package com.promptoptimizer.analytics.domain;

/**
 * 审计 JSONB 设备字典：MOBILE 手机、TABLET 平板、DESKTOP 电脑、UNKNOWN 无法识别；应用当前不写预留值。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public enum AnalyticsDeviceType {
    MOBILE,
    TABLET,
    DESKTOP,
    UNKNOWN
}
