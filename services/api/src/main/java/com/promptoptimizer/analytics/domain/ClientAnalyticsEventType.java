package com.promptoptimizer.analytics.domain;

/**
 * 浏览器允许提交的事件白名单；身份、IP、设备和时间均由服务端采集。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public enum ClientAnalyticsEventType {
    APP_VISIT,
    RESULT_EXPORTED
}
