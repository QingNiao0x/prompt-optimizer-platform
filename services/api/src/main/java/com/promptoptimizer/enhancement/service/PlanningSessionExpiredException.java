package com.promptoptimizer.enhancement.service;

import com.promptoptimizer.common.exception.InvalidOptimizationRequestException;

/** 对不存在、过期和非本人会话使用同一公开错误，避免泄露所有权。 */
public class PlanningSessionExpiredException extends InvalidOptimizationRequestException {
    public PlanningSessionExpiredException(String message) { super(message); }
}
