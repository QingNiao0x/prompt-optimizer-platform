package com.promptoptimizer.context.api;

import com.promptoptimizer.common.api.ApiResponse;
import com.promptoptimizer.common.web.RequestIdFilter;
import com.promptoptimizer.enhancement.application.PlanningSessionService;
import com.promptoptimizer.enhancement.domain.PlanningContextPreparation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 为上下文感知的 Plan Mode 准备短期、安全摘要。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@RestController
@RequestMapping("/api/v1/context/planning")
public class PlanningContextController {

    private final PlanningSessionService planningSessionService;

    public PlanningContextController(PlanningSessionService planningSessionService) {
        this.planningSessionService = planningSessionService;
    }

    @PostMapping
    public ApiResponse<PlanningContextPreparation> prepare(
            @Valid @RequestBody PlanningContextRequest request,
            HttpServletRequest httpRequest
    ) {
        String requestId = (String) httpRequest.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        return ApiResponse.success(requestId, planningSessionService.prepareContext(request));
    }
}
