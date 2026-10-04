package com.promptoptimizer.common.logging;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * 用单调时钟记录提示词链路的阶段耗时，区分上下文、上游调用和结果组装。
 * 标签仅由代码及已选模型构成，不记录需求、答案、路径、凭据或异常正文；失败原样传播。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class PipelineStageTiming {
    private static final Logger LOGGER = LoggerFactory.getLogger(PipelineStageTiming.class);

    private PipelineStageTiming() { }

    /** 不改变执行顺序、重试预算或异常类型，失败时也留下对应阶段与耗时。 */
    public static <T> T measure(String operation, String stage, String model, Supplier<T> action) {
        long started = System.nanoTime();
        boolean completed = false;
        try {
            T result = action.get();
            completed = true;
            return result;
        } finally {
            LOGGER.info("event=pipeline.stage.completed requestId={} workflowId={} operation={} stage={} model={} status={} durationMs={}",
                    LogFields.value(MDC.get("requestId")), LogFields.value(MDC.get("workflowId")),
                    LogFields.value(operation), LogFields.value(stage), LogFields.value(model),
                    completed ? "SUCCESS" : "FAILED", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        }
    }
}
