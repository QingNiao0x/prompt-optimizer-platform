package com.promptoptimizer.enhancement.application;

/** 共享存储不可用时拒绝继续创建不可跨实例读取的计划。 */
public class PlanningStoreUnavailableException extends RuntimeException {
    public PlanningStoreUnavailableException() { super("计划会话存储暂时不可用，请稍后重试。"); }
}
