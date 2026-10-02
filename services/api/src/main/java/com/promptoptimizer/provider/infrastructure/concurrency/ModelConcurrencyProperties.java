package com.promptoptimizer.provider.infrastructure.concurrency;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 模型调用及账号请求的并发边界；生产环境通过 Redis 在所有 API 实例之间共享名额。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Validated
@ConfigurationProperties(prefix = "app.provider.concurrency")
public class ModelConcurrencyProperties {

    @Min(1)
    private int globalLimit = 100;
    @Min(1)
    private int userLimit = 3;
    @NotNull
    private StoreMode storeMode = StoreMode.REDIS;
    @NotBlank
    private String keyPrefix = "prompt-optimizer:model-concurrency";
    @NotNull
    private Duration leaseDuration = Duration.ofMinutes(5);
    @NotNull
    private Duration heartbeatInterval = Duration.ofSeconds(30);

    /** 预留至少三个续租周期，避免短暂调度延迟将仍在执行的调用判定为过期。 */
    @AssertTrue(message = "并发租约必须至少为 1 秒，续租间隔至少为 100 毫秒且不超过租约的三分之一")
    public boolean isLeaseConfigurationValid() {
        return leaseDuration != null && heartbeatInterval != null
                && leaseDuration.toMillis() >= 1_000 && heartbeatInterval.toMillis() >= 100
                && heartbeatInterval.compareTo(leaseDuration.dividedBy(3)) <= 0;
    }

    public int getGlobalLimit() { return globalLimit; }
    public void setGlobalLimit(int globalLimit) { this.globalLimit = globalLimit; }
    public int getUserLimit() { return userLimit; }
    public void setUserLimit(int userLimit) { this.userLimit = userLimit; }
    public StoreMode getStoreMode() { return storeMode; }
    public void setStoreMode(StoreMode storeMode) { this.storeMode = storeMode; }
    public String getKeyPrefix() { return keyPrefix; }
    public void setKeyPrefix(String keyPrefix) { this.keyPrefix = keyPrefix; }
    public Duration getLeaseDuration() { return leaseDuration; }
    public void setLeaseDuration(Duration leaseDuration) { this.leaseDuration = leaseDuration; }
    public Duration getHeartbeatInterval() { return heartbeatInterval; }
    public void setHeartbeatInterval(Duration heartbeatInterval) { this.heartbeatInterval = heartbeatInterval; }

    /** MEMORY 仅供显式选择的本地单实例环境；Redis 故障时不会自动切换存储。 */
    public enum StoreMode { REDIS, MEMORY }
}
