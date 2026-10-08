package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.PlanOption;
import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.PlanQuestionType;
import com.promptoptimizer.provider.domain.PlanningProviderRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
    private static final Pattern UNVERIFIED_STATE = Pattern.compile(
            "(?:尚未|仍未|并未|未|待)(?:批准|审批|决定|确定|采用|核验|建立|确认|生效)"
                    + "|(?:权威性|适用(?:范围|口径)|审批状态|批准状态|有效性|效力).{0,20}(?:未|待|应.{0,8}(?:核对|确认)|须.{0,8}(?:核对|确认))"
                    + "|(?:并非|不是|不代表).{0,8}(?:已批准|生效|适用)|仅[是作]?候选|仅作建议");
    private static final Pattern COMPARISON_ONLY = Pattern.compile(
            "(?:不得|不能|不要|不擅自|不应).{0,14}(?:采用|选择|选定).{0,12}(?:其中|任一|某一|一项)"
                    + "|(?:仅|只)(?:进行)?(?:展示|呈现|列出|比较)(?:两|各|不同|双方).{0,12}(?:口径|规则|方案|选择)");
    private static final Pattern QUANTITY = Pattern.compile("\\d+(?:\\.\\d+)?\\s*(?:小时|分钟|天|月|年|元|%|个)");
    private static final Set<String> GENERIC_QUESTION_PAIRS = Set.of(
            "如何", "应如", "何定", "定义", "应怎", "怎样", "选择", "方案", "采用", "使用", "哪个", "什么", "本次", "是否", "需要", "为准");

    private PlanRecommendationAligner() {
    }

    /** 当前目标优先于历史和项目材料；排除明确否定的技术，推荐只作待确认建议。 */
    static PlanQuestion align(PlanQuestion question, PlanningProviderRequest input) {
        if (question.type() == PlanQuestionType.FREE_TEXT || question.options().size() < 2) {
            return question;
        }
        String currentEvidence = input.rawPrompt();
        String userEvidence = userCorpus(input);
        String projectEvidence = projectCorpus(input);
        int bestScore = 0;
        int bestIndex = -1;
        boolean unique = true;
        List<String> reasons = new ArrayList<>();
        for (int index = 0; index < question.options().size(); index++) {
            PlanOption option = question.options().get(index);
            // 泛指题干不能消除候选自身的机构和年份；每个候选独立核对，另一院同值不构成依据。
            String candidateScope = question.question() + " " + option.answer() + " " + option.description();
            String currentCorpus = normalize(applicableCorpus(currentEvidence, candidateScope));
            String userCorpus = normalize(applicableCorpus(userEvidence, candidateScope));
            String projectCorpus = normalize(applicableCorpus(projectEvidence, candidateScope));
            String choice = choiceIdentity(option.label());
            String current = support(currentCorpus, choice, option, question.options(), false);
            String user = support(userCorpus, choice, option, question.options(), false);
            String project = support(projectCorpus, choice, option, question.options(), true);
            // 事实存在不等于本次已决定沿用。当前需求明确要求只比较双方时，历史及项目不能替它选胜者。
            boolean rejected = !choice.isBlank() && evidence(currentCorpus, choice) < 0
                    || unresolvedCurrentSelection(currentCorpus, question, option);
            // 明确偏好优先于同次材料中的普通技术提及；两个同级偏好仍不替用户选胜者。
            int score = rejected ? 0 : !current.isBlank() ? explicitPreference(currentCorpus, current) ? 20_000 : 10_000
                    : !user.isBlank() ? 100 : !project.isBlank() ? 1 : 0;
            String matched = score >= 10_000 ? current : score == 100 ? user : project;
            reasons.add(recommendationReason(score >= 10_000 ? "原始需求" : score == 100 ? "用户描述或历史偏好" : "项目证据",
                    score >= 10_000 ? currentCorpus : score == 100 ? userCorpus : projectCorpus, matched));
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

    /**
     * 推荐理由复用已核对范围的同句依据，偏好不写成决定，不从另一技术或机构借用取舍。
     * 只展示完整短句；长句不截掉否定或适用条件，无法简明引用时只说明已有匹配依据。
     */
    private static String recommendationReason(String source, String corpus, String matched) {
        if (matched.isBlank()) return "";
        boolean preference = explicitPreference(corpus, matched);
        String evidenceSentence = java.util.Arrays.stream(corpus.split("[。；;!?\\n]+"))
                .map(String::strip).filter(sentence -> !sentence.isEmpty() && sentence.length() <= 120)
                .filter(sentence -> evidence(sentence, matched) > 0)
                .filter(sentence -> !preference || explicitPreference(sentence, matched))
                .findFirst().orElse("");
        String basis = evidenceSentence.isEmpty()
                ? (matched.length() <= 80 ? "匹配依据为“" + matched + "”" : "存在与该选项相符的完整依据")
                : "依据摘要：“" + evidenceSentence + "”";
        return source + (preference ? "表达了偏好；" : "提供了适用依据；") + basis
                + "。此为建议，仍需确认本次选择及适用范围。";
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
        input.planningContext().fileSummaries().stream()
                .filter(value -> value != null && !value.matches("(?s)^\\[(?:TEST_SOURCE|TEST_FIXTURE|EXAMPLE_MATERIAL|GENERATED_REPORT)](?:\\s|$).*"))
                .forEach(value -> append(corpus, value));
        input.planningContext().factCards().forEach(card -> {
            // 路径仅用于溯源；目录里出现技术名不是项目采用该技术的证据。
            // 测试、演示和生成报告不能替当前业务推荐背书；实际源码和用户材料仍须经过句内适用性核对。
            if (card != null && card.origin() != null && switch (card.origin()) {
                case PROJECT_SOURCE, PROJECT_DOCUMENT, USER_MATERIAL -> true;
                default -> false;
            }) append(corpus, card.evidence());
        });
        return corpus.toString();
    }

    /** 保留选项的完整含义，不能从“基于 Plan 确认”或“API 调用量”中只截出英文词。 */
    private static String choiceIdentity(String label) {
        return normalize(label).replaceFirst("^(?:本次|继续|严格|优先)*(?:采用|用|选择|沿用)?\\s*", "");
    }

    /** 不拆成共同词；完整实践短句可支撑“混合使用”等概括选项，但不能替另一技术名称背书。 */
    private static String support(String corpus, String choice, PlanOption option, List<PlanOption> alternatives, boolean project) {
        // “缺姓名时不查询”不能替“地区为空时不查询”背书；短动作必须连同对象和条件完整匹配。
        if (ACTION_LABEL.matcher(choice).matches()) {
            String claim = normalize(option.answer());
            return claim.length() >= 8 && evidence(corpus, claim) > 0 ? claim : "";
        }
        // 通知渠道的互斥候选允许沿用明确偏好作建议；不能把这种例外扩展到统计分母的“仅/全部”。
        if (!project && choice.matches("^仅(?:启用)?(?:站内信|邮件)$")) {
            String channel = choice.replaceFirst("^仅(?:启用)?", "");
            if (explicitPreference(corpus, channel) && evidence(corpus, channel) > 0) return channel;
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
        // 待翻译的英文术语只是任务对象，不是保留英文的偏好；仍保留技术选型与明确指定术语的依据。
        boolean bareNamedAnswer = TECHNOLOGY_LABEL.matcher(answer).matches()
                && !TECHNOLOGY_LABEL.matcher(choice).matches()
                && !explicitPreference(corpus, choice) && !explicitPreference(corpus, answer);
        if (!bareNamedAnswer && answer.length() >= 8 && evidence(corpus, answer) > 0) return answer;
        if (TECHNOLOGY_LABEL.matcher(option.label()).find()) return "";
        for (String clause : option.description().split("[，,。；;]")) {
            String claim = normalize(clause);
            // “再生成最终提示词”等共同交付步骤无法区分候选，不能给某一种组织方式背书。
            boolean shared = alternatives.stream().filter(other -> !other.id().equals(option.id()))
                    .anyMatch(other -> normalize(other.label() + " " + other.description() + " " + other.answer()).contains(claim));
            if (!shared && claim.length() >= 8 && evidence(corpus, claim) > 0
                    && (project || explicitPreference(corpus, claim))) return claim;
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
        // 换行是不同资料/用户陈述的边界；消除它会把另一份材料的审批状态或选择误接到当前证据上。
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^\\S\\n]+", " ").trim()
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
        // 完整答案可能连同句号命中。句号属于证据句的边界，不能让下一句审批说明被吞进本句。
        int evidenceEnd = end;
        while (evidenceEnd > start && "。；;!?".indexOf(corpus.charAt(evidenceEnd - 1)) >= 0) evidenceEnd--;
        int left = start;
        while (left > 0 && "。；;!?\n".indexOf(corpus.charAt(left - 1)) < 0) left--;
        int right = evidenceEnd;
        while (right < corpus.length() && "。；;!?\n".indexOf(corpus.charAt(right)) < 0) right++;
        String before = corpus.substring(left, start);
        String after = corpus.substring(evidenceEnd, right);
        String sentence = corpus.substring(left, right).strip();
        if (sentence.matches("[^：:。\\n]*[/\\\\][^：:。\\n]*\\.(?:md|txt|java|vue|tsx?|jsx?|pdf)(?:#chunk-\\d+)?")) return true;
        if (before.matches("(?s).*(?:若|如果|假如|假设|倘若|仅在|未来|将来|可考虑|可选|候选|备选|拟采用|建议采用|计划采用)[^。；;\\n]*")) return true;
        if (after.matches("(?s)^[^，,。；;\\n]{0,30}(?:尚未采用|尚未决定|尚未批准|未选定|待批准|待决定|仅作建议).*")) return true;
        // 否定审批或候选定位作用于匹配短句本身，不要求它刚好出现在标签后30字内。
        if (after.matches("(?s)^[^，,]{0,16}(?:并非|不是|不代表).{0,8}(?:已批准|生效|适用).*")) return true;
        if (after.matches("(?s).*?(?:但|而)?(?:这|该|此)(?:仅|只是|是)?(?:.{0,12}候选|.{0,12}草稿).*")) return true;

        // 同一段的下一句若明确指代前面的规则，审批缺口仍约束它；另一业务对象的未批准不能连带否决。
        int paragraphLeft = corpus.lastIndexOf('\n', start) + 1;
        int paragraphRight = corpus.indexOf('\n', evidenceEnd);
        if (paragraphRight < 0) paragraphRight = corpus.length();
        String paragraph = corpus.substring(paragraphLeft, paragraphRight);
        int followingStart = Math.min(right + 1, paragraphRight);
        String following = corpus.substring(followingStart, paragraphRight).strip();
        if (following.matches("(?s)^(?:该|此|上述|本|这项).{0,16}(?:规则|方案|口径|材料).*")
                && (following.matches("(?s)^(?:该|此|上述|本|这项)(?:规则|方案|口径|材料).*")
                || sharesQuestionObject(following, corpus.substring(start, evidenceEnd)))
                && UNVERIFIED_STATE.matcher(following.split("[。；;!?]", 2)[0]).find()) return true;
        // “两份材料的适用/审批须核对”明确约束整段双方；只看到旧规则短句不能恢复其权威性。
        Matcher sharedState = Pattern.compile("(?:两份|双方|这些|各份|全部)(?:资料|材料|规则|口径|方案)"
                + "[^。；;!?]*?(?:权威性|适用口径|适用范围|审批状态|批准状态)[^。；;!?]*").matcher(paragraph);
        while (sharedState.find()) if (UNVERIFIED_STATE.matcher(sharedState.group()).find()) return true;
        return false;
    }

    /**
     * 将当前“只比较、不选口径”的范围绑定到候选对象；不能因某个订单规则未决而取消退款或技术选型的推荐。
     * 明确当前采用某个完整行为时仍按当前目标优先，不让历史草稿阻止有效选择。
     */
    private static boolean unresolvedCurrentSelection(String corpus, PlanQuestion question, PlanOption option) {
        String choice = choiceIdentity(option.label());
        if (explicitPreference(corpus, choice)) return false;
        for (String clause : option.description().split("[，,。；;：:]")) {
            String claim = normalize(clause);
            if (claim.length() >= 8 && explicitPreference(corpus, claim)) return false;
        }
        for (String paragraph : corpus.split("\\n\\s*\\n")) {
            if (!COMPARISON_ONLY.matcher(paragraph).find() || !mentionsOption(paragraph, question, option)) continue;
            long distinctChoices = question.options().stream()
                    .filter(candidate -> mentionsOption(paragraph, question, candidate)).count();
            if (distinctChoices >= 2) return true;
        }
        return false;
    }

    /** 完整实践或具名技术优先；数值只能连同问题的业务对象匹配，不把其他字段恰好同值视为同一选择。 */
    private static boolean mentionsOption(String paragraph, PlanQuestion question, PlanOption option) {
        String choice = choiceIdentity(option.label());
        if (choice.length() >= 3 && paragraph.contains(choice)) return true;
        String details = normalize(option.answer() + "。" + option.description());
        for (String clause : details.split("[，,。；;：:]")) {
            if (clause.length() >= 8 && paragraph.contains(clause)) return true;
        }
        if (!sharesQuestionObject(paragraph, question.question())) return false;
        Matcher quantity = QUANTITY.matcher(details);
        while (quantity.find()) if (paragraph.contains(quantity.group())) return true;
        return false;
    }

    /** 数值关联需要业务名词，剔除“如何/采用/方案”等问句框架，避免把同数字的不同决定合并。 */
    private static boolean sharesQuestionObject(String paragraph, String question) {
        Matcher words = Pattern.compile("[\\p{IsHan}]{2,}").matcher(normalize(question));
        while (words.find()) {
            String word = words.group();
            for (int index = 0; index < word.length() - 1; index++) {
                String pair = word.substring(index, index + 2);
                if (!GENERIC_QUESTION_PAIRS.contains(pair) && paragraph.contains(pair)) return true;
            }
        }
        return false;
    }

    private static void append(StringBuilder corpus, String value) {
        if (value != null && !value.isBlank()) {
            corpus.append('\n').append(value);
        }
    }

    /**
     * 具名机构或年份的推荐只能使用相同范围的证据，不能把另一院相同窗口当作默认答案。
     * 无具名范围的原有技术选型继续走原链路；紧随证据的审批说明保留，不因切句抹掉否定。
     */
    private static String applicableCorpus(String corpus, String question) {
        Pattern owner = Pattern.compile("(?:[A-Za-z][A-Za-z0-9_]*|[\\p{IsHan}]{1,12})(?:医院|公司|机构|部门|工作区|租户)|[甲乙丙丁]院");
        List<String> scopes = owner.matcher(question).results().map(match -> match.group()
                .replaceFirst("^(?:对于|关于|请问|请|为)", "")).distinct().toList();
        List<String> years = Pattern.compile("(?:19|20)\\d{2}年").matcher(question).results().map(java.util.regex.MatchResult::group).toList();
        if (scopes.isEmpty() && years.isEmpty()) return corpus;
        StringBuilder eligible = new StringBuilder();
        // 同段的证据及指代限定保持在同段，不能人为插入换行把未审批说明变成另一份事实。
        // 原有换行仍是来源边界，另一段的未知审批状态不得连带取消当前推荐。
        for (String paragraph : corpus.split("\\n", -1)) {
            StringBuilder scopedParagraph = new StringBuilder();
            boolean precedingMatches = false;
            for (String sentence : paragraph.split("(?<=[。；;!?])")) {
                String text = sentence.strip();
                boolean same = scopes.stream().allMatch(text::contains) && years.stream().allMatch(text::contains);
                boolean dependent = precedingMatches && text.matches("^(?:该|此|上述|这项|本规则|本方案).*");
                if (same || dependent) scopedParagraph.append(sentence);
                precedingMatches = same || dependent;
            }
            append(eligible, scopedParagraph.toString());
        }
        return eligible.toString();
    }
}
