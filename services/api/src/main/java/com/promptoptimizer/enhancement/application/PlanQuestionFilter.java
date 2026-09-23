package com.promptoptimizer.enhancement.application;

import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.provider.domain.PlanningProviderRequest;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 保守过滤重复文本及有明确字段证据的事实问题；无法确定的业务选择仍保留。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class PlanQuestionFilter {
    private record FactRule(Pattern question, Pattern fact) { }
    private static final List<FactRule> RULES = List.of(
            rule("(?:研究|分析).*(?:地区|区域)|(?:地区|区域).*(?:范围|哪里)", "(?:研究地区|研究范围|地区范围)\\s*[:：=]\\s*([^\\n。；;]{2,80})"),
            rule("(?:分析|编程).*(?:工具|语言)", "(?:分析工具|编程语言)\\s*[:：=]\\s*([^\\n。；;]{1,80})"),
            rule("(?:目标|面向).*(?:读者|受众|学生)|(?:读者|受众).*(?:谁|哪些)", "(?:目标读者|目标受众|面向学生)\\s*[:：=]\\s*([^\\n。；;]{2,80})"),
            rule("(?:输出|交付).*(?:格式)", "(?:输出格式|交付格式)\\s*[:：=]\\s*([^\\n。；;]{2,80})"),
            rule("(?:适用|涉及).*(?:法域|司法辖区)|(?:法域|司法辖区).*(?:什么|哪个)", "(?:适用法域|司法辖区)\\s*[:：=]\\s*([^\\n。；;]{2,80})")
    );

    /** 根据用户原始需求、对话和安全上下文去重；仅移除已被明确事实回答的问题。 */
    public List<PlanQuestion> filter(List<PlanQuestion> questions, PlanningProviderRequest input) {
        StringBuilder evidence = new StringBuilder(input.rawPrompt()).append('\n').append(input.contextDescription());
        input.conversationHistory().stream().filter(message -> "user".equals(message.role()))
                .forEach(message -> evidence.append('\n').append(message.content()));
        if (input.planningContext() != null) {
            evidence.append('\n').append(input.planningContext().description());
            input.planningContext().fileSummaries().forEach(value -> evidence.append('\n').append(value));
        }
        Set<String> seen = new HashSet<>();
        return questions.stream().filter(question -> seen.add(normalize(question.question())))
                .filter(question -> !resolved(question.question(), evidence.toString())).toList();
    }

    /** 混合问题与冲突选择必须留给用户确认，单一明确字段才能判定为已解决。 */
    private boolean resolved(String question, String evidence) {
        // 混合问题、变更要求与确认冲突不能因命中某个已知字段而整题删除。
        if (question.matches(".*(以及|和|与|是否|更换|调整|迁移|冲突|还是).*")) return false;
        for (FactRule rule : RULES) {
            if (!rule.question().matcher(question).find()) continue;
            var matches = rule.fact().matcher(evidence);
            Set<String> values = new HashSet<>();
            boolean uncertain = false;
            while (matches.find()) {
                String value = matches.group(1).trim();
                if (value.matches(".*(未知|待定|未明确|可能|建议|例如|某地区|某省|某市|[？?]).*")) uncertain = true;
                else values.add(normalize(value));
            }
            if (!uncertain && values.size() == 1) return true;
        }
        return false;
    }

    private static FactRule rule(String question, String fact) {
        return new FactRule(Pattern.compile(question), Pattern.compile(fact));
    }

    private String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{P}\\s]+", "");
    }
}
