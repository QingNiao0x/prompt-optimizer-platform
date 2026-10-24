package com.promptoptimizer.analytics.domain;

/**
 * 浏览器事件协议；页面访问仅保留解码兼容，结果导出继续可靠采集。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public enum ClientAnalyticsEventType {
    /** 旧版本请求可正常结束，但服务端忽略该事件，避免旧客户端无限重试。 */
    APP_VISIT,
    RESULT_EXPORTED
}
