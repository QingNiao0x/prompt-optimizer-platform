package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.ConfirmedPlanDecision;
import com.promptoptimizer.enhancement.domain.ConfirmedPlanDecision.Scope;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException.Reason;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 保留用户明确规则，并拦截可确定的否定、数值边界和操作反转。
 * 仅比较作用对象与条件一致的短句，不把词汇相似当成开放域语义等价。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class RequirementFidelityGuard {
    private static final Logger LOGGER = LoggerFactory.getLogger(RequirementFidelityGuard.class);
    private static final Pattern REQUIREMENT = Pattern.compile(
            "(?i)(必须|须|不得|禁止|不能|不允许|不超过|不少于|不低于|不高于|至少|最多|仅|只|"
                    + "保持|保留|排除|确认|取消|不填|不覆盖|不修改|不改变|不阻断|不回滚|附表另计|plan\\s*问答|按.+(?:降序|升序).*(?:选|取)|"
                    + "研究范围[：:]|时间范围[：:]|统计口径[：:]|格式要求[：:]|"
                    + "\\bmust\\b|\\bshall\\b|\\bonly\\b|\\bnever\\b|\\bdo not\\b)");
    private static final Pattern INSTRUCTION_OVERRIDE = Pattern.compile(
            "(?i)(忽略|覆盖|绕过|删除|削弱).{0,16}(系统指令|平台约束|权限红线|安全规则)|"
                    + "ignore.{0,20}(system|platform).{0,12}(instruction|constraint)");
    private static final Pattern UNCERTAIN = Pattern.compile("[？?]|^(?:请问)?是否|例如|举例|假设");
    private static final Pattern ACTION = Pattern.compile("填充|覆盖|删除|部署|迁移|提交|发送|导出|执行");
    private static final Pattern NEGATION = Pattern.compile("不得|禁止|不允许|不需要|不能|不应|严禁|不必|无需|不要|不(?=填|覆盖|修改|改变|回滚|阻断|保持|保留|输出)");
    private static final Pattern QUANTITY = Pattern.compile(
            "\\d+(?:\\.\\d+)?|[零〇一二三四五六七八九十百千万两]+(?=个|条|次|字|页|元|天|年|月|小时|分钟|秒|%)");
    private static final Pattern MISSING_STATE_MAPPING = Pattern.compile("(?:视为|当作|按|计为)(?:未完成|零值|0)");
    private static final Pattern STATE_SUBJECT = Pattern.compile(
            "缺失(?:答卷|记录|值|数据)?|未完成(?:答卷|记录|值|数据)|零值(?:答卷|记录|值|数据)?");
    private static final String MAPPING_NEGATION = "(?:不得|不能|不允许|禁止|严禁|不要|不应|不|未)";
    private static final String MAPPING_MODIFIERS = "(?:再|直接|一律|统一|简单|自动|擅自|地)*";
    private static final String MISSING_OBJECT = "(?:其|这些|该|上述|所有|缺失(?:答卷|记录|值|数据)?)";
    private static final String MAPPING_OBJECT = "(?:(?:将|把)" + MISSING_OBJECT + ")?";
    private static final Pattern NEGATED_MAPPING_PREFIX = Pattern.compile(
            MAPPING_NEGATION + MAPPING_MODIFIERS + MAPPING_OBJECT + MAPPING_MODIFIERS + "(?:被)?$");
    private static final String MISSING_CONTROL_ACTION = MAPPING_MODIFIERS + MAPPING_OBJECT + MAPPING_MODIFIERS
            + "(?:剔除|删除|移除|排除|补零|补0)(?:缺失(?:答卷|记录|值|数据)?)?";
    private static final Pattern COORDINATED_NEGATED_MAPPING_PREFIX = Pattern.compile(
            MAPPING_NEGATION + MISSING_CONTROL_ACTION
                    + "(?:(?:、|或|或者|和|及)" + MISSING_CONTROL_ACTION + ")*"
                    + "(?:或|或者|和|及)" + MAPPING_OBJECT + MAPPING_MODIFIERS + "(?:被)?$");
    private static final Pattern MAPPING_COORDINATION = Pattern.compile("(?:、|或|或者|和|及)" + MAPPING_MODIFIERS);
    private final SensitiveValueDetector sensitiveValueDetector = new SensitiveValueDetector();

    /** 原始要求和明确执行决定可保留原句；未决回答与现状说明不能冒充本次执行规则。 */
    List<String> explicitRules(String rawPrompt, List<ConfirmedPlanDecision> decisions) {
        Set<String> answers = new LinkedHashSet<>();
        for (ConfirmedPlanDecision decision : decisions) {
            if (decision.scope() != Scope.CURRENT_STATE) {
                collect(decision.scope() == Scope.UNRESOLVED
                        ? PlanAnswerSemantics.confirmedPart(decision.answer()) : decision.answer(), answers);
                // 用户暂未决定参数，不等于放弃其明确的禁止猜测、等待确认等执行边界。
                if (decision.scope() == Scope.UNRESOLVED) {
                    Arrays.stream(decision.answer().split("(?<=[。！？!?])|\\R"))
                            .filter(sentence -> sentence.matches(".*(?:不得|不能|禁止|不默认|不自行|不编造|仅预留|只预留).*"))
                            .forEach(sentence -> collect(sentence, answers));
                }
            }
        }
        Set<String> rules = new LinkedHashSet<>();
        collect(rawPrompt, rules);
        // 绑定确认可以改变业务选择；平台权限仍由独立的强制约束维护，不能在此被覆盖。
        Set<String> effective = new LinkedHashSet<>();
        for (String rule : rules) {
            // 确认请求不是永久业务规则；只移除可由同字段、同候选对的明确答案证明已解决的子句。
            String pending = clauses(rule).stream().filter(clause -> !resolvedChoice(clause, decisions))
                    .collect(java.util.stream.Collectors.joining("；"));
            if (pending.isBlank()) continue;
            if (!pending.equals(String.join("；", clauses(rule)))) rule = pending;
            if (!hasContradiction(rule, List.copyOf(answers))) {
                effective.add(rule);
                continue;
            }
            // 只移除被后续选择替代的完整子句；同句中其他保密、兼容性或异常处理要求仍须保留。
            String retained = clauses(rule).stream().filter(clause -> !hasContradiction(clause, List.copyOf(answers)))
                    .collect(java.util.stream.Collectors.joining("；"));
            if (!retained.isBlank()) effective.add(retained);
        }
        effective.addAll(answers);
        return List.copyOf(effective);
    }

    /** 仅处理已绑定冲突题的取值选择；新取值、未知回答及同句独立执行条件不能随之丢失。 */
    private boolean resolvedChoice(String clause, List<ConfirmedPlanDecision> decisions) {
        String text = normalize(clause);
        if (!text.matches(".*(?:冲突|未确定|未明确).*(?:确认|选择).*(?:哪一个|哪一项|哪个|何者).*")
                || text.matches(".*(?:不得|禁止|保留|保持|严格|等于|大于|小于|退款|例外).*")) return false;
        for (ConfirmedPlanDecision decision : decisions) {
            if (decision.questionId() == null || !decision.questionId().startsWith("context-conflict-")
                    || decision.scope() == Scope.UNRESOLVED) continue;
            String field = normalize(decision.topic()).replace("金额", "");
            if (!text.replace("金额", "").contains(field)) continue;
            List<String> values = Pattern.compile("（([^）]+)）").matcher(decision.question()).results()
                    .map(match -> normalizeQuantities(match.group(1))).distinct().toList();
            if (values.size() != 2 || values.stream().anyMatch(value -> !normalizeQuantities(text).contains(value))) continue;
            // 数值集合完全相同才删除旧选择；出现第三个新金额时必须保留。
            List<String> numbers = QUANTITY.matcher(text.replaceAll("两个|两项|哪一个|哪一项", "")).results()
                    .map(match -> normalizeNumber(match.group())).distinct().sorted().toList();
            List<String> knownNumbers = values.stream().flatMap(value -> QUANTITY.matcher(value).results())
                    .map(match -> normalizeNumber(match.group())).distinct().sorted().toList();
            if (!numbers.equals(knownNumbers)) continue;
            var selected = Pattern.compile("^(?:本次|最终|决定)?(?:采用|选定|选择|以|使用)(.+)")
                    .matcher(normalize(decision.answer().split("[。；;，,]", 2)[0]));
            if (selected.find() && !selected.group(1).matches(".*(?:不|未|可能|如果|或者|还是|都).*")) {
                String answer = normalizeQuantities(selected.group(1));
                if (values.stream().filter(answer::contains).count() == 1) return true;
            }
        }
        return false;
    }

    /** 中文金额与阿拉伯金额只作文本等值比较，不补造候选项或单位。 */
    private String normalizeQuantities(String text) {
        return QUANTITY.matcher(normalize(text)).replaceAll(match -> normalizeNumber(match.group()));
    }

    /** 模型不得以追加正确原文掩盖另一个执行段落中的相反要求。 */
    void validate(String draft, List<String> rules, String field) {
        List<String> statements = clauses(draft);
        // 同一组规则服务于本段所有断言，分句只计算一次，避免长需求在嵌套循环中重复解析。
        List<List<String>> expectedByRule = rules.stream().map(this::clauses).toList();
        for (int statementIndex = 0; statementIndex < statements.size(); statementIndex++) {
            String statement = statements.get(statementIndex);
            for (int ruleIndex = 0; ruleIndex < rules.size(); ruleIndex++) {
                List<String> expectedClauses = expectedByRule.get(ruleIndex);
                for (int expectedIndex = 0; expectedIndex < expectedClauses.size(); expectedIndex++) {
                    String expected = expectedClauses.get(expectedIndex);
                    if (contradicts(expected, statement)) {
                        // 仅保存位置与分类，便于按同一合成输入复现；不记录规则、模型正文或凭据。
                        LOGGER.warn("event=requirement.rule_conflict field={} ruleIndex={} expectedClauseIndex={} statementIndex={}",
                                field, ruleIndex, expectedIndex, statementIndex);
                        throw new ProviderResponseValidationException(Reason.RULE_CONFLICT, field);
                    }
                }
            }
        }
    }

    /** 文档与本次明确选择冲突时，不把文档升级为覆盖用户决定的校验规则。 */
    List<String> compatibleSourceRules(List<String> sources, List<String> explicitRules) {
        return sources.stream().filter(source -> !hasContradiction(source, explicitRules))
                .filter(source -> !hasContradiction(source, sources.stream().filter(other -> !other.equals(source)).toList()))
                .toList();
    }

    /** 只按完整规范化原句判定已覆盖，出现几个关键词不足以免除规则保留。 */
    boolean containsRule(String text, String rule) {
        if (text == null || text.isBlank() || rule == null || rule.isBlank()) return false;
        String expected = normalize(rule);
        // 子串可能处于否定、例子或历史引用中；无法证明完整覆盖时补回原句，不能仅凭包含关系放行。
        return Pattern.compile("(?<=[。！？!?])|\\R").splitAsStream(text)
                .map(this::normalize).anyMatch(expected::equals);
    }

    /** 提取完整明确要求，不把示例、未知回答或越权提示升级为执行规则。 */
    private void collect(String text, Set<String> rules) {
        if (text == null || text.isBlank()) return;
        // 保留同句中的条件与例外；“数据尚未提供，但不得编造”仍是明确要求，不能因未知词删掉整句。
        for (String sentence : text.split("(?<=[。！？!?])|\\R")) {
            String value = sentence.strip().replaceFirst("^(?:[-*•]\\s+|\\d+[.)、]\\s*)", "");
            // 问号后的裸待定状态没有业务对象，不能在答案绑定后继续宣称已定选择尚未决定。
            // 保留禁止猜测的条件边界；具体未决问题仍由权威提醒列表及 Plan 未决答案完整交付。
            if (value.matches("^当前(?:尚未|还未)(?:决定|确定)[，,](?:不得|不能)默认补全[。.]?$")) {
                value = "对于仍未明确的条件，不得默认补全。";
            }
            if (!value.isBlank() && REQUIREMENT.matcher(value).find() && !UNCERTAIN.matcher(value).find()
                    && !INSTRUCTION_OVERRIDE.matcher(value).find() && !sensitiveValueDetector.containsCredential(value)) {
                rules.add(value);
            }
        }
    }

    /** 来源冲突与确认替代都需双向比较，不能只检测某一种否定方向。 */
    private boolean hasContradiction(String text, List<String> rules) {
        return clauses(text).stream().anyMatch(clause -> rules.stream().flatMap(rule -> clauses(rule).stream())
                .anyMatch(other -> contradicts(clause, other) || contradicts(other, clause)));
    }

    /** 只拒绝明确执行断言；未知、对照引用和用于验证拒绝行为的反例不作为新要求。 */
    private boolean contradicts(String expectedText, String actualText) {
        String expected = normalize(expectedText);
        String actual = normalize(actualText);
        if (expected.isBlank() || actual.isBlank() || expected.equals(actual)
                || actual.matches(".*(?:反例|错误示例|原错误|旧规则|历史规则|[？?]).*")) return false;
        if (defaultsUnconfirmedFact(expected, actual)) return true;
        // 明确的字段映射禁令不是待选偏好；注明“假设/待确认”也不能把按同名映射变成合法候选。
        if (reversesExplicitFieldMapping(expected, actual)) return true;
        if (actual.matches(".*(?:待确认|尚未|暂不确定).*")) return false;
        if (actual.matches(".*(?:测试|验证).*(?:拒绝|拦截|失败|不应).*")
                || actual.matches(".*(?:拒绝|拦截|失败).*(?:测试|验证).*")) return false;

        // 已确定的自动选取规则不是供模型重新发起的业务选择；展示已选结果仍然合法。
        if (expected.matches(".*多条(?:有效)?记录按.*(?:降序|升序).*(?:选一条|取第一条).*")
                && actual.matches(".*(?:展示|列出)(?:多条|并列的?)?(?:有效)?记录.*(?:供|由|让)用户(?:自行)?选择.*")
                && !actual.matches(".*(?:不|不得|禁止|无需)(?:展示|列出).*")) return true;
        // 研究对象是交互问答时，填写示例也不能偷换成静态模板或模型自行列步骤。
        if (expected.contains("plan问答") && !actual.matches(".*(?:用户回答|用户确认|向用户提问|先提问|问答).*")) {
            if (actual.matches(".*(?:预先编写|预先制定).*(?:计划|规划)模板.*模型按模板执行.*")
                    || actual.matches(".*先(?:让模型|由模型|输出|生成|制定).*(?:计划|步骤).*(?:再|然后).*(?:作答|回答).*" )
                    || actual.matches(".*(?:研究者|用户)提供计划文本.*作为提示词.*")) return true;
        }
        // 方案与代码是并列要求，输出组织方式不能成为删掉其中一项的授权。
        if (expected.matches(".*(?:交付|输出|提供|给出).*(?:分析|研究)?方案(?:与|和|及|以及|、)(?:sql|r|python)?(?:代码框架|伪代码).*")) {
            if (actual.matches(".*(?:不|无需|不必)(?:单独)?(?:提供|输出|撰写|写)(?:分析|研究)?方案(?:说明)?$")
                    || actual.matches(".*(?:不|无需|不必)(?:单独)?(?:提供|输出|撰写|写)(?:sql|r|python)?(?:代码框架|伪代码)$")
                    || actual.matches("^(?:只要|仅|只)(?:提供|输出)?(?:sql伪代码|分析方案)$")) return true;
        }
        // 附表另计不能变成正文与表格共用字数预算；表注等未说明的细节不在此推断。
        if (expected.contains("附表另计")
                && actual.matches(".*(?:字|字数|篇幅).*(?:包含|计入|包括).*(?:正文.*(?:和|及|、).*表格内容|全部附表).*")
                && !actual.matches(".*(?:不包含|不计入|不包括|不得|不能).*")) return true;

        // 表单回放中的语义反转不是简单漏词；同时识别保留有效值和取消不改变字段的要求。
        if (expected.matches(".*取消.{0,12}(?:保持|保留|不修改|不改变).*原值.*")
                && changesValuesOnCancellation(actual)) return true;
        if (expected.matches(".*(?:只填|仅填|仅向|只向|只给|仅给).*(?:null|空字符串|空字段|空值).*")
                && actual.matches(".*(?:清空.{0,12}字段|填入null|赋值为null|填入空字符串).*")
                && !actual.matches(".*(?:不|不得|禁止|不要|不能)(?:清空|填入|赋值).*")) return true;
        if (expected.contains("0") && expected.contains("false")
                && expected.matches(".*(?:保留|保持).*") && replacesValidValues(actual)) return true;
        if (expected.matches(".*(?:确认后|经.{0,8}确认|先.{0,8}确认|提示用户是否|人工确认).*")
                && actual.matches(".*(?:无需|不需要|不必|不再|不额外|不经|跳过|绕过).{0,8}确认.*")
                && !actual.matches(".*(?:不得|禁止|不能|不要|不应).*(?:跳过|绕过|省略).*" )
                && !actual.matches(".*(?:经用户确认|用户(?:点击)?确认后|已(?:经)?确认后).*(?:无需|不需要|不必|不额外).*(?:再次|重复|二次|弹窗)?确认.*")
                && ACTION.matcher(expected).results().anyMatch(action -> actual.contains(action.group()))) return true;
        if (expected.contains("填充")
                && expected.matches(".*(?:确认后|经.{0,8}确认|先.{0,8}确认|提示用户是否|人工确认).*")
                && immediateFillBeforeConfirmation(actual)) return true;
        if (expected.matches(".*(?:不得|不能|禁止).*(?:未完成|缺失|零值).*(?:混同|等同).*")
                && conflatesMissingState(actual)) return true;
        // 完成日期未知不能推出按期，也不能因缺少按期证据反推逾期；保留补齐日期后的事实判断。
        if (expected.matches(".*缺少完成日期.*(?:不得|不能).*(?:臆断|推定).*按期完成.*")
                && actual.matches(".*(?:缺少完成日期|完成日期(?:为空|缺失)).*(?:按|视为|标记为)历史逾期.*")
                && !actual.matches(".*(?:不按|不视为|不标记|不能|不得|不判断).*")) return true;

        // 完整作用域相同才比较反向谓词，避免将另一个渠道、阶段或对象的合法规则判成冲突。
        var negative = NEGATION.matcher(expected);
        while (negative.find()) {
            for (String positive : List.of("", "允许", "可以", "必须", "应当", "需要")) {
                if ((expected.substring(0, negative.start()) + positive + expected.substring(negative.end())).equals(actual)) return true;
            }
        }
        var addedNegation = NEGATION.matcher(actual);
        while (addedNegation.find()) {
            String withoutNegation = actual.substring(0, addedNegation.start()) + actual.substring(addedNegation.end());
            if (withoutNegation.equals(expected)) return true;
        }
        if (expected.matches(".*(?:必须|需要|应当).*") && actual.matches(".*(?:无需|不必|不需要).*")) {
            if (expected.replaceFirst("必须|需要|应当", "").equals(actual.replaceFirst("无需|不必|不需要", ""))) return true;
        }
        QuantityRule left = quantityRule(expected);
        QuantityRule right = quantityRule(actual);
        return !left.values().isEmpty() && left.skeleton().equals(right.skeleton())
                && (!left.values().equals(right.values()) || !left.operators().equals(right.operators()));
    }

    /** 只比较完整命名关系，不把合法并列展示、核验后的映射或分别使用两个日期判为违规。 */
    private boolean reversesExplicitFieldMapping(String expected, String actual) {
        boolean negated = actual.matches(".*(?:不得|不能|禁止|不要|不应|不)(?:直接|按|将|把|用|视为|当成).*" );
        if (negated) return false;
        if (expected.matches(".*(?:不能|不得|禁止).*同名.*同义.*" )) {
            if (actual.matches(".*同名字段直接(?:视为|当成|当作)同义.*")
                    || actual.matches(".*直接按同名映射.*")) return true;
        }
        return expected.matches(".*(?:不能|不得|禁止).*创建日期.*(?:当作|作为|代替|视为)就诊日期.*")
                && actual.matches(".*(?:用|将|把)?创建日期(?:代替|当作|作为|视为)就诊日期.*");
    }

    /**
     * 按取消分支中各动作的邻近否定判断，不把“禁止清空或覆盖”误当作清空许可。
     * 并列禁令共享否定，“但覆盖原值”等独立正向动作仍必须拦截；清理候选列表不等于清空表单。
     */
    private boolean changesValuesOnCancellation(String statement) {
        var actions = Pattern.compile("(?:移除|删除)(?:原有|现有|已有)?(?:保持|保留)原值(?:的)?(?:逻辑|保护)?"
                + "|取消(?:保持|保留)原值(?:的)?(?:逻辑|保护|选项)"
                + "|清空(?:(?:全部|所有|任何|表单|目标|现有|已有|原有|的)*(?:字段|原值|表单)|(?=或|及|和|、))"
                + "|覆盖原值|无需保持|不再保持|不必保留").matcher(statement);
        int cancellation = statement.indexOf("取消");
        int previousEnd = -1;
        boolean previousProhibited = false;
        while (actions.find()) {
            boolean removesProtection = actions.group().matches("^(?:移除|删除|取消(?:保持|保留)).*");
            if (!removesProtection && (cancellation < 0 || actions.start() < cancellation)) continue;
            String prefix = statement.substring(0, actions.start());
            boolean prohibited = prefix.matches(".*(?:不|不得|不能|禁止|不要|不应|避免)(?:再|直接|自动|随意)?$");
            if (previousEnd >= 0 && previousProhibited
                    && statement.substring(previousEnd, actions.start()).matches("(?:的|任何|全部|所有|已有|表单|字段|原值)*(?:或|及|和|、|并|并且)")) {
                prohibited = true;
            }
            if (!prohibited) return true;
            previousEnd = actions.end();
            previousProhibited = true;
        }
        return false;
    }

    /** 匹配后立即填充是明确的执行时序；之后确认或撤销不能替代此前要求的首次确认。 */
    private boolean immediateFillBeforeConfirmation(String statement) {
        var immediate = Pattern.compile("(?:匹配到(?:记录|基线|数据)|匹配成功|当前地区(?:存在|有)匹配(?:记录|基线)?)"
                + "(?:后|时)?(?:直接|立即|马上)(?:自动)?填充(?:基本信息|字段|表单)").matcher(statement);
        while (immediate.find()) {
            String preceding = statement.substring(0, immediate.start());
            if (preceding.matches(".*(?:不|不得|不能|禁止|不要|不应)(?:在)?$")) continue;
            if (!preceding.matches(".*(?:经用户确认|用户(?:点击)?确认后|用户点击填充后|经确认后).*")) return true;
        }
        return false;
    }

    /** 逐个检查作用于有效值的动作，否定只覆盖相应动作，不能由同句另一条禁令替代。 */
    private boolean replacesValidValues(String statement) {
        var actions = Pattern.compile("0.{0,40}false.{0,60}?(视为空值|当作空值|覆盖|清空)").matcher(statement);
        while (actions.find()) {
            String prefix = statement.substring(0, actions.start(1));
            if (prefix.matches(".*(?:不|不得|不能|不应|不要|禁止)(?:再|直接|一律|自动)?$")) continue;
            if (prefix.matches(".*(?:不得|不能|不应|不要|禁止)(?:将|把)?0[、和与及]*false(?:[、和与及]*非空字符串)?$")) continue;
            String next = statement.substring(actions.end(1));
            if (next.matches("^(?:空字段|空字符串|null).*")) continue;
            return true;
        }
        return false;
    }

    /** 核对每个状态映射的对象与否定范围，只允许否定沿同一对象的明确并列动作继承。 */
    private boolean conflatesMissingState(String statement) {
        var mapping = MISSING_STATE_MAPPING.matcher(statement);
        int previousEnd = -1;
        boolean previousMissing = false;
        boolean previousProhibited = false;
        while (mapping.find()) {
            String preceding = statement.substring(0, mapping.start());
            var subject = STATE_SUBJECT.matcher(preceding);
            String lastSubject = "";
            while (subject.find()) lastSubject = subject.group();
            boolean coordinated = previousEnd >= 0
                    && MAPPING_COORDINATION.matcher(statement.substring(previousEnd, mapping.start())).matches();
            // “缺失单列，未完成记录计为未完成”在第二个动作已明确换了对象，不推断为缺失映射。
            boolean missing = lastSubject.startsWith("缺失") || (coordinated && previousMissing);
            // “不得剔除、补零或把缺失当作未完成”共享一个禁令；但“不得剔除，但将其视为未完成”不共享。
            boolean prohibited = NEGATED_MAPPING_PREFIX.matcher(preceding).find()
                    || COORDINATED_NEGATED_MAPPING_PREFIX.matcher(preceding).find()
                    || (coordinated && previousMissing && previousProhibited);
            if (missing && !prohibited) return true;
            previousEnd = mapping.end();
            previousMissing = missing;
            previousProhibited = prohibited;
        }
        return false;
    }

    /** 已知事实仍未定时，不允许“先指定具体值、以后再确认”；合法用户选定与占位接口不在此拦截。 */
    private boolean defaultsUnconfirmedFact(String expected, String actual) {
        var selection = Pattern.compile("^(.{2,40}?)(?:尚未确定|未确定|尚未确认|未确认)(.*?)(?:先指定采用|先指定使用|默认采用|暂按)(.+)")
                .matcher(actual);
        if (!selection.matches() || !expected.contains(selection.group(1))
                || !expected.matches(".*(?:未确定|未确认).*")) return false;
        if (selection.group(2).matches(".*(?:不得|不能|不应|先经用户确认|经用户确认后).*")
                || selection.group(3).matches("^(?:占位|空值|空字符串|接口|待确认).*$")) return false;
        return true;
    }

    /** 数量比较保持对象、动作和条件原样；只归一化数值及常见比较符，不推算材料未给出的值。 */
    private QuantityRule quantityRule(String text) {
        String comparable = text.replaceAll("不得超过|不超过|至多|最多|小于等于|<=", "≤")
                .replaceAll("不少于|不低于|至少|大于等于|>=", "≥")
                .replaceAll("严格大于|超过|大于", ">")
                .replaceAll("严格小于|少于|低于|小于", "<");
        List<String> values = QUANTITY.matcher(comparable).results().map(match -> normalizeNumber(match.group())).toList();
        List<String> operators = Pattern.compile("[≤≥<>]").matcher(comparable).results().map(match -> match.group()).toList();
        String skeleton = QUANTITY.matcher(comparable).replaceAll("#").replaceAll("[≤≥<>]", "@");
        return new QuantityRule(skeleton, values, operators);
    }

    /** 阿拉伯数值去除无意义小数；中文小整数与单位乘法仅用于比较已有原文。 */
    private String normalizeNumber(String text) {
        if (text.matches("\\d+(?:\\.\\d+)?")) return new BigDecimal(text).stripTrailingZeros().toPlainString();
        long total = 0;
        long section = 0;
        long digit = 0;
        String digits = "零一二三四五六七八九";
        for (char character : text.replace('〇', '零').replace('两', '二').toCharArray()) {
            int value = digits.indexOf(character);
            if (value >= 0) { digit = value; continue; }
            long unit = switch (character) { case '十' -> 10; case '百' -> 100; case '千' -> 1000; case '万' -> 10000; default -> 1; };
            if (unit == 10000) { total += (section + digit) * unit; section = 0; }
            else section += (digit == 0 ? 1 : digit) * unit;
            digit = 0;
        }
        return Long.toString(total + section + digit);
    }

    /** 不在逗号处分割，避免把触发条件与操作拆开后扩大作用范围。 */
    private List<String> clauses(String text) {
        if (text == null || text.isBlank()) return List.of();
        return Pattern.compile("[。；;\\r\\n]+").splitAsStream(text).filter(value -> !value.isBlank()).toList();
    }

    /** 仅消除排版差异；保留否定、数字、比较符和业务对象供后续判断。 */
    private String normalize(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceFirst("^(?:[-*#]+\\s*|\\d+[.)、]\\s+)", "")
                .replaceAll("[\\s`*‘’“”\"，,。；;]", "")
                .replaceFirst("^(?:约束|要求|规则|注意|空值处理)[:：]", "");
    }

    private record QuantityRule(String skeleton, List<String> values, List<String> operators) { }
}
