package com.promptoptimizer.enhancement.api;

import com.promptoptimizer.enhancement.application.PlanningSessionService;
import com.promptoptimizer.enhancement.application.PlanQualityMetrics;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 收集最佳努力的 Plan 交互统计，不接收或保存回答正文。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@RestController
@RequestMapping("/api/v1/optimizations/plan-events")
public class PlanningInteractionController {
    private final PlanningSessionService sessions;
    private final PlanQualityMetrics metrics;
    private final ConcurrentHashMap<String, Instant> seen = new ConcurrentHashMap<>();
    public PlanningInteractionController(PlanningSessionService sessions, PlanQualityMetrics metrics) {
        this.sessions = sessions;
        this.metrics = metrics;
    }
    /**
     * 校验计划可访问性后去重计数；过期键和容量上限限制统计状态的内存占用。
     */
    @PostMapping
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public synchronized void record(@Valid @RequestBody Interaction request) {
        sessions.assertAccessible(request.planId());
        Instant now = Instant.now();
        seen.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
        if (seen.size() >= 5_000) return;
        if (seen.putIfAbsent(request.planId() + ":" + request.event(), now.plusSeconds(1_800)) == null) {
            metrics.event(request.event());
        }
    }
    /**
     * 仅包含计划标识与有限枚举事件，不接收用户回答正文。
     *
     * @author QingNiao
     * @since 0.1.0
     */
    public record Interaction(@NotBlank @Size(max = 64) String planId,
                              @NotNull PlanQualityMetrics.Event event) { }
}
