package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.PlanOption;
import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.PlanQuestionType;
import com.promptoptimizer.provider.domain.PlanningProviderRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 在不增删候选项的前提下，把「建议」对准当前需求、已上传材料和用户已说过的话。
 * 只有完整选项或具体实践有证据时才展示推荐；模型自报的推荐也须通过相同核对。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class PlanRecommendationAligner {

    private static final Pattern TECHNOLOGY_LABEL = Pattern.compile("[a-zA-Z][a-zA-Z0-9.+-]*(?:[ /-]+[a-zA-Z0-9][a-zA-Z0-9.+-]*)*");
    private static final Pattern ACTION_LABEL = Pattern.compile(
            "^(?:不|仅|只|先|允许|禁止|继续|保持|保留|直接|自动)?(?:查询|填充|弹窗|提示|录入|覆盖|匹配)(?:也不提示|并提示)?$");

    private PlanRecommendationAligner() {
    }

    /** 当前目标优先于历史和项目材料；排除明确否定的技术，推荐只作待确认建议。 */
    static PlanQuestion align(PlanQuestion question, PlanningProviderRequest input) {
        if (question.type() == PlanQuestionType.FREE_TEXT || question.options().size() < 2) {
            return question;
        }
        String currentCorpus = normalize(input.rawPrompt());
        String userCorpus = normalize(userCorpus(input));
        String projectCorpus = normalize(projectCorpus(input));
        int bestScore = 0;
        int bestIndex = -1;
        boolean unique = true;
        List<String> reasons = new ArrayList<>();
        for (int index = 0; index < question.options().size(); index++) {
            PlanOption option = question.options().get(index);
            String choice = choiceIdentity(option.label());
            String current = support(currentCorpus, choice, option, question.options(), false);
            String user = support(userCorpus, choice, option, question.options(), false);
            String project = support(projectCorpus, choice, option, question.options(), true);
            boolean rejected = !choice.isBlank() && evidence(currentCorpus, choice) < 0;
            int score = rejected ? 0 : !current.isBlank() ? 10_000 : !user.isBlank() ? 100 : !project.isBlank() ? 1 : 0;
            String matched = score == 10_000 ? current : score == 100 ? user : project;
            reasons.add((score == 10_000 ? "原始需求" : score == 100 ? "用户描述或历史偏好" : "项目证据")
                    + "明确包含：" + matched + "。请核对适用范围后选择。");
            if (score > bestScore) {
                bestScore = score;
                bestIndex = index;
                unique = true;
            } else if (score == bestScore && score > 0) {
                unique = false;
            }
        }
        boolean supported = unique && bestIndex >= 0 && bestScore > 0;
        List<PlanOption> aligned = new ArrayList<>();
        for (int index = 0; index < question.options().size(); index++) {
            PlanOption option = question.options().get(index);
            boolean recommended = supported && index == bestIndex;
            aligned.add(new PlanOption(
                    option.id(),
                    option.label(),
                    option.description(),
                    option.answer(),
                    recommended,
                    recommended ? reasons.get(index) : ""
            ));
        }
        return new PlanQuestion(
                question.id(),
                question.question(),
                question.hint(),
                question.type(),
                aligned,
                question.examples(),
                question.allowCustomAnswer()
        );
    }

    private static String userCorpus(PlanningProviderRequest input) {
        StringBuilder corpus = new StringBuilder();
        append(corpus, input.contextDescription());
        input.conversationHistory().stream()
                .filter(message -> "user".equals(message.role()))
                .forEach(message -> append(corpus, message.content()));
        return corpus.toString();
    }

    private static String projectCorpus(PlanningProviderRequest input) {
        StringBuilder corpus = new StringBuilder();
        if (input.planningContext() == null) {
            return "";
        }
        input.planningContext().technologies().forEach(value -> append(corpus, value));
        input.planningContext().dependencies().forEach(value -> append(corpus, value));
        input.planningContext().fileSummaries().forEach(value -> append(corpus, value));
        input.planningContext().factCards().forEach(card -> {
            // 路径仅用于溯源；目录里出现技术名不是项目采用该技术的证据。
            append(corpus, card.evidence());
        });
        return corpus.toString();
    }

    /** 保留选项的完整含义，不能从“基于 Plan 确认”或“API 调用量”中只截出英文词。 */
    private static String choiceIdentity(String label) {
        return normalize(label).replaceFirst("^(?:本次|继续|严格|优先)*(?:采用|用|选择)?\\s*", "");
    }

    /** 不拆成共同词；完整实践短句可支撑“混合使用”等概括选项，但不能替另一技术名称背书。 */
    private static String support(String corpus, String choice, PlanOption option, List<PlanOption> alternatives, boolean project) {
        // “缺姓名时不查询”不能替“地区为空时不查询”背书；短动作必须连同对象和条件完整匹配。
        if (ACTION_LABEL.matcher(choice).matches()) {
            String claim = normalize(option.answer());
            return claim.length() >= 8 && evidence(corpus, claim) > 0 ? claim : "";
        }
        if (option.label().matches(".*(仅|统一|全部|所有|只用).*")
                && evidence(corpus, normalize(option.label())) <= 0) return "";
        // 中文名词可能只是数据或候选对象：提到“预约事件”不等于已选择它作主统计单位。
        // 完整技术名、具名行为断言或明确选择才能凭标签获得推荐，否则核对完整答案。
        if (choice.length() >= 3 && evidence(corpus, choice) > 0
                && (TECHNOLOGY_LABEL.matcher(choice).matches()
                || choice.matches(".*(?:不查询|不覆盖|保留|保持|须|必须|禁止|采用|使用|统一用|仅用|只用|^用).+")
                || explicitPreference(corpus, choice))) return choice;
        String answer = normalize(option.answer());
        if (answer.length() >= 8 && evidence(corpus, answer) > 0) return answer;
        if (!project || TECHNOLOGY_LABEL.matcher(option.label()).find()) return "";
        for (String clause : option.description().split("[，,。；;]")) {
            String claim = normalize(clause);
            // “再生成最终提示词”等共同交付步骤无法区分候选，不能给某一种组织方式背书。
            boolean shared = alternatives.stream().filter(other -> !other.id().equals(option.id()))
                    .anyMatch(other -> normalize(other.label() + " " + other.description() + " " + other.answer()).contains(claim));
            if (!shared && claim.length() >= 8 && evidence(corpus, claim) > 0) return claim;
        }
        return "";
    }

    /** 只认紧邻候选的明确选择谓词；列举、比较或“资料有该对象”不构成偏好。 */
    private static boolean explicitPreference(String corpus, String choice) {
        var match = Pattern.compile("(?:本次(?:明确)?(?:选择|采用|用)|明确(?:选择|采用|用)|决定(?:选择|采用|用)|"
                + "指定|偏好|优先选择)\\s*" + Pattern.quote(choice)).matcher(corpus);
        while (match.find()) if (!unselectedEvidence(corpus, match.start(), match.end())) return true;
        return false;
    }

    /** 同义连接词可归一化，但保留比较边界、否定词和完整技术名。 */
    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim()
                .replace("使用", "用").replace("大于等于", "≥").replace("不少于", "≥")
                .replace("大于", ">").replace("超过", ">").replace("小于等于", "≤").replace("小于", "<");
    }

    /** 只使用完整技术词或中文短语；明确的否定和迁出来源不构成正向推荐依据。 */
    private static int evidence(String corpus, String token) {
        boolean latin = token.chars().allMatch(character -> character < 128);
        Pattern occurrence = Pattern.compile((latin ? "(?<![a-z0-9+_.-])" : "") + Pattern.quote(token)
                + (latin ? "(?![a-z0-9+_.-])" : ""));
        Matcher matcher = occurrence.matcher(corpus);
        boolean found = false;
        while (matcher.find()) {
            String prefix = corpus.substring(Math.max(0, matcher.start() - 24), matcher.start());
            String suffix = corpus.substring(matcher.end(), Math.min(corpus.length(), matcher.end() + 24));
            if (prefix.matches("(?s).*(?:不要|不再|不能|不用|不使用|不采用|不引入|尚未|无需|排除|避免|禁止|without|avoid|not)[^，。；;.!?\\n]{0,12}")
                    || prefix.matches("(?s).*从\\s*") && suffix.matches("(?s)^.{0,6}(?:迁移到|切换到|改为|替换为).*")
                    || suffix.matches("(?s)^[\\s]*(?:仅是|只是|仅为|是)?(?:迁出来源|被替换的|旧版框架).*")) {
                return -1;
            }
            if (unselectedEvidence(corpus, matcher.start(), matcher.end())) continue;
            found = true;
        }
        return found ? 1 : 0;
    }

    /**
     * 未选分支、将来建议与纯路径不能为当前推荐背书；取完整句界而非固定24字窗口。
     * 条件业务规则只有其完整条件包含在匹配证据里时才可支持，不能只匹配条件中的技术名。
     */
    private static boolean unselectedEvidence(String corpus, int start, int end) {
        int left = start;
        while (left > 0 && "。；;!?\n".indexOf(corpus.charAt(left - 1)) < 0) left--;
        int right = end;
        while (right < corpus.length() && "。；;!?\n".indexOf(corpus.charAt(right)) < 0) right++;
        String before = corpus.substring(left, start);
        String after = corpus.substring(end, right);
        String sentence = corpus.substring(left, right).strip();
        if (sentence.matches("[^：:。\\n]*[/\\\\][^：:。\\n]*\\.(?:md|txt|java|vue|tsx?|jsx?|pdf)(?:#chunk-\\d+)?")) return true;
        if (before.matches("(?s).*(?:若|如果|假如|假设|倘若|仅在|未来|将来|可考虑|可选|候选|备选|拟采用|建议采用|计划采用)[^。；;\\n]*")) return true;
        return after.matches("(?s)^[^，,。；;\\n]{0,30}(?:尚未采用|尚未决定|尚未批准|未选定|待批准|待决定|仅作建议).*" );
    }

    private static void append(StringBuilder corpus, String value) {
        if (value != null && !value.isBlank()) {
            corpus.append('\n').append(value);
        }
    }
}
