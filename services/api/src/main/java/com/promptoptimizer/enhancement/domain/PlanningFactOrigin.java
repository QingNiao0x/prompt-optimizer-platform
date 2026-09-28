package com.promptoptimizer.enhancement.domain;

/** 标识证据用途，不代表该内容已经部署或经过业务真实性验证；旧值保持兼容。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public enum PlanningFactOrigin {
    /** 项目源码或构建配置，不能仅凭存在判断业务已上线。 */
    PROJECT_SOURCE,
    /** 用户业务资料，包括研究材料和外部方案，不能直接视为现有实现。 */
    USER_MATERIAL,
    /** 项目说明、规范或设计文档，须区分现状和计划。 */
    PROJECT_DOCUMENT,
    /** 测试代码中的断言和样例，不代表生产数据或技术选型。 */
    TEST_SOURCE,
    /** 测试夹具和模拟数据，只在相关测试任务中作为样例使用。 */
    TEST_FIXTURE,
    /** 示例项目或演示材料，不代表当前业务规则。 */
    EXAMPLE_MATERIAL,
    /** 构建、覆盖率或测试工具生成的报告。 */
    GENERATED_REPORT,
    /** 用途未能通过材料路径和类型识别，不升级为已验证实现。 */
    UNKNOWN
}
