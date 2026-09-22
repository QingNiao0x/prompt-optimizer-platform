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

/** 最佳努力的交互统计，不接收或保存回答正文。 */
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
    public record Interaction(@NotBlank @Size(max = 64) String planId,
                              @NotNull PlanQualityMetrics.Event event) { }
}
