package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.PlanningFactCard;
import com.promptoptimizer.enhancement.domain.PlanningFactCategory;
import com.promptoptimizer.enhancement.domain.PlanningFactOrigin;
import com.promptoptimizer.provider.domain.PlanningProviderRequest;
import com.promptoptimizer.template.domain.TaskDeliveryProfile;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 保守过滤已由明确证据回答或重复的问题；不确定和冲突问题交由用户确认。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class PlanQuestionFilter {
    private record FactRule(PlanningFactCategory category, Pattern question, Pattern label) { }
    private record KnownFact(PlanningFactCategory category, String value, String source, String scope) { }
    private record QuestionKey(String subject, List<String> details) { }
    private static final Pattern INDEPENDENT_PRESENTATION_DECISION = Pattern.compile(
            "新增|另需|另外|还需|是否|待定|尚未|未确定|待确认|冲突|阈值|权限|隐私|保密|重试|超时|[<>!=]|\\d");
    private static final Pattern ADDITIONAL_DECISION = Pattern.compile("另需|另外|此外|还需(?:确认|决定|选择)|同时还");

    private static final List<FactRule> RULES = List.of(
            rule(PlanningFactCategory.REGION,
                    "(?:研究|分析|覆盖|目标).*(?:地区|区域)|(?:地区|区域).*(?:范围|哪里|哪个|什么)",
                    "(?:研究地区|研究范围|地区范围|覆盖地区|地区)\\s*[:：=]\\s*([^\\n。；;]{2,80})"),
            rule(PlanningFactCategory.AUDIENCE,
                    "(?:目标|面向).*(?:读者|受众|学生)|(?:读者|受众).*(?:谁|哪些)",
                    "(?:目标读者|目标受众|面向学生)\\s*[:：=]\\s*([^\\n。；;]{2,80})"),
            rule(PlanningFactCategory.JURISDICTION,
                    "(?:适用|涉及).*(?:法域|司法辖区)|(?:法域|司法辖区).*(?:什么|哪个)",
                    "(?:适用法域|司法辖区)\\s*[:：=]\\s*([^\\n。；;]{2,80})"),
            rule(PlanningFactCategory.DATA_SOURCE,
                    "(?:数据|资料).*(?:来源|来自|取自)|(?:来源|来自).*(?:数据|资料)",
                    "(?:数据来源|资料来源|来源)\\s*[:：=]\\s*([^\\n。；;]{2,100})"),
            rule(PlanningFactCategory.DATA_FORMAT,
                    "(?:数据|文件|材料).*(?:格式|类型)|(?:格式|类型).*(?:数据|文件|材料)",
                    "(?:数据格式|文件格式|材料格式)\\s*[:：=]\\s*([^\\n。；;]{2,80})"),
            rule(PlanningFactCategory.DISEASE_CATEGORY,
                    "(?:疾病|病种).*(?:亚类|分类|定义|口径)|(?:亚类|病种分类).*(?:如何|什么|哪些)",
                    "(?:疾病亚类|病种分类|疾病分类|亚类定义)\\s*[:：=]\\s*([^\\n。；;]{2,100})"),
            rule(PlanningFactCategory.POPULATION,
                    "(?:人群|年龄|城乡|性别|患者).*(?:划分|分组|标准|范围|如何|哪些)",
                    "(?:人群划分|年龄组|城乡划分|性别分组|研究对象|目标人群)\\s*[:：=]\\s*([^\\n。；;]{2,100})"),
            rule(PlanningFactCategory.ANALYSIS_TOOL,
                    "(?:分析|统计|编程).*(?:工具|软件|语言)|(?:工具|软件|语言).*(?:使用|采用|偏好|什么)",
                    "(?:分析工具|统计软件|编程语言|工具偏好)\\s*[:：=]\\s*([^\\n。；;]{1,80})"),
            rule(PlanningFactCategory.ANALYSIS_METHOD,
                    "(?:分析|研究|分解).*(?:方法|模型)|(?:方法|模型).*(?:采用|使用|什么)",
                    "(?:分析方法|研究方法|分解方法|方法学|模型)\\s*[:：=]\\s*([^\\n。；;]{2,100})"),
            rule(PlanningFactCategory.OUTPUT_FORMAT,
                    "(?:输出|交付).*(?:格式|形式|内容)|(?:格式|形式).*(?:输出|交付)",
                    "(?:输出格式|交付格式|输出内容)\\s*[:：=]\\s*([^\\n。；;]{2,100})"),
            rule(PlanningFactCategory.ACCEPTANCE_CRITERIA,
                    "(?:验收|成功|评价|判断).*(?:标准|条件|指标)|(?:标准|条件).*(?:验收|成功)",
                    "(?:验收标准|成功标准|评价标准|判断标准)\\s*[:：=]\\s*([^\\n。；;]{2,120})"),
            rule(PlanningFactCategory.TIME_RANGE,
                    "(?:研究|分析|统计|观察).*(?:时间范围|期间|年份)|(?:时间范围|研究期间|分析期间).*(?:什么|哪段|多久)",
                    "(?:时间范围|研究期间|分析期间|起止时间|时间段)\\s*[:：=]\\s*([^\\n。；;]{2,100})"),
            rule(PlanningFactCategory.BUSINESS_RULE,
                    "(?:业务|审批|退款|取消|计算|收费|资格|规则|阈值|条件).*(?:规则|如何|怎样|什么|多少|标准|条件|阈值)",
                    "(?:业务规则|审批规则|退款规则|取消规则|规则|阈值|审批条件)\\s*[:：=]\\s*([^\\n。；;]{2,180})")
    );

    /** 去掉完全重复、已明确事实及同一对象的简单同义问法；分类相同不能代替对象相同。 */
    public List<PlanQuestion> filter(List<PlanQuestion> questions, PlanningProviderRequest input) {
        List<KnownFact> facts = collectFacts(input);
        var decisionPolicy = PlanningDecisionPolicy.from(input);
        Set<QuestionKey> seen = new HashSet<>();
        Set<QuestionKey> seenDimensions = new HashSet<>();
        return questions.stream()
                .filter(question -> seen.add(new QuestionKey(normalize(question.question()), questionDetails(question))))
                .filter(question -> !clearlyOutsideCurrentTask(question.question(), input.rawPrompt()))
                .filter(question -> !routinePresentation(question, input.rawPrompt()))
                .filter(question -> !routineExecutionPresentation(question, input.rawPrompt()))
                .filter(question -> !KnownTestCoverage.repeatsKnownBranches(question, input))
                .filter(question -> hasAdditionalDecision(question) || !decisionPolicy.resolvedOrDelegated(question))
                .filter(question -> hasAdditionalDecision(question) || !resolved(question.question(), facts, input))
                .filter(question -> {
                    String dimension = questionDimension(question.question());
                    return dimension == null || seenDimensions.add(new QuestionKey(dimension, questionDetails(question)));
                }).toList();
    }

    /** 模型 ID 不参与身份；说明、选项和示例里的新选择参与比较，不能只凭相同题干删整题。 */
    private List<String> questionDetails(PlanQuestion question) {
        List<String> details = new ArrayList<>();
        details.add(question.type().name());
        details.add(Boolean.toString(question.allowCustomAnswer()));
        details.add(normalize(safe(question.hint())));
        question.options().forEach(option -> {
            details.add(normalize(safe(option.label())));
            details.add(normalize(safe(option.description())));
            details.add(normalize(safe(option.answer())));
            details.add(normalize(safe(option.recommendationReason())));
        });
        question.examples().forEach(example -> details.add(normalize(example)));
        return List.copyOf(details);
    }

    /** 额外说明显式引入另一选择时保留；“新增/修改代码”“不新增业务”等交付描述不能绕过已有决定校验。 */
    private boolean hasAdditionalDecision(PlanQuestion question) {
        return questionDetails(question).stream().anyMatch(detail -> ADDITIONAL_DECISION.matcher(detail).find());
    }

    /** 常规章节组织交给执行者；用户主动要求确认结构、期刊规范或专业方法时仍保留问题。 */
    private boolean routinePresentation(PlanQuestion candidate, String rawPrompt) {
        if (explicitlyDelegatedFactOrganization(candidate, safe(rawPrompt))) return true;
        if (TaskDeliveryProfile.identify(rawPrompt) == TaskDeliveryProfile.GENERAL
                || safe(rawPrompt).matches("(?s).*(?:询问|确认|让我选择|由我选择).{0,16}(?:章节|结构|顺序|提纲).*")) return false;
        // 章节题也可能携带样本、法域等真实选择；只委派没有新决策元信息的纯排版问题。
        if (questionDetails(candidate).stream().anyMatch(detail -> INDEPENDENT_PRESENTATION_DECISION.matcher(detail).find())) return false;
        String question = candidate.question();
        return question.matches("^(?:论文|方法提纲|报告|新闻稿|教案|指南)?(?:的)?(?:章节|小节|提纲)(?:应|应该|需要)?如何(?:组织|排序|安排)[？?]$")
                || question.matches("^(?:方法提纲|报告|新闻稿|教案|指南)的(?:章节|小节)(?:应|应该|需要)?如何(?:组织|排序|安排)[？?]$");
    }

    /**
     * 用户已委托的事实清单排版无需再选；“冲突材料/未决选择”是已给定的栏目名称。
     * 只接受这三栏的纯组织选项，新指标、专业标准、另一对象或额外决定继续保留。
     */
    private boolean explicitlyDelegatedFactOrganization(PlanQuestion candidate, String raw) {
        if (!raw.matches("(?s).*常规章节组织[^。]*由执行者处理[^。]*无需让我决定.*")
                || !candidate.question().matches("^你希望最终提示词在交付物中如何组织“已知事实、冲突材料与未决选择”这三部分[？?]$")) return false;
        if (!raw.contains("已知事实") || !raw.contains("冲突材料") || !raw.contains("未决选择")
                || candidate.options().isEmpty()) return false;
        if (questionDetails(candidate).stream().map(detail -> detail.replace("冲突材料", "").replace("未决选择", ""))
                .anyMatch(detail -> INDEPENDENT_PRESENTATION_DECISION.matcher(detail).find())) return false;
        return candidate.options().stream().allMatch(option -> {
            String answer = option.answer().replaceAll("[\\s，。、]", "")
                    .replace("已知事实", "").replace("冲突材料", "").replace("未决选择", "").replace("与", "");
            if (answer.equals("分别独立成节列出") || answer.equals("将合并为一张表用类型列区分")) return true;
            var byObject = Pattern.compile("^按([^。]{2,40})分别列出各自的已知事实、冲突材料与未决选择[。]?$")
                    .matcher(option.answer());
            return byObject.matches() && java.util.Arrays.stream(byObject.group(1).split("[、，,与和]"))
                    .map(String::strip).allMatch(scope -> scope.length() >= 2 && raw.contains(scope));
        });
    }

    /**
     * 仅委托已要求的关键伪代码粒度和原文已命名的确认框形态。
     * 用户主动要求作出这类选择、确认粒度或候选新增业务参数时保留整题，不代替业务决定。
     */
    private boolean routineExecutionPresentation(PlanQuestion question, String rawPrompt) {
        String raw = safe(rawPrompt);
        if (!raw.matches("(?s).*(?:实现方案|开发|代码|表单).*")
                || raw.matches("(?s).*(?:询问|确认|让我选择|由我选择|由用户选择).{0,16}(?:伪代码粒度|伪代码深度|伪代码详细|确认形式|交互形式).*")) return false;
        String text = question.question();
        Pattern independent = Pattern.compile("新增|跨租户|权限|隐私|重试|超时|阈值|毫秒|数据来源|字段映射|生产|迁移|运行|具体参数值|\\d+");
        if (independent.matcher(text).find() || questionDetails(question).stream()
                .anyMatch(detail -> independent.matcher(detail).find())) return false;
        if (raw.contains("关键伪代码") && text.matches("^伪代码(?:需要|应|应该)?(?:详细到什么程度|粒度如何确定|采用什么粒度)[？?]$")
                && !question.options().isEmpty()) {
            return question.options().stream().allMatch(option ->
                    option.label().matches("(?:流程级|方法级)(?:伪代码)?")
                    && (option.answer().contains("流程级") || option.answer().contains("服务方法")));
        }
        return raw.matches("(?s).*(?:确认框|确认弹窗|弹窗确认).*" )
                && !raw.matches("(?s).*(?:确认形式|确认形态|确认方式).{0,12}(?:尚未|未明确|未确定|待定|冲突).*" )
                && text.matches("^(?:有匹配候选时[，,])?确认交互(?:应|需要)?采用哪种形式[？?]$");
    }

    /** 只拦截与用户主要目标明显冲突的研究提问；其它相关性判断保持保守。 */
    private boolean clearlyOutsideCurrentTask(String question, String rawPrompt) {
        String task = safe(rawPrompt).toLowerCase(Locale.ROOT);
        boolean softwareGoal = task.matches(".*(开发|实现|修复|重构|bug|接口|功能|代码).*" );
        boolean researchGoal = task.matches(".*(研究|论文|死亡率|发病率|yll|arriaga|统计分析).*" );
        return softwareGoal && !researchGoal
                && question.toLowerCase(Locale.ROOT)
                .matches(".*(这项研究|研究地区|研究范围|死亡率|减寿|arriaga|yll).*" );
    }

    /** 用户原文、用户历史、明确标签和事实卡片共同构成已知事实；模型回复不作为证据。 */
    private List<KnownFact> collectFacts(PlanningProviderRequest input) {
        List<KnownFact> facts = new ArrayList<>();
        collectFromText(facts, input.rawPrompt(), "user_prompt");
        collectFromText(facts, input.contextDescription(), "user_context");
        input.conversationHistory().stream().filter(message -> "user".equals(message.role()))
                .forEach(message -> collectFromText(facts, message.content(), "user_history"));
        if (input.planningContext() != null) {
            collectFromText(facts, input.planningContext().description(), "analyzed_description");
            input.planningContext().fileSummaries().forEach(value ->
                    collectFromText(facts, value, "analyzed_file_summary"));
            for (PlanningFactCard card : input.planningContext().factCards()) {
                // 有标签的卡片与普通文本使用同一取值/对象口径，避免完整证据与裸取值被误判成冲突。
                FactRule rule = RULES.stream().filter(value -> value.category() == card.category()).findFirst().orElseThrow();
                var matches = rule.label().matcher(card.evidence());
                boolean labeled = false;
                while (matches.find()) {
                    labeled = true;
                    facts.add(new KnownFact(card.category(), matches.group(1).trim(), card.sourcePath(),
                            PlanningFactScope.labeled(card.category(), card.evidence(), matches.start(1))));
                }
                if (!labeled) facts.add(new KnownFact(card.category(), card.evidence(), card.sourcePath(),
                        PlanningFactScope.evidence(card.category(), card.evidence())));
            }
        }
        return List.copyOf(facts);
    }

    private void collectFromText(List<KnownFact> facts, String text, String source) {
        if (text == null || text.isBlank()) return;
        for (FactRule rule : RULES) {
            var matches = rule.label().matcher(text);
            while (matches.find()) facts.add(new KnownFact(rule.category(), matches.group(1).trim(), source,
                    PlanningFactScope.labeled(rule.category(), text, matches.start(1))));
        }
    }

    /** 只有同一对象/属性存在唯一明确值且问题未要求变更时，才继承答案；类别相同不足以证明已解决。 */
    private boolean resolved(String question, List<KnownFact> facts, PlanningProviderRequest input) {
        if (explicitDeliveryOrFailure(question, input.rawPrompt())) return true;
        if (knownInteractivePlanDefinition(question, input.rawPrompt())) return true;
        if (explicitWritingLanguage(question, input.rawPrompt()) || knownImplementationLayer(question, input)) return true;
        if (answeredByUploadedProject(question, input)) return true;
        if (isCompoundOrChange(question)) return false;
        if (knownTechnologyAnswers(question, input)) return true;
        List<PlanningFactCategory> categories = questionCategories(question);
        if (categories.size() != 1) return false;
        PlanningFactCategory category = categories.getFirst();
        String questionScope = PlanningFactScope.question(category, question);
        Set<String> values = new HashSet<>();
        boolean uncertain = false;
        for (KnownFact fact : facts) {
            if (fact.category() != category) continue;
            if (category == PlanningFactCategory.BUSINESS_RULE) {
                // 有明确对象标题时必须完整匹配；无标题的既有自然语言规则继续采用有限主题校验。
                if (fact.scope() != null && !fact.scope().isEmpty()) {
                    if (!PlanningFactScope.same(questionScope, fact.scope())) continue;
                } else if (!sameTopic(question, fact.value())) continue;
            }
            if (category != PlanningFactCategory.BUSINESS_RULE
                    && !PlanningFactScope.same(questionScope, fact.scope())) continue;
            String value = fact.value();
            // 唯一的“未提供/未指定”不是唯一已知取值；保持未知，不能因标签匹配删除必要问题。
            if (PlanAnswerSemantics.unresolved(value)
                    || value.matches(".*(未知|待定|未明确|可能|建议|例如|某地区|某省|某市|[？?]).*")) uncertain = true;
            else values.add(normalize(value));
        }
        return !uncertain && values.size() == 1;
    }

    /** 只复用明确限定的交付范围和通用异常约定；重试次数、演示数据授权等独立选择继续询问。 */
    private boolean explicitDeliveryOrFailure(String question, String rawPrompt) {
        String raw = safe(rawPrompt).replaceAll("\\s", "");
        if (question.matches("^(?:本次)?是否(?:还)?需要(?:输出|提供|生成)(?:实际|真实)(?:分析)?结果[？?]$")
                && raw.matches("(?s).*(?:只|仅)(?:提供|交付|输出).*(?:方案|方法).*(?:框架|代码).*")) {
            return raw.matches("(?s).*(?:不|不得)(?:计算|生成|输出|提供)(?:真实|实际)(?:分析)?结果.*");
        }
        return question.replaceAll("[，,\\s]", "").matches("^(?:先)?(?:查询)?详情(?:接口)?(?:查询|调用)?(?:这一步)?失败(?:时)?(?:应|应该|需要)?(?:如何|怎么|怎样)处理[？?]$")
                && raw.contains("接口异常提醒但不阻断手工录入")
                && !raw.matches("(?s).*(?:仅|只)(?:针对|处理)?列表接口异常.*");
    }

    /** 只复用当前需求中明确的交付语言；资料语言、附录和编程语言不能替代该决定。 */
    private boolean explicitWritingLanguage(String question, String rawPrompt) {
        if (!question.matches(".*(?:使用中文|中文还是|输出语言|交付语言|用什么语言|中英双语).*")
                || question.matches(".*(?:附录|附件|摘要|引用|引文|术语|代码|编程|调整|更换|改为).*")
                || safe(rawPrompt).matches("(?s).*(?:英文|英语|双语|语言尚未|语言未确定).*")) return false;
        return Pattern.compile("(?:^|[。；;\\n]|(?:输出|提供|撰写|交付|生成)(?:一份|一篇)?)\\s*中文(?:方法|工作|年度)?(?:提纲|报告|正文|总结)"
                + "|(?:使用|采用|输出|写成|撰写|交付|语言[：:])\\s*(?:简体)?中文")
                .matcher(safe(rawPrompt)).find();
    }

    /**
     * 只回答某项具体逻辑落在哪一层；双方证据冲突、要求迁移或资料是示例时仍保留问题。
     * “文件位于前端”不是“业务过滤在前端”，必须有该逻辑与负责层的明确关系。
     */
    private boolean knownImplementationLayer(String question, PlanningProviderRequest input) {
        if (input.planningContext() == null || (safe(input.rawPrompt()) + question)
                .matches("(?s).*(?:迁移|更换|调整|改为|冲突).*") ) return false;
        String target = question;
        var qualifier = Pattern.compile("^“([^”]{1,80})”的(.+)$").matcher(target);
        if (qualifier.matches()) {
            String raw = safe(input.rawPrompt()).replaceAll("\\s", "");
            if (!java.util.Arrays.stream(qualifier.group(1).split("[、，,]"))
                    .allMatch(raw::contains)) return false;
            target = qualifier.group(2);
        }
        var subject = Pattern.compile("^(.{2,40}?)(?:（[^）]*）)?[，,]?(?:应该|应|需要)?在哪一层实现[？?]$").matcher(target);
        if (!subject.find()) return false;
        String object = Pattern.quote(subject.group(1));
        Pattern relation = Pattern.compile("(服务端|后端|前端|客户端)(?:负责|实现|完成)" + object
                + "|" + object + "(?:由|在)(服务端|后端|前端|客户端)(?:负责|实现|完成)");
        Set<String> layers = new HashSet<>();
        List<String> evidence = new ArrayList<>();
        for (PlanningFactCard card : input.planningContext().factCards()) {
            if (card.category() != PlanningFactCategory.BUSINESS_RULE
                    || !(card.origin() == PlanningFactOrigin.PROJECT_SOURCE || card.origin() == PlanningFactOrigin.PROJECT_DOCUMENT
                    || card.origin() == PlanningFactOrigin.USER_MATERIAL)) continue;
            evidence.add(card.evidence());
        }
        // 短事实卡片可能未收录“负责”关系，继续检查带已识别资料用途的安全摘要。
        input.planningContext().fileSummaries().stream()
                .filter(summary -> summary.matches("(?s)^\\[(?:PROJECT_SOURCE|PROJECT_DOCUMENT|USER_MATERIAL)] .*"))
                .forEach(evidence::add);
        for (String value : evidence) {
            if (!value.contains(subject.group(1))) continue;
            if (value.matches("(?s).*(?:建议|候选|计划|目标|示例|待定|未知|尚未|不是|并非|不由|不在|不负责).*")) return false;
            var matches = relation.matcher(value);
            while (matches.find()) {
                String layer = matches.group(1) == null ? matches.group(2) : matches.group(1);
                layers.add(layer.equals("服务端") || layer.equals("后端") ? "server" : "client");
            }
        }
        return layers.size() == 1;
    }

    /** 已明确研究交互问答时不重新选择机制；问答轮次、信息给法等设计细节继续保留。 */
    private boolean knownInteractivePlanDefinition(String question, String raw) {
        return safe(raw).matches("(?si).*plan\\s*问答.*")
                && question.matches("(?i)^“?Plan”?(?:模式|条件)(?:在方法部分)?(?:应如何界定|具体指什么操作|具体指什么|是什么)[？?]$");
    }

    private List<PlanningFactCategory> questionCategories(String question) {
        List<PlanningFactCategory> result = new ArrayList<>();
        for (FactRule rule : RULES) {
            if (rule.question().matcher(question).find() && !result.contains(rule.category())) {
                result.add(rule.category());
            }
        }
        return result;
    }

    /** 简单中文主题词交集；缺少可核实主题词时宁可保留问题。 */
    private boolean sameTopic(String question, String evidence) {
        Set<String> questionTerms = hanBigrams(question);
        questionTerms.removeAll(Set.of("业务", "规则", "条件", "标准", "如何", "怎样", "什么", "多少", "哪些", "是否"));
        Set<String> evidenceTerms = hanBigrams(evidence);
        evidenceTerms.removeAll(Set.of("业务", "规则", "条件", "标准", "如何", "怎样", "什么", "多少", "哪些", "是否"));
        return questionTerms.stream().filter(evidenceTerms::contains).count() >= 2;
    }

    private Set<String> hanBigrams(String value) {
        Set<String> terms = new HashSet<>();
        var tokens = Pattern.compile("[\\p{IsHan}]{2,}").matcher(value);
        while (tokens.find()) {
            String token = tokens.group();
            for (int index = 0; index + 2 <= token.length(); index++) terms.add(token.substring(index, index + 2));
        }
        return terms;
    }

    /**
     * 已上传项目里能直接读到的路径、表结构和工具版本不再向用户索取。
     * 要求更换或升级时仍保留问题。
     */
    private boolean answeredByUploadedProject(String question, PlanningProviderRequest input) {
        if (question.matches(".*(更换|调整|迁移|升级|降级|改为|转为).*")) return false;
        String corpus = projectCorpus(input).toLowerCase(Locale.ROOT);
        if (corpus.isBlank()) return false;
        if (asksWhereSourceLives(question)) {
            List<String> symbols = distinctiveSymbols(question);
            return !symbols.isEmpty() && symbols.stream()
                    .allMatch(symbol -> corpus.contains(symbol.toLowerCase(Locale.ROOT)));
        }
        if (asksForSchema(question)) {
            boolean hasSchema = corpus.contains("create table") || corpus.contains(".sql")
                    || corpus.contains("mapper.xml");
            return hasSchema && schemaMatchesQuestion(question, corpus);
        }
        if (asksForJavaVersion(question)) {
            return corpus.matches("(?s).*\\bjava\\s*\\d{2}\\b.*")
                    || corpus.contains("<java.version>")
                    || corpus.contains("java.version>");
        }
        if (question.toLowerCase(Locale.ROOT).contains("mybatis") && question.contains("版本")) {
            return Pattern.compile("mybatis[^\\n]{0,80}\\d+\\.\\d+").matcher(corpus).find();
        }
        return false;
    }

    private boolean asksWhereSourceLives(String question) {
        return question.matches(".*(目录|仓库|源码路径|代码片段|代码在哪).*");
    }

    private boolean asksForSchema(String question) {
        return question.matches(".*(表结构|表名|字段及索引|索引信息).*");
    }

    private boolean asksForJavaVersion(String question) {
        return question.matches("(?i).*java\\s*版本.*") || question.matches("(?i).*使用的\\s*java.*版本.*");
    }

    /** 统计日志类问题对应 analytics / audit 材料；其它表结构问题要求问题里的标识出现在材料中。 */
    private boolean schemaMatchesQuestion(String question, String corpus) {
        if (question.contains("统计") || question.contains("日志")) {
            return corpus.contains("analytics") || corpus.contains("audit");
        }
        List<String> symbols = distinctiveSymbols(question);
        return !symbols.isEmpty() && symbols.stream()
                .allMatch(symbol -> corpus.contains(symbol.toLowerCase(Locale.ROOT)));
    }

    private List<String> distinctiveSymbols(String question) {
        List<String> symbols = new ArrayList<>();
        var matcher = Pattern.compile("\\b[A-Z][A-Za-z0-9]{8,}\\b").matcher(question);
        while (matcher.find()) symbols.add(matcher.group());
        return symbols;
    }

    private String projectCorpus(PlanningProviderRequest input) {
        StringBuilder corpus = new StringBuilder();
        if (input.planningContext() == null) return "";
        input.planningContext().technologies().forEach(value -> corpus.append('\n').append(value));
        input.planningContext().dependencies().forEach(value -> corpus.append('\n').append(value));
        input.planningContext().directoryOverview().forEach(value -> corpus.append('\n').append(value));
        input.planningContext().fileSummaries().forEach(value -> corpus.append('\n').append(value));
        input.planningContext().factCards().forEach(card -> corpus.append('\n')
                .append(card.sourcePath()).append(' ').append(card.evidence()));
        return corpus.toString();
    }

    /** 项目已有实现或依赖可回答“当前采用什么”，但不能回答“是否迁移/更换”。 */
    private boolean knownTechnologyAnswers(String question, PlanningProviderRequest input) {
        if (!question.matches(".*(什么|哪个|哪种|哪些|采用|使用).*(框架|技术栈|数据库|依赖|运行环境|编程语言).*"
                ) && !question.matches(".*(框架|技术栈|数据库|依赖|运行环境|编程语言).*(什么|哪个|哪种|哪些).*")) return false;
        StringBuilder known = new StringBuilder(safe(input.rawPrompt())).append(' ').append(input.contextDescription());
        if (input.planningContext() != null) {
            input.planningContext().technologies().forEach(value -> known.append(' ').append(value));
            input.planningContext().dependencies().forEach(value -> known.append(' ').append(value));
        }
        String normalized = known.toString().toLowerCase(Locale.ROOT);
        String[] catalog;
        if (question.contains("数据库")) {
            catalog = new String[] {"postgresql", "mysql", "mariadb", "sqlite", "mongodb", "oracle", "sql server"};
        } else if (question.contains("前端")) {
            catalog = new String[] {"vue", "react", "angular", "svelte"};
        } else if (question.contains("后端")) {
            catalog = new String[] {"spring boot", "django", "fastapi", "nestjs", "express", "quarkus", "micronaut"};
        } else if (question.contains("语言")) {
            catalog = new String[] {"java", "typescript", "javascript", "python", "go", "rust", "kotlin", "c#", "c++"};
        } else if (question.contains("依赖")) {
            return input.planningContext() != null && !input.planningContext().dependencies().isEmpty();
        } else {
            catalog = new String[] {"spring boot", "vue", "react", "angular", "django", "fastapi", "nestjs", "quarkus", "micronaut"};
        }
        return java.util.Arrays.stream(catalog).filter(normalized::contains).count() == 1;
    }

    /** 复合问题或明确要求变更的选择必须保留给用户。 */
    private boolean isCompoundOrChange(String question) {
        return question.matches(".*(以及|和|与|是否|更换|调整|迁移|冲突|还是|升级|降级|改为|转为).*" );
    }

    private String questionDimension(String question) {
        return PlanDecisionIdentity.questionKey(question);
    }

    private static FactRule rule(PlanningFactCategory category, String question, String label) {
        return new FactRule(category, Pattern.compile(question), Pattern.compile(label));
    }

    private String normalize(String value) {
        return PlanDecisionIdentity.exactTextKey(value);
    }

    private String safe(String value) { return value == null ? "" : value; }
}
