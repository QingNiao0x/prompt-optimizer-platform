package com.promptoptimizer.provider.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 范围准备的来源分工：用户证据建立委托，材料证据限定细节；转换为现有范围对象而不创建授权。
 * 只用于v4，不放宽旧范围解析，不根据任务关键词补造或选择用户引文。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record NaturalTaskScopeSources(List<Action> actions, Response response) {
    public record Action(String text, NaturalTaskScope.Timing timing, String condition,
            List<NaturalTaskScope.Evidence> userEvidence, List<NaturalTaskScope.Evidence> detailEvidence) { }

    public record Response(NaturalTaskScope.ResponseBasis basis, String description,
            List<NaturalTaskScope.Evidence> userEvidence, List<NaturalTaskScope.Evidence> detailEvidence) { }

    /** 模型必须给出真实委托引文；服务端仅合并两类已核验来源，不把材料改标为用户来源。 */
    public NaturalTaskScope toScope(NaturalPromptInput input) {
        if (actions == null || actions.isEmpty() || actions.size() > 64 || response == null) throw invalid();
        Map<String, NaturalTaskSource> sources = input.taskSources().stream()
                .collect(Collectors.toMap(NaturalTaskSource::id, Function.identity()));
        List<NaturalTaskScope.Action> normalized = new ArrayList<>();
        for (int i = 0; i < actions.size(); i++) {
            var action = actions.get(i);
            if (action == null) throw invalid();
            normalized.add(new NaturalTaskScope.Action(action.text(), action.timing(), action.condition(),
                    combine(action.userEvidence(), action.detailEvidence(), sources,
                            action.timing() != NaturalTaskScope.Timing.DESCRIPTIVE, "actions[" + i + "].userEvidence")));
        }
        var result = new NaturalTaskScope(normalized, new NaturalTaskScope.Response(response.basis(), response.description(),
                combine(response.userEvidence(), response.detailEvidence(), sources,
                        response.basis() != NaturalTaskScope.ResponseBasis.UNSPECIFIED, "response.userEvidence")));
        // advisory只解除范围分类的生成门控，原来源存在性、引文、容量与否定条件检查继续执行。
        return result.resolveAdvisory(input);
    }

    /** 同一条记录的用户证据不能由details中的引文代替，防止空委托靠材料凑成可执行动作。 */
    private static List<NaturalTaskScope.Evidence> combine(List<NaturalTaskScope.Evidence> user,
            List<NaturalTaskScope.Evidence> details, Map<String, NaturalTaskSource> sources, boolean required, String field) {
        if (user == null || details == null || user.size() + details.size() > 64) throw invalid();
        if (required && user.isEmpty()) throw new InvalidUserEvidence(field);
        for (var evidence : user) {
            if (evidence == null) throw invalid();
            var source = sources.get(evidence.sourceId());
            if (source == null || !switch (source.kind()) {
                case ORIGINAL_REQUEST, USER_CONVERSATION, BOUND_PLAN_ANSWER, USER_REVISION -> true;
                case PROJECT_CONTEXT, ASSISTANT_CONVERSATION -> false;
            }) throw new InvalidUserEvidence(field);
        }
        List<NaturalTaskScope.Evidence> combined = new ArrayList<>(user);
        combined.addAll(details);
        if (combined.stream().anyMatch(java.util.Objects::isNull)) throw invalid();
        return List.copyOf(combined);
    }

    /** 补正仅发送服务端确定的字段路径，不把来源内容或任意异常文本拼进system指导。 */
    public static final class InvalidUserEvidence extends ProviderException {
        private final String field;
        private InvalidUserEvidence(String field) {
            super(ProviderFailureType.INVALID_RESPONSE, "范围准备缺少有效用户委托来源", false);
            this.field = field;
        }

        public String repairInstruction() {
            return "\n" + field + "须引用原始提示词、用户对话、真实回答或修改意见中的委托原句。"
                    + "项目描述和文件的细节放入detailEvidence，同时保留原任务依据；没有用户委托的材料动作不得升级为当前任务。";
        }
    }

    private static ProviderException invalid() {
        return new ProviderException(ProviderFailureType.INVALID_RESPONSE, "范围准备来源协议无效", false);
    }
}
