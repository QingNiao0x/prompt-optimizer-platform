package com.promptoptimizer.provider.domain;

import com.promptoptimizer.enhancement.domain.ConfirmedPlanDecision;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 将用户明说的受众和研究主题保留为带原文位置的不同语义锚点。
 * 不猜测研究样本、不以关键词数量判断充分性，也不覆盖服务端绑定的实际对象选择。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record TaskMeaningContract(List<Anchor> anchors, List<String> confirmedSubjects) {
    private static final Pattern AUDIENCE = Pattern.compile("(?:为|面向)([^，,。；;\\n]{1,40}?)(?:撰写|编写|写一|写|设计)");
    private static final Pattern TOPIC = Pattern.compile("(?:研究对象(?:为|是)|(?:^|[，,。；;\\n])研究)([^。；;\\n]{2,180})");
    private static final Pattern ASSERTED_SUBJECT = Pattern.compile("研究对象(?:为|是|包括|包含|确定为|设定为)\\s*([^。；;\\n]{1,180})");

    /** 锚点只在本请求中使用，不建立跨任务事实缓存。 */
    public TaskMeaningContract {
        anchors = List.copyOf(anchors);
        confirmedSubjects = List.copyOf(confirmedSubjects);
    }

    /** 来源位置直接取有效用户输入；有效 Plan 的研究对象选择具有自己的授权范围。 */
    public static TaskMeaningContract from(String raw, List<ConfirmedPlanDecision> decisions) {
        String input = raw == null ? "" : raw;
        var anchors = new ArrayList<Anchor>();
        collect(input, AUDIENCE, Role.AUDIENCE, anchors);
        collect(input, TOPIC, Role.RESEARCH_TOPIC, anchors);
        var chosen = decisions.stream().filter(decision -> decision.scope() != ConfirmedPlanDecision.Scope.UNRESOLVED
                && decision.question().matches("(?s).*(?:研究对象|招募对象|招募人群|样本人群|受试者人群).*")
                && !decision.question().matches("(?s).*(?:授权|获准|伦理|隐私|能否使用|是否允许).*"))
                .flatMap(decision -> anchors.stream().filter(anchor -> anchor.role() == Role.AUDIENCE
                        && positivelyNames(decision.answer(), anchor.text())).map(Anchor::text))
                .distinct().toList();
        return new TaskMeaningContract(anchors, chosen);
    }

    /** 选择只作用于实际命名的人群；仅提到、排除或推荐某对象不能放行所有受众。 */
    private static boolean positivelyNames(String answer, String subject) {
        var match = Pattern.compile(Pattern.quote(subject)).matcher(answer);
        while (match.find()) {
            String prefix = answer.substring(Math.max(0, match.start() - 12), match.start());
            if (!prefix.matches(".*(?:不是|并非|不选|不采用|排除|建议|可考虑|推荐|例如|候选).*")) return true;
        }
        return false;
    }

    /** 只收集有位置可回查的有限片段，避免再次传输整份需求或无界增加元数据。 */
    private static void collect(String raw, Pattern pattern, Role role, List<Anchor> target) {
        var match = pattern.matcher(raw);
        while (target.size() < 8 && match.find()) {
            target.add(new Anchor(role, match.group(1), match.start(1), match.end(1), Origin.EFFECTIVE_USER_INPUT));
        }
    }

    /** 在尾注和平台条款加入之前拒绝已复现的受众移位，不假称覆盖全部语义误绑定。 */
    public void validate(String content, String field) {
        if (anchors.stream().noneMatch(anchor -> anchor.role() == Role.RESEARCH_TOPIC)) return;
        for (String clause : content.split("[。；;\\n]")) {
            var subject = ASSERTED_SUBJECT.matcher(clause);
            while (subject.find()) {
                String prefix = clause.substring(Math.max(0, subject.start() - 12), subject.start());
                // 否定、引用和明确推荐不宣称这是用户原来指定的研究对象。
                if (prefix.matches(".*(?:不得|不能|不是|并非|例如|引用|建议|可考虑|候选).*")) continue;
                for (Anchor audience : anchors) {
                    if (audience.role() != Role.AUDIENCE || !subject.group(1).contains(audience.text())) continue;
                    boolean alsoExplicitTopic = anchors.stream().anyMatch(anchor -> anchor.role() == Role.RESEARCH_TOPIC
                            && anchor.text().contains(audience.text()));
                    if (!alsoExplicitTopic && !confirmedSubjects.contains(audience.text())) throw new ProviderResponseValidationException(
                            ProviderResponseValidationException.Reason.TASK_ROLE_CONFLICT, field);
                }
            }
        }
    }

    /** 原文片段是依据，不是模型推理过程；角色类型防止同一词被转用于另一对象。 */
    public record Anchor(Role role, String text, int start, int end, Origin origin) { }
    /** 写作受众与研究主题是不同角色，不能从一个角色推定另一个。 */
    public enum Role { AUDIENCE, RESEARCH_TOPIC }
    /** 有效输入由原始需求与服务端绑定决定组成；位置针对 effectivePrompt，不冒充附件摘录。 */
    public enum Origin { EFFECTIVE_USER_INPUT }
}
