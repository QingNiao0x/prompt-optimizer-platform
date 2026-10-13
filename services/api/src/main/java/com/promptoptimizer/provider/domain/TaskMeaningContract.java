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

    /**
     * 将本任务已有研究主题的来源用于评价步骤指导，不重新猜测对象或增加评分参数。
     * 这里只指导生成；来源和传递可本地校验，模型是否正确绑定仍须真实作品验证。
     */
    public String evaluationGuidance() {
        String sources = anchors.stream().filter(anchor -> anchor.role() == Role.RESEARCH_TOPIC)
                .map(anchor -> anchor.origin() + "[" + anchor.start() + "," + anchor.end() + ")")
                .collect(java.util.stream.Collectors.joining("、"));
        if (sources.isEmpty()) return "";
        return "\n本任务评价对象绑定：依据 taskView.userMeaningAnchors 中 RESEARCH_TOPIC 的来源 " + sources
                + "，结合有效确认，仅当原任务包含比较、评审或评分时，在 TASK 原有评价步骤中落实待评对象或产物、"
                + "评审所见材料及各项指标作用的对象。主题在背景出现不能替代步骤中的对象绑定。"
                + "原任务要求盲法时，说明对应材料如何盲化；未要求评价时不增加评分流程。"
                + "对象以用户任务为准，包括用户明确要求评价的下游作品；多个对象的主次关系未定时保持未决。"
                + "已明确的表述保留并使用一次，未确认的实际选值仍保持未决；已委托设计时可提出评分尺度、参数等"
                + "具体拟议建议及适用条件，不冒充用户确认或既有事实，不增加独立尾注。";
    }

    /**
     * 研究提问不能把写作受众预设为参与者；真正的人群选择问题和有本任务来源的对象声明仍允许。
     * 资料只提供支持，不从模型候选、测试夹具或假设句反向生成用户选择。
     */
    public void validatePlanningQuestion(String question, String effectiveInput, List<String> researchEvidence) {
        if (anchors.stream().noneMatch(anchor -> anchor.role() == Role.RESEARCH_TOPIC)) return;
        for (Anchor audience : anchors) {
            if (audience.role() != Role.AUDIENCE || !presupposesParticipant(question, audience.text())) continue;
            boolean supported = confirmedSubjects.contains(audience.text())
                    || explicitlyStudiesAudience(effectiveInput, audience.text())
                    || researchEvidence.stream().anyMatch(source -> declaresCurrentPopulation(source, audience.text()));
            if (!supported) throw new ProviderResponseValidationException(
                    ProviderResponseValidationException.Reason.TASK_ROLE_CONFLICT, "questions.question");
        }
    }

    /** 只拦截已复现的参与/招募身份预设；身份本身是条件或是否题时仍交用户澄清。 */
    private boolean presupposesParticipant(String question, String audience) {
        int named = question.indexOf(audience);
        if (named < 0) return false;
        String prefix = question.substring(0, named);
        if (prefix.matches("(?s).*(?:是否|能否|要不要|需不需要|如果|假如|若|建议|可考虑|候选).*")) return false;
        String subject = Pattern.quote(audience);
        return question.matches("(?s).*(?:参与[^，,。；;？?]{0,40}的|招募的)" + subject + ".*")
                || question.matches("(?s).*" + subject + "(?:对象|参与者|受试者|的招募).*");
    }

    /** 原研究主题可明确包含同一人群，但否定、未来条件及示例中的主题不提供当前选择。 */
    private boolean explicitlyStudiesAudience(String effectiveInput, String audience) {
        if (declaresCurrentPopulation(effectiveInput, audience)) return true;
        for (Anchor topic : anchors) {
            if (topic.role() != Role.RESEARCH_TOPIC || !topic.text().contains(audience)
                    || topic.end() > effectiveInput.length()
                    || !currentResearchSection(effectiveInput, topic.start())) continue;
            String before = effectiveInput.substring(0, topic.start());
            int boundary = Math.max(Math.max(before.lastIndexOf('。'), before.lastIndexOf('；')), before.lastIndexOf('\n'));
            String prefix = before.substring(boundary + 1);
            if (prefix.endsWith("研究") && !prefix.matches("(?s).*(?:不|未|如果|假如|若|以后|待确认|建议|示例|例如).*")) return true;
        }
        return false;
    }

    /** 仅本研究的完整肯定对象声明可支持角色复用，另一研究、待确认或推荐不成为支持。 */
    private boolean declaresCurrentPopulation(String source, String audience) {
        String subject = Pattern.quote(audience);
        String role = "(?:(?:本(?:次)?研究(?:的)?)?(?:研究对象|招募对象|受试者人群|参与者)|本(?:次)?研究对象)";
        String separator = "(?:[、，,]|和|及|与)";
        var lines = Pattern.compile("(?m)^.*$").matcher(source);
        while (lines.find()) {
            String line = lines.group();
            if (!currentResearchSection(source, lines.end())) continue;
            var heading = Pattern.compile("^\\s*([^，,。；;：:]{1,60})[：:](.*)$").matcher(line);
            if (heading.matches() && !heading.group(1).matches(role)) {
                line = heading.group(2);
            }
            for (String clause : line.split("[。；;]")) {
                if (clause.matches("(?s).*(?:尚未确定|未确定|待定|待确认|确认后|如果|假如|以后|未来|建议|可考虑|示例|例如|不是|并非|不采用|不招募|排除).*")) continue;
                if (clause.strip().matches(role + "(?:为|是|确定为|[：:])\\s*"
                        + "(?:[^，,、]{1,30}" + separator + ")*" + subject
                        + "(?:" + separator + "[^，,、]{1,30})*")) return true;
            }
        }
        return false;
    }

    /** 声明和主题共用来源段落范围；普通标题透明，非事实标记与明确另一研究标题不能被换行擦除。 */
    private boolean currentResearchSection(String source, int offset) {
        boolean current = true;
        boolean asserted = true;
        for (String line : source.substring(0, offset).split("\\R")) {
            var marker = Pattern.compile("^\\s*([^，,。；;：:]{1,60})[：:].*$").matcher(line);
            if (!marker.matches()) continue;
            String heading = marker.group(1);
            if (heading.matches("(?:示例|例如|假设|建议|可考虑|候选|如果|假如|若|以后|未来|待确认|尚未确定).*")) asserted = false;
            else if (heading.matches("本研究|本次研究|当前研究")) current = true;
            else if (heading.endsWith("研究")) current = false;
        }
        return current && asserted;
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
                    if (audience.role() != Role.AUDIENCE || !namesAudienceAsSubject(subject.group(1), audience.text())) continue;
                    boolean alsoExplicitTopic = anchors.stream().anyMatch(anchor -> anchor.role() == Role.RESEARCH_TOPIC
                            && anchor.text().contains(audience.text()));
                    if (!alsoExplicitTopic && !confirmedSubjects.contains(audience.text())) throw new ProviderResponseValidationException(
                            ProviderResponseValidationException.Reason.TASK_ROLE_CONFLICT, field);
                }
            }
        }
    }

    /** 只排除明确的独立写作受众子句；逗号列举的研究人群和后续对象仍参与校验。 */
    private boolean namesAudienceAsSubject(String subject, String audience) {
        String namedAudience = Pattern.quote(audience);
        // 逗号本身不是对象边界：“工程师，研究生”仍是研究对象列举。
        // 只有完整的受众谓词及其本次已知受众才豁免，避免“面向研究生开展招募”被误作写作说明。
        String audienceClause = "[，,]\\s*(?:(?:(?:本文|本报告|本文稿|本方案|本方法正文)(?:仅)?(?:面向|供)"
                + "|(?:本文的)?(?:写作受众|目标读者|读者)(?:为|是))\\s*" + namedAudience + "(?:读者|阅读)?"
                + "|(?:仅)?面向\\s*" + namedAudience + "(?:读者|阅读))\\s*(?=[，,]|$)";
        return subject.replaceAll(audienceClause, "").contains(audience);
    }

    /** 原文片段是依据，不是模型推理过程；角色类型防止同一词被转用于另一对象。 */
    public record Anchor(Role role, String text, int start, int end, Origin origin) { }
    /** 写作受众与研究主题是不同角色，不能从一个角色推定另一个。 */
    public enum Role { AUDIENCE, RESEARCH_TOPIC }
    /** 有效输入由原始需求与服务端绑定决定组成；位置针对 effectivePrompt，不冒充附件摘录。 */
    public enum Origin { EFFECTIVE_USER_INPUT }
}
