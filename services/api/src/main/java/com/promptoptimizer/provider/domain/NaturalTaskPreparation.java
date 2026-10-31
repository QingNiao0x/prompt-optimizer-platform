package com.promptoptimizer.provider.domain;

import com.promptoptimizer.common.security.SensitiveValueScanner;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 在同一次范围准备中区分执行者工作与需要用户决定的信息，供正文和Plan复用。
 * 该记录仅是当前请求内的模型解释，不是新来源、现实资料状态、用户确认或执行授权。
 * 未提交附件不能推导为现实缺资料；责任分类的语义正确性须由真实任务对照验证，
 * 本类只校验引用及结构，执行者工作不能直接成为Plan问题；准备分类有误时可引用用户原文纠正。
 * 不要求罗列所有步骤或资料，不更改冻结版本的范围协议，也不替代最终正文的要求覆盖检查。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record NaturalTaskPreparation(NaturalTaskScope taskScope, List<Dependency> dependencies) {
    private static final int MAX_DEPENDENCIES = 64;
    private static final int MAX_EVIDENCE = 64;
    private static final int MAX_TEXT_LENGTH = 20_000;
    private static final int MAX_ID_LENGTH = 128;

    /**
     * 责任指派只表达如何推进原任务，不表达资料是否真实存在；未知类别不默认归给用户。
     * 用户明确保留的信息或授权仍须按原条件处理，不能因模型建议而转给执行者决定。
     */
    public enum Owner {
        /** 原委托内由执行者读取、检索、分析或设计的工作，不单独建立前置提问。 */
        EXECUTOR_WORK,
        /** 需要用户独有信息、保留选择或授权，且不同回答会实质改变结果的决定。 */
        USER_DECISION
    }

    /**
     * 本次任务相关的材料或决定；action说明处理动作，reason说明其与原任务的关系及影响。
     * evidence必须回指原输入，不能引用准备记录自身；所有权分类不建立新的事实或权限。
     */
    public record Dependency(String id, String subject, Owner owner, String action, String reason,
            List<NaturalTaskScope.Evidence> evidence) {
        public Dependency { evidence = copy(evidence); }
    }

    public NaturalTaskPreparation { dependencies = copy(dependencies); }

    /**
     * 保留原范围校验，核对条目容量、唯一编号、敏感值及原始出处。
     * 用户决定必须有用户来源支持，但不要求原文明确出现“未知”等词；缺少的是决定本身，
     * 不是证明用户已承认资料缺失。出处真实仅说明可追溯，不证明模型分类的语义正确。
     */
    public NaturalTaskPreparation resolve(NaturalPromptInput input) {
        if (taskScope == null || dependencies == null || dependencies.size() > MAX_DEPENDENCIES) throw invalid();
        taskScope.resolve(input);
        Map<String, NaturalTaskSource> sources = input.taskSources().stream()
                .collect(Collectors.toMap(NaturalTaskSource::id, Function.identity()));
        var ids = new HashSet<String>();
        for (Dependency dependency : dependencies) {
            text(dependency.id(), MAX_ID_LENGTH);
            if (!ids.add(dependency.id()) || dependency.owner() == null) throw invalid();
            text(dependency.subject(), MAX_TEXT_LENGTH);
            text(dependency.action(), MAX_TEXT_LENGTH);
            text(dependency.reason(), MAX_TEXT_LENGTH);
            validateEvidence(dependency.evidence(), sources, dependency.owner() == Owner.USER_DECISION);
        }
        return this;
    }

    /**
     * 正常提问引用须来自准备记录中的USER_DECISION，不能直接把执行者工作重新派给用户。
     * 发现准备误判或遗漏时使用有原始来源的纠正路径，不把前一阶段解释当作绝对事实。
     * 本方法不证明问题有价值；提问影响及全部真实题答仍走原有服务校验。
     */
    public void validateDecisionReference(String id) {
        text(id, MAX_ID_LENGTH);
        if (dependencies == null || dependencies.stream().noneMatch(dependency -> dependency.id().equals(id)
                && dependency.owner() == Owner.USER_DECISION)) throw invalid();
    }

    /**
     * 允许后续阶段依原输入纠正准备分类或补回被遗漏的重要决定，不要求原文出现“未知”等字眼。
     * 只接收可核查的连续原句及用户来源，不能用准备记录或助手建议自证用户决定。
     * 引用合法仍不证明纠正语义成立；原因、实际问题和完整输入须在语义评测中共同核查。
     */
    public void validateDecisionCorrection(List<NaturalTaskScope.Evidence> evidence, NaturalPromptInput input) {
        Map<String, NaturalTaskSource> sources = input.taskSources().stream()
                .collect(Collectors.toMap(NaturalTaskSource::id, Function.identity()));
        validateEvidence(evidence, sources, true);
    }

    /**
     * 原记录中的用户决定可以因原文已给出答案或已委托设计而被纠正为无需继续提问。
     * 仅允许引用已有USER_DECISION，并要求用户来源；不接受新编号或执行者条目伪造解决状态。
     * 本检查不把模型“已解决”的声明提升为事实或用户确认，最终正文与实际题答保护继续有效。
     */
    public void validateResolvedDecision(String id, List<NaturalTaskScope.Evidence> evidence, NaturalPromptInput input) {
        validateDecisionReference(id);
        validateDecisionCorrection(evidence, input);
    }

    /** 核对连续原句并采用服务端来源类别，项目资料或助手建议不能单独建立或撤销用户决定。 */
    private static void validateEvidence(List<NaturalTaskScope.Evidence> values,
            Map<String, NaturalTaskSource> sources, boolean userRequired) {
        if (values == null || values.isEmpty() || values.size() > MAX_EVIDENCE) throw invalid();
        boolean hasUserSource = false;
        for (NaturalTaskScope.Evidence evidence : values) {
            if (evidence == null) throw invalid();
            text(evidence.sourceId(), MAX_TEXT_LENGTH);
            text(evidence.quote(), MAX_TEXT_LENGTH);
            NaturalTaskSource source = sources.get(evidence.sourceId());
            if (source == null || !source.text().contains(evidence.quote())) throw invalid();
            hasUserSource |= switch (source.kind()) {
                case ORIGINAL_REQUEST, USER_CONVERSATION, BOUND_PLAN_ANSWER, USER_REVISION -> true;
                case PROJECT_CONTEXT, ASSISTANT_CONVERSATION -> false;
            };
        }
        // 资料可解释读取对象，但只有用户来源能支持把某项选择保留给用户。
        if (userRequired && !hasUserSource) throw invalid();
    }

    /** 请求内辅助字段与最终正文采用同一凭据识别器，避免经内部记录泄露敏感值。 */
    private static void text(String value, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum
                || new SensitiveValueScanner().containsCredential(value)) throw invalid();
    }

    /** 缺失列表保留为缺失以便拒绝；合法空列表表示没有需要单独分派的依赖，不自动造条目。 */
    private static <T> List<T> copy(List<T> values) {
        if (values == null) return null;
        if (values.stream().anyMatch(java.util.Objects::isNull)) throw invalid();
        return List.copyOf(values);
    }

    /** 使用稳定错误而不回显模型原文或用户材料，沿用既有协议修复计量。 */
    private static ProviderException invalid() {
        return new ProviderException(ProviderFailureType.INVALID_RESPONSE, "任务执行责任记录无效", false);
    }
}
