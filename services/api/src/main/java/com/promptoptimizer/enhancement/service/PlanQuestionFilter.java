package com.promptoptimizer.enhancement.service;

import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.PlanningFactCard;
import com.promptoptimizer.enhancement.domain.PlanningFactCategory;
import com.promptoptimizer.provider.domain.PlanningProviderRequest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** 保守过滤已由明确证据回答或重复的问题；不确定和冲突问题交由用户确认。 */
public final class PlanQuestionFilter {
    private record FactRule(PlanningFactCategory category, Pattern question, Pattern label) { }
    private record KnownFact(PlanningFactCategory category, String value, String source) { }

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

    /** 去掉完全重复、已明确事实及同一事实维度的简单同义问法。 */
    public List<PlanQuestion> filter(List<PlanQuestion> questions, PlanningProviderRequest input) {
        List<KnownFact> facts = collectFacts(input);
        Set<String> seen = new HashSet<>();
        Set<String> seenDimensions = new HashSet<>();
        return questions.stream()
                .filter(question -> seen.add(normalize(question.question())))
                .filter(question -> !clearlyOutsideCurrentTask(question.question(), input.rawPrompt()))
                .filter(question -> !resolved(question.question(), facts, input))
                .filter(question -> {
                    String dimension = questionDimension(question.question());
                    return dimension == null || seenDimensions.add(dimension);
                }).toList();
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
                facts.add(new KnownFact(card.category(), card.evidence(), card.sourcePath()));
            }
        }
        return List.copyOf(facts);
    }

    private void collectFromText(List<KnownFact> facts, String text, String source) {
        if (text == null || text.isBlank()) return;
        for (FactRule rule : RULES) {
            var matches = rule.label().matcher(text);
            while (matches.find()) facts.add(new KnownFact(rule.category(), matches.group(1).trim(), source));
        }
    }

    /** 只有同一类别只有一个明确值且问题未要求变更时，才不再确认。 */
    private boolean resolved(String question, List<KnownFact> facts, PlanningProviderRequest input) {
        if (answeredByUploadedProject(question, input)) return true;
        if (isCompoundOrChange(question)) return false;
        if (knownTechnologyAnswers(question, input)) return true;
        List<PlanningFactCategory> categories = questionCategories(question);
        if (categories.size() != 1) return false;
        PlanningFactCategory category = categories.getFirst();
        Set<String> values = new HashSet<>();
        boolean uncertain = false;
        for (KnownFact fact : facts) {
            if (fact.category() != category) continue;
            if (category == PlanningFactCategory.BUSINESS_RULE && !sameTopic(question, fact.value())) continue;
            String value = fact.value();
            if (value.matches(".*(未知|待定|未明确|可能|建议|例如|某地区|某省|某市|[？?]).*")) uncertain = true;
            else values.add(normalize(value));
        }
        return !uncertain && values.size() == 1;
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
        if (isCompoundOrChange(question)) return null;
        List<PlanningFactCategory> categories = questionCategories(question);
        if (categories.size() == 1) return categories.getFirst().name();
        if (question.matches(".*(框架|技术栈|数据库|依赖|运行环境|编程语言).*")) {
            if (question.contains("数据库")) return "DATABASE";
            if (question.contains("依赖")) return "DEPENDENCIES";
            if (question.contains("语言")) return "PROGRAMMING_LANGUAGE";
            if (question.contains("前端")) return "FRONTEND_FRAMEWORK";
            if (question.contains("后端")) return "BACKEND_FRAMEWORK";
            return "TECHNOLOGY_STACK";
        }
        return null;
    }

    private static FactRule rule(PlanningFactCategory category, String question, String label) {
        return new FactRule(category, Pattern.compile(question), Pattern.compile(label));
    }

    private String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}\\p{IsPunctuation}\\s]+", "");
    }

    private String safe(String value) { return value == null ? "" : value; }
}
