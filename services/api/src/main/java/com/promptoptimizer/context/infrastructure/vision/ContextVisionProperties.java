package com.promptoptimizer.context.infrastructure.vision;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 视觉识别的独立任务预算；不放宽文件上传、全文索引或最终上下文的已有上限。
 * @author QingNiao
 * @since 0.1.0
 */
@Component
@ConfigurationProperties(prefix = "app.context.vision")
public class ContextVisionProperties {
    private int maxPages = 10;
    private int maxDocumentPages = 500;
    private int maxCalls = 10;
    private int maxOutputTokens = 8000;
    private int deadlineSeconds = 180;
    public int getMaxPages() { return maxPages; }
    public void setMaxPages(int value) { maxPages = Math.max(1, Math.min(50, value)); }
    public int getMaxDocumentPages() { return maxDocumentPages; }
    public void setMaxDocumentPages(int value) { maxDocumentPages = Math.max(1, Math.min(2000, value)); }
    public int getMaxCalls() { return maxCalls; }
    public void setMaxCalls(int value) { maxCalls = Math.max(1, Math.min(50, value)); }
    public int getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(int value) { maxOutputTokens = Math.max(1024, Math.min(16000, value)); }
    public int getDeadlineSeconds() { return deadlineSeconds; }
    public void setDeadlineSeconds(int value) { deadlineSeconds = Math.max(30, Math.min(600, value)); }
}
