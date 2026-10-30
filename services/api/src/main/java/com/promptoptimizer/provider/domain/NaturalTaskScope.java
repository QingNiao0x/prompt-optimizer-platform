package com.promptoptimizer.provider.domain;

import com.promptoptimizer.common.security.SensitiveValueScanner;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 当前任务的动作与回应边界。由独立理解阶段产生，正文和Plan复用，不作为新来源或用户授权。
 * 来源匹配只能证明可追溯，不能证明分类语义正确；完整原文和实际题答仍须交给后续阶段。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record NaturalTaskScope(List<Action> actions, Response response) {
    /** 描述性动作与未来条件动作不自动成为本次要执行的动作。 */
    public enum Timing { CURRENT, CONDITIONAL, DESCRIPTIVE }
    /** 未指定成果本身不是缺口；必要回应须从原动作推导，不能增加独立作品。 */
    public enum ResponseBasis { EXPLICIT, INHERENT, UNSPECIFIED }

    /** 原句来源沿用服务端目录，只记录连续片段，不接收模型自报的来源类别。 */
    public record Evidence(String sourceId, String quote) { }
    /** 动作的时态、条件与依据就地绑定；不用职业、场景或动作关键词路由。 */
    public record Action(String text, Timing timing, String condition, List<Evidence> evidence) {
        public Action { evidence = copy(evidence); }
    }
    /** 对外回应可以未指定；description不用于补造用户未要求的成果栏目。 */
    public record Response(ResponseBasis basis, String description, List<Evidence> evidence) {
        public Response { evidence = copy(evidence); }
    }

    public NaturalTaskScope { actions = copy(actions); }

    /**
     * 核对出处和最低结构，拒绝仅由资料或助手文本建立的当前命令。
     * 这里不根据引文存在宣称语义正确，也不以准备记录代替最终正文覆盖检查。
     */
    public NaturalTaskScope resolve(NaturalPromptInput input) {
        return resolve(input, true);
    }

    /**
     * 准备分类仅供参考时，不用CURRENT是否出现推断该不该继续生成或提问。
     * 合法动作、条件及用户来源仍全部核验；最终阶段另从完整原始提示词形成目标与覆盖记录。
     */
    public NaturalTaskScope resolveAdvisory(NaturalPromptInput input) {
        return resolve(input, false);
    }

    private NaturalTaskScope resolve(NaturalPromptInput input, boolean requireCurrent) {
        if (actions == null || actions.isEmpty() || actions.size() > 64 || response == null || response.basis() == null) {
            throw invalid();
        }
        Map<String, NaturalTaskSource> sources = input.taskSources().stream()
                .collect(Collectors.toMap(NaturalTaskSource::id, Function.identity()));
        boolean current = false;
        for (int i = 0; i < actions.size(); i++) {
            Action action = actions.get(i);
            text(action.text(), false);
            text(action.condition(), true);
            if (action.timing() == null || (action.timing() == Timing.CONDITIONAL && action.condition().isBlank())) throw invalid();
            evidence(action.evidence(), sources, action.timing() != Timing.DESCRIPTIVE, "actions[" + i + "].evidence");
            current |= action.timing() == Timing.CURRENT;
        }
        if (requireCurrent && !current) throw invalid();
        text(response.description(), response.basis() == ResponseBasis.UNSPECIFIED);
        if (response.basis() == ResponseBasis.UNSPECIFIED) {
            if (!response.description().isEmpty() || response.evidence() == null || !response.evidence().isEmpty()) throw invalid();
        } else {
            evidence(response.evidence(), sources, true, "response.evidence");
        }
        return this;
    }

    /** 后续内部任务记录沿用同一当前动作，不重新猜测目标；原有条件仍由完整输入及覆盖记录保存。 */
    public String goal() {
        return actions.stream().filter(action -> action.timing() == Timing.CURRENT)
                .map(Action::text).collect(Collectors.joining("；"));
    }

    /** 无独立成果时为空；不得用准备阶段的存在推导必须提交文档。 */
    public String deliverable() { return response.description(); }

    /** 出处必须真实存在；当前任务至少有一处用户来源，资料仍可佐证对象细节。 */
    private static void evidence(List<Evidence> evidence, Map<String, NaturalTaskSource> sources, boolean userRequired, String field) {
        if (evidence == null || evidence.isEmpty() || evidence.size() > 64) throw invalid();
        boolean user = false;
        for (Evidence value : evidence) {
            text(value.sourceId(), false); text(value.quote(), false);
            NaturalTaskSource source = sources.get(value.sourceId());
            if (source == null || !source.text().contains(value.quote())) throw invalid();
            user |= switch (source.kind()) {
                case ORIGINAL_REQUEST, USER_CONVERSATION, BOUND_PLAN_ANSWER -> true;
                case PROJECT_CONTEXT, ASSISTANT_CONVERSATION -> false;
            };
        }
        if (userRequired && !user) throw new MissingUserAuthority(field);
    }

    /**
     * 引文存在但未证明用户委托时，只报告服务端生成的字段位置；不回传源码、引文或任意异常文本。
     * 该诊断不自动补证据、不把材料改成用户来源；重试仍须通过相同的来源及最终正文覆盖校验。
     */
    public static final class MissingUserAuthority extends ProviderException {
        private final String field;

        private MissingUserAuthority(String field) {
            super(ProviderFailureType.INVALID_RESPONSE, "任务动作与回应边界无效", false);
            this.field = field;
        }

        /** 只由core-repair的协议补正使用；旧版本不会追加此提示。 */
        public String repairInstruction() {
            return "\n" + field + "缺少用户来源。当前动作及非空回应依据须至少引用一处原始提示词、用户对话或实际Plan回答中的真实连续原句；"
                    + "项目材料只能佐证细节，不能单独建立委托。重新核对完整输入，不伪造引文，不将材料改标为用户来源。";
        }
    }

    /** 只约束内部记录容量，不截断实际输入，也不放宽缺失列表为合法空列表。 */
    private static <T> List<T> copy(List<T> values) {
        if (values == null) return null;
        if (values.stream().anyMatch(java.util.Objects::isNull)) throw invalid();
        return List.copyOf(values);
    }

    private static void text(String value, boolean emptyAllowed) {
        if (value == null || (!emptyAllowed && value.isBlank()) || value.length() > 20_000
                || new SensitiveValueScanner().containsCredential(value)) throw invalid();
    }

    private static ProviderException invalid() {
        return new ProviderException(ProviderFailureType.INVALID_RESPONSE, "任务动作与回应边界无效", false);
    }
}
