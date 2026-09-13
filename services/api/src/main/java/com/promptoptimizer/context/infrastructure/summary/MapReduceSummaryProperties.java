package com.promptoptimizer.context.infrastructure.summary;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @DateTime: 2026-09-13
 * @Author: QingNiao
 * @ProjectName: prompt-optimizer-platform
 * @Description: 定义 Map-Reduce 全文摘要的批次、输出长度和费用保护配置。
 */
@Validated
@ConfigurationProperties(prefix = "app.summary.map-reduce")
public class MapReduceSummaryProperties {

    private boolean enabled;

    @Min(1)
    @Max(64)
    private int mapBatchSize = 8;

    @Min(2)
    @Max(128)
    private int reduceBatchSize = 24;

    @Min(6_000)
    @Max(500_000)
    private int maxBatchCharacters = 48_000;

    @Min(200)
    @Max(8_000)
    private int intermediateSummaryCharacters = 1_200;

    @Min(400)
    @Max(12_000)
    private int finalSummaryCharacters = 1_800;

    @Min(1)
    @Max(2_048)
    private int maxMapCalls = 256;

    @Min(1)
    @Max(256)
    private int maxReduceCalls = 32;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMapBatchSize() {
        return mapBatchSize;
    }

    public void setMapBatchSize(int mapBatchSize) {
        this.mapBatchSize = mapBatchSize;
    }

    public int getReduceBatchSize() {
        return reduceBatchSize;
    }

    public void setReduceBatchSize(int reduceBatchSize) {
        this.reduceBatchSize = reduceBatchSize;
    }

    public int getMaxBatchCharacters() {
        return maxBatchCharacters;
    }

    public void setMaxBatchCharacters(int maxBatchCharacters) {
        this.maxBatchCharacters = maxBatchCharacters;
    }

    public int getIntermediateSummaryCharacters() {
        return intermediateSummaryCharacters;
    }

    public void setIntermediateSummaryCharacters(int intermediateSummaryCharacters) {
        this.intermediateSummaryCharacters = intermediateSummaryCharacters;
    }

    public int getFinalSummaryCharacters() {
        return finalSummaryCharacters;
    }

    public void setFinalSummaryCharacters(int finalSummaryCharacters) {
        this.finalSummaryCharacters = finalSummaryCharacters;
    }

    public int getMaxMapCalls() {
        return maxMapCalls;
    }

    public void setMaxMapCalls(int maxMapCalls) {
        this.maxMapCalls = maxMapCalls;
    }

    public int getMaxReduceCalls() {
        return maxReduceCalls;
    }

    public void setMaxReduceCalls(int maxReduceCalls) {
        this.maxReduceCalls = maxReduceCalls;
    }
}
