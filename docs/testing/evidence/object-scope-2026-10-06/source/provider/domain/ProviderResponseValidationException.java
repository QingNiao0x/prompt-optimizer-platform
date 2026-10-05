package com.promptoptimizer.provider.domain;

import java.util.Objects;

/**
 * 记录结构化结果失败的固定原因和字段位置，不携带模型正文或用户资料。
 * 原因同时用于内部诊断与有限修复，对外仍使用既有 RESULT_INVALID 契约。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class ProviderResponseValidationException extends ProviderException {
    private final Reason reason;
    private final String field;
    private final int modelAttempts;

    /** 字段只允许程序定义的 JSON 路径，禁止把模型值、问题 ID 或原文当成诊断位置。 */
    public ProviderResponseValidationException(Reason reason, String field) {
        this(reason, field, null, 0);
    }

    /** 保留解析器原始 cause 供受控诊断；公开响应与日志只使用固定 reason/field，不读取 cause 消息。 */
    public ProviderResponseValidationException(Reason reason, String field, Throwable cause) {
        this(reason, field, cause, 0);
    }

    private ProviderResponseValidationException(Reason reason, String field, Throwable cause, int modelAttempts) {
        super(ProviderFailureType.INVALID_RESPONSE, reason == Reason.RULE_CONFLICT
                ? "模型返回结果与已明确的业务规则冲突，请重试。" : "模型返回结果未通过结构与安全校验", false, cause);
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
        if (field == null || !field.matches("[A-Za-z][A-Za-z0-9_.\\[\\]]{0,119}")) {
            throw new IllegalArgumentException("validation field must be a bounded code-defined path");
        }
        this.field = field;
        this.modelAttempts = modelAttempts;
    }

    public Reason getReason() { return reason; }
    public String getField() { return field; }

    /** 0 表示未附加已核实的预算循环次数；响应省略该值，不用推测次数冒充事实。 */
    public int getModelAttempts() { return modelAttempts; }

    /** 最终可修复响应失败附带实际循环次数，保持失败对象不可变并保留原始 cause。 */
    public ProviderResponseValidationException withModelAttempts(int attempts) {
        if (attempts < 1 || attempts > 10) throw new IllegalArgumentException("model attempts must be bounded");
        return new ProviderResponseValidationException(reason, field, getCause(), attempts);
    }

    /** 固定服务端事实或元数据不能由模型重写；失败仍被拦截，但不为它重复付费调用。 */
    public boolean isModelRepairable() {
        return !field.startsWith("context.") && reason != Reason.METADATA_INVALID
                && reason != Reason.CONFIRMED_DECISION_MISSING;
    }

    /** 固定修复要求不能引用或复述导致校验失败的敏感内容。 */
    public enum Reason {
        RESPONSE_EMPTY("上游 choices 的第一项必须包含非空 message.content。"),
        RESPONSE_TRUNCATED("输出达到长度上限；在有限输出预算内重新生成完整 JSON，减少重复文字而保留所有实质要求。"),
        PLAN_JSON_INVALID("只返回可解析的单个计划 JSON 对象，字段类型与计划契约一致。"),
        METADATA_INVALID("保留所选模型的有效结果元数据。"),
        SECTION_INVALID("各段落使用合法且不重复的 type、非空字符串 title 和 content，保留四要素。"),
        REQUIRED_SECTIONS_MISSING("必须完整返回 BACKGROUND、TASK、OUTPUT、CONSTRAINTS 四个段落。"),
        AMBIGUITY_COUNT_INVALID("待确认事项使用顶层 ambiguities 字符串数组，最多八项；合并重复项但保留所有不同的实质未决条件。"),
        AMBIGUITY_VALUE_INVALID("每条待确认事项必须为非空字符串，最多五百字符，不得删除实质未决条件。"),
        SENSITIVE_CONTENT("使用不含凭据值的业务表述；隐私要求写成完整句子，避免 password: 或 apiKey= 等赋值形式；不得输出真实或伪造的密钥、密码、认证头或私钥。"),
        CONFIRMED_DECISION_MISSING("完整保留输入中用户已确认的答案和适用边界，不得替用户更改决定。"),
        RULE_CONFLICT("检查并改正执行要求与原始需求、明确确认答案及适用资料规则相反的内容；保留否定、条件、比较符、数值、单位和作用对象。取消保持原值不等于移除保持逻辑，向空字段补值不等于将字段清空。不得在另一段追加正确规则却保留矛盾指令。"),
        SOURCE_SCOPE_CONFLICT("资料取值与对象、属性、版本及适用条件分别核对。只写一版与另一版而未明确对应关系的资料，保持版本对应待核实；不得按出现次序绑定到具体版本或拼成统一规则。其他属性的版本证据不能证明本属性。"),
        PLAN_STRUCTURE_INVALID("summary 为非空字符串且不超过五百字符，questions 数组最多八项，不展示内部实现术语。"),
        PLAN_QUESTION_INVALID("问题 ID 唯一且仅含英文字母、数字、下划线或连字符，最长六十四字符；question 非空且最长三百字符，hint 最长五百字符；type 合法；最多四个简短示例。题干和 hint 不展示 TemplateCode、模板枚举代码、选择任务模板、确认缺失维度、缺失维度等内部术语。"),
        PLAN_OPTION_INVALID("选项 ID 唯一且最长六十四字符；label 最长一百二十字符，description 和 recommendationReason 最长三百字符，answer 最长一千五百字符；选择题保留二至五项，FREE_TEXT 的 options 必须为空；多选答案合计含分隔符不能超过一千五百字符。"),
        PLAN_RECOMMENDATION_INVALID("每道题至多一个 recommended=true，没有唯一依据时可以全部为 false；完整保留合法问题、选项及答案。");

        private final String repairInstruction;

        Reason(String repairInstruction) { this.repairInstruction = repairInstruction; }

        /** 返回不含用户内容的静态修复提示。 */
        public String repairInstruction() { return repairInstruction; }
    }
}
