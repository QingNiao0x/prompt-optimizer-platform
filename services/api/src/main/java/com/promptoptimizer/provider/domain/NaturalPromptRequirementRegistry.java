package com.promptoptimizer.provider.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 49在既有范围调用中核验原要求出处并发放本请求的引用编号，不建立新事实或权限。
 * 准备原件与不可变条目保留旧解释的证据链；正文补正必须另给原始依据，不能自动继承引用冒充已保留题答。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class NaturalPromptRequirementRegistry {
    /** 准备阶段只提交范围和原要求，不接收模型发放的reqId或尚不存在的正文引文。 */
    public record Preparation(List<NaturalTaskScope.Action> actions, NaturalTaskScope.Response response,
            List<NaturalPromptPhasePayloads.Requirement> requirements) {
        public Preparation { requirements = requirements == null ? null : List.copyOf(requirements); }

        /** 核验完整来源与全部实际题答后才编号；advisory分类和临时校验目标不控制是否生成正文。 */
        public Prepared resolve(NaturalPromptInput input) {
            var scope = new NaturalTaskScope(actions, response).resolveAdvisory(input);
            List<NaturalTaskUnderstanding.Requirement> original = bounded(requirements, 256).stream()
                    .map(NaturalPromptRequirementRegistry::requirement).toList();
            // 这里只复用原来源/容量保护。首条动作仅满足内部形状，不绑定最终goal或充分性状态。
            var checked = new NaturalTaskUnderstanding(scope.actions().getFirst().text(), scope.deliverable(), original,
                    List.of(), List.of(), new NaturalTaskReview(NaturalTaskReview.InputReadiness.NOT_ASSESSED, List.of(), List.of(), List.of()))
                    .resolve(input);
            List<Entry> entries = new ArrayList<>();
            for (int index = 0; index < checked.requirements().size(); index++) {
                var value = checked.requirements().get(index);
                entries.add(new Entry("r" + index, value.text(), value.dimensions(), value.evidence().stream()
                        .map(evidence -> new NaturalPromptPhasePayloads.SourceEvidence(evidence.sourceId(), evidence.quote())).toList()));
            }
            return new Prepared(scope, entries);
        }
    }

    /** 已核验出处的候选要求；reqId只在当前调用内有效，不能当taskSources的sourceId使用。 */
    public record Entry(String reqId, String text, List<NaturalTaskUnderstanding.Dimension> dimensions,
            List<NaturalPromptPhasePayloads.SourceEvidence> evidence) {
        public Entry { dimensions = List.copyOf(dimensions); evidence = List.copyOf(evidence); }

        /** 恢复同一原要求记录；最终resolve仍会按完整原input重验来源类别与实际题答。 */
        public NaturalTaskUnderstanding.Requirement requirement() {
            return NaturalPromptRequirementRegistry.requirement(new NaturalPromptPhasePayloads.Requirement(text, dimensions, evidence));
        }
    }

    /** 同请求的不可变准备结果；保留原列表供补正审计，不因writer替换而改写准备依据。 */
    public record Prepared(NaturalTaskScope scope, List<Entry> requirements) {
        public Prepared {
            requirements = bounded(requirements, 256);
            if (scope == null || requirements.isEmpty()) throw invalid("原要求准备结果不完整");
            for (int index = 0; index < requirements.size(); index++) {
                if (requirements.get(index) == null || !("r" + index).equals(requirements.get(index).reqId())) {
                    throw invalid("原要求编号不是本次服务端顺序");
                }
            }
        }
    }

    /** 转换模型显式给出的依据，不补原句、维度或来源；新旧要求均复用同一canonical校验。 */
    static NaturalTaskUnderstanding.Requirement requirement(NaturalPromptPhasePayloads.Requirement value) {
        if (value == null) throw invalid("原要求为空");
        var evidence = bounded(value.evidence(), 64).stream()
                .map(item -> new NaturalTaskUnderstanding.Evidence(item.sourceId(), item.quote())).toList();
        return new NaturalTaskUnderstanding.Requirement(value.text(), evidence, bounded(value.dimensions(), NaturalTaskUnderstanding.Dimension.values().length));
    }

    /** 容量与原协议一致，不截断模型列表或把缺失列表默认为已完成。 */
    static <T> List<T> bounded(List<T> values, int maximum) {
        if (values == null || values.size() > maximum || values.stream().anyMatch(java.util.Objects::isNull)) {
            throw invalid("原要求引用协议列表缺失、为空条目或超出容量");
        }
        return List.copyOf(values);
    }

    static ProviderException invalid(String message) {
        return new ProviderException(ProviderFailureType.INVALID_RESPONSE, message, false);
    }

    private NaturalPromptRequirementRegistry() { }
}
