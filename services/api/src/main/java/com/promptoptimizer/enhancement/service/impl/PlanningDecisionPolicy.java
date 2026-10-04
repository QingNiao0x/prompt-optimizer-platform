package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.PlanOption;
import com.promptoptimizer.enhancement.domain.PlanningFactOrigin;
import com.promptoptimizer.enhancement.domain.PlanningKnownDecision;
import com.promptoptimizer.enhancement.domain.PlanningKnownDecision.Kind;
import com.promptoptimizer.provider.domain.PlanningProviderRequest;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException.Reason;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 按作用对象、决定维度和来源建立 Plan 的已定信息索引。
 * 同时供提问模型和服务端过滤使用；不将资料存在、候选文字或缺失片段当作业务决定。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class PlanningDecisionPolicy {
    private record Source(String path, String text, PlanningFactOrigin origin) { }

    private static final Pattern SELECTED_FIELDS = Pattern.compile(
            "(?:再填|填充|使用|遵循)\\s*`?([A-Z][A-Z0-9_]{2,64})`?");
    private static final Pattern SUMMARY = Pattern.compile(
            "^\\[(PROJECT_SOURCE|PROJECT_DOCUMENT|USER_MATERIAL)]\\s+(.+?)[：:]([\\s\\S]*)$");
    private static final Pattern UNKNOWN = Pattern.compile("尚未|未确定|未明确|未定|待定|未知|还没决定|待确认|尚不明确");
    private static final Pattern FIELD_SCOPE = Pattern.compile("字段(?:集合|范围|清单)|哪些字段|哪部分字段|部分字段|其他字段|额外字段|整组|全部|所有|哪些项目|哪些信息|填充内容");
    private static final Pattern FILL_ACTION = Pattern.compile("自动填充|自动补齐|自动补全|填充基本信息|基本信息自动|填充内容");
    private static final Pattern INDEPENDENT_FIELD_RULE = Pattern.compile(
            "null|false|空值|空字符串|覆盖|脱敏|权限|校验|判断|格式|顺序|映射|合并|数据来源|来源优先|保留原值", Pattern.CASE_INSENSITIVE);
    private static final Pattern INDEPENDENT_FIELD_USE = Pattern.compile(
            "判断|校验|脱敏|权限|格式|映射|合并|数据来源|来源优先|源字段|源数据|返回值|详情.*为空|性能|指标|超时");
    private static final Pattern CHANGE_FIELD_SCOPE = Pattern.compile(
            "(?:调整|修改|更改|重新选择|重新确定|新增|缩减).{0,24}(?:填充字段|字段范围|字段集合|字段清单)|"
                    + "(?:字段范围|字段集合|填充字段).{0,24}(?:调整|修改|更改|重新选择|新增|缩减)");
    // 从完整中文片段或对象后缀后开始，不截取长修饰语的末尾冒充业务名；请求长度已由协议限制。
    private static final Pattern OTHER_SUBJECT = Pattern.compile(
            "(?:(?<![\\p{IsHan}])|(?<=表单)|(?<=模块)|(?<=接口)|(?<=流程)|(?<=场景)|(?<=任务))"
                    + "((?:(?!(?:表单|模块|接口|流程|场景|任务))[\\p{IsHan}]){2,})(?:表单|模块|接口|流程|场景|任务)");
    private static final Pattern TEST_METRIC = Pattern.compile("响应时间|吞吐量|并发数|并发量|覆盖率|毫秒|\\b\\d+(?:\\.\\d+)?ms\\b",
            Pattern.CASE_INSENSITIVE);
    private static final String CONFIRMATION_CONTENT = "(?:确认(?:信息)?内容|确认信息|信息量)";
    private static final Pattern MODULE_DECLARATION = Pattern.compile(
            "([\\p{IsHan}]{2,12})模块为\\s*([A-Za-z0-9_\\-与、\\s]+)[，,](?:均|都)调用\\s*([A-Za-z0-9_./-]+\\.(?:ts|tsx|js|jsx|vue|java))");

    /** 文档声明的模块映射必须对应已提供源码；只绑定本次修改范围，不宣称调用关系已验证。 */
    private record ModuleScope(String category, List<String> names, List<String> sources) {
        private ModuleScope {
            names = List.copyOf(names);
            sources = List.copyOf(sources);
        }
    }

    private final PlanningProviderRequest input;
    private final List<PlanningKnownDecision> decisions;
    private final ModuleScope moduleScope;
    private final boolean separateUnverifiableRegionStates;

    private PlanningDecisionPolicy(PlanningProviderRequest input) {
        this.input = input;
        this.moduleScope = declaredModuleScope(safe(input.rawPrompt()), sources(input));
        this.decisions = build(input);
        this.separateUnverifiableRegionStates = sources(input).stream().anyMatch(source -> normalize(source.text())
                .matches("(?s).*无法核验(?:的)?记录.*确无候选.*地区不符.*分别说明.*"))
                && !normalize(input.rawPrompt()).matches("(?s).*无法核验.{0,30}(?:按|视为|当作)查询失败.*");
    }

    /** 从同一份输入重建证据；调用方不能仅靠自填内部 metadata 使问题被过滤。 */
    static PlanningDecisionPolicy from(PlanningProviderRequest input) {
        return new PlanningDecisionPolicy(input);
    }

    /** 为模型附加有来源的已定信息，降低先生成无用问题再由服务端删除的概率。 */
    static PlanningProviderRequest enrich(PlanningProviderRequest input) {
        var policy = from(input);
        return new PlanningProviderRequest(input.rawPrompt(), input.contextDescription(), input.conversationHistory(),
                input.planningContext(), input.model(), policy.decisions);
    }

    /** 只消除相同决定维度；时延、缺失值、权限、迁移与验收阈值等新选择继续保留。 */
    boolean resolvedOrDelegated(PlanQuestion question) {
        String text = normalize(question.question());
        if (text.matches(".*(?:冲突|新增业务|数据库迁移|迁移数据库|新建索引).*")) return false;
        // 工程路径与测试指标是不同决定；所有候选均检查，不能用其他已定分支吞掉新性能条件。
        if (text.contains("测试") && (TEST_METRIC.matcher(text).find() || question.options().stream().anyMatch(option ->
                TEST_METRIC.matcher(option.label() + " " + option.description() + " " + option.answer()).find()))) return false;
        var symbols = Pattern.compile("\\b[A-Z][A-Z0-9_]{2,64}\\b").matcher(question.question());
        while (symbols.find()) if (!safe(input.rawPrompt()).contains(symbols.group())) return false;
        // 模块问句的通用动词不是业务名称；先核对完整声明和每个候选，再使用跨对象保护。
        if (hasDecision(Kind.DECLARED_MODULE_SCOPE) && declaredModuleSelection(question, text)) return true;
        // 已绑定的接口动作先解析作用范围，“先查询详情时若详情接口失败”不是另一个接口名。
        if (hasDecision(Kind.ERROR_UI_LOOKUP) && errorUi(text, question)) return true;
        if (hasDecision(Kind.ERROR_UI_LOOKUP) && existingErrorPresentation(question, text)) return true;
        if (mentionsDifferentSubject(text)) return false;
        for (PlanningKnownDecision decision : decisions) {
            boolean matched = switch (decision.kind()) {
                case FIELD_SCOPE -> fieldSelection(text, question, decision);
                case DECLARED_MODULE_SCOPE -> false;
                case MATCH_TRIGGER -> triggerSelection(text);
                case EXISTING_UI_TRIGGER_LOOKUP -> existingUiTrigger(text);
                case REGION_MATCH_SCOPE -> regionScopeSelection(text) || matchingPromptScopeSelection(question, text);
                case FILL_WRITE_SCOPE -> fillWriteScope(text);
                case DETAIL_BEFORE_FILL -> detailPrerequisite(text);
                case CANCEL_EFFECT -> cancelEffect(text);
                case RECORD_ORDER -> recordOrder(text);
                case MATCH_KEYS -> matchKeys(text);
                case NO_MATCH_UI -> noMatchUi(text);
                case ERROR_UI_LOOKUP -> errorUi(text, question);
                case RESEARCH_STATUS -> researchStatus(text);
                case PLAN_INTERACTION_DEFINITION -> planInteractionDefinition(question, text);
                case WRITING_SECTION_SCOPE -> methodSectionScope(text) || knownMethodOutlineContents(question, text);
                case REVIEWER_BLINDING -> reviewerVisibility(text);
                case METHOD_SELECTION -> methodSelection(text);
                case INFORMATION_PARITY -> informationParity(text);
                case EXISTING_REGION_LOOKUP -> regionImplementation(text) || regionAlgorithmChoice(question, text)
                        || regionLayerSelection(question, text);
                case TEST_LAYER_LOOKUP -> testLayerSelection(question, text) || existingBehaviorCoverage(question, text)
                        || mixedEngineeringCoverage(question, text);
                case TEST_TARGET_LOOKUP -> testTargetSelection(question, text);
            };
            if (matched) return true;
        }
        return false;
    }

    /** 字段集合与空值/权限等处理规则是不同维度；选项中的字段常量不能给无关题干背书。 */
    private boolean fieldSelection(String text, PlanQuestion question, PlanningKnownDecision decision) {
        if (INDEPENDENT_FIELD_RULE.matcher(text).find() || text.matches(".*(?:以及|和|与).*(?:规则|时机|来源|人群|地区|策略).*")) return false;
        // 题干泛问“其他字段”时，候选可能混入独立判断或校验；不能只凭题干清空该新决定。
        if (question.options().stream().anyMatch(option -> {
            String choice = normalize(option.label() + " " + option.description() + " " + option.answer());
            if (INDEPENDENT_FIELD_USE.matcher(choice).find()) return true;
            // 候选复述已确定的目标空值条件并非新用途；未确定该条件时仍保留其独立决定。
            return INDEPENDENT_FIELD_RULE.matcher(choice).find() && !hasDecision(Kind.FILL_WRITE_SCOPE);
        })) return false;
        boolean named = text.contains(normalize(decision.subject()));
        boolean sameAction = FILL_ACTION.matcher(text).find();
        // 明确引用另一个常量时不能复用本次选中的集合。
        var symbols = Pattern.compile("\\b[A-Z][A-Z0-9_]{2,64}\\b").matcher(question.question());
        while (symbols.find()) if (!normalize(symbols.group()).equals(normalize(decision.subject()))) return false;
        return (named || sameAction) && (FIELD_SCOPE.matcher(text).find() || text.contains("指哪些字段"));
    }

    /** 已定触发条件只回答触发本身，不能推断防抖、重试、延迟、失败处理或其他动作。 */
    private boolean triggerSelection(String text) {
        return FILL_ACTION.matcher(text).find()
                && text.matches(".*(?:触发时机|触发条件|何时|什么时候|什么时候触发|在哪个环节).*" )
                && !text.matches(".*(?:延迟|毫秒|防抖|重试|失败|超时|次数|频率|重新设计).*" );
    }

    /** 查询条件与提示业务已定后，默认沿用并核查工程事件，不为新按钮/失焦策略强迫用户做技术选择。 */
    private boolean existingUiTrigger(String text) {
        return FILL_ACTION.matcher(text).find() && text.contains("提示")
                && text.matches(".*(?:什么时机|什么时候|哪个环节|何时|触发时机|触发条件).*" )
                && !text.matches(".*(?:延迟|毫秒|防抖|重试|失败|超时|次数|频率|重新设计|缓存).*" );
    }

    /** 已定范围同时确定基准与成员边界；地区字段来源、空值和行政级别仍是独立条件。 */
    private boolean regionScopeSelection(String text) {
        return text.contains("地区")
                && text.matches(".*(?:地区范围|下级地区|同级地区|上级地区|其他地区).*" )
                && text.matches(".*(?:以什么为准|以谁为准|哪个为准|基准是什么|以哪个地区|包含|包括|排除|限于|限定|仅限).*" )
                && !text.matches(".*(?:来源|缺失|为空|空值|行政级别|具体层级|区县|省级|市级|临时|历史|权限|调整|迁移|算法|编码|数据表).*" );
    }

    /** 已定范围内有匹配才提示时，提示使用同一个匹配结果；来源、空地区与新反馈不由此推断。 */
    private boolean matchingPromptScopeSelection(PlanQuestion question, String text) {
        if (!hasDecision(Kind.MATCH_TRIGGER) || !text.contains("当前地区") || !text.contains("匹配")
                || !text.matches(".*(?:下级地区|本级).*" ) || !text.matches(".*(?:提示|弹窗).*" )
                || reopensExecutionRule(safe(input.rawPrompt()), "提示(?:触发)?范围|提示触发|触发条件")) return false;
        Pattern independent = Pattern.compile("来源|缺失|空值|为空|字段|权限|隐私|行政|层级|延迟|防抖|重试|次数|超时|新增|新建|迁移|时长|阈值|指标|成本|预算|反馈"
                + "|人群|年龄|性别|疾病|受众|逐字段|整批|样式|按钮|确认粒度|确认方式|角色|提示方式|交互方式");
        return !independent.matcher(text).find() && question.options().stream().noneMatch(option ->
                independent.matcher(normalize(option.label() + " " + option.description() + " " + option.answer())).find());
    }

    /** 字段集合与写入条件分别有证据时可合并确认；源数据为空、脱敏或映射仍是独立维度。 */
    private boolean fillWriteScope(String text) {
        if (!text.contains("填充") || text.matches(".*(?:源数据|源字段|返回值|详情.*为空|脱敏|权限|映射|格式|重试|来源|校验|提示|提醒|弹窗|反馈|禁用|缓存|超时|单位|时区|审计|日志|通知|计费|回滚).*")) return false;
        // 复合题同时问字段集合时，空值条件已知并不足以回答集合；声明冲突或缺席仍需确认。
        if (text.matches(".*(?:范围|集合|限定|哪些字段).*" )
                && decisions.stream().noneMatch(decision -> decision.kind() == Kind.FIELD_SCOPE)) return false;
        return text.matches(".*(?:当前为|当前值|目标字段当前是|已有值|已有非空|仅.*null|只.*null).*")
                && text.matches(".*(?:范围|限定|覆盖|保留|修改|写入|只处理|只填|视为可填充|如何处理|怎样处理|怎么处理|应如何处理).*" );
    }

    /** 详情是填充前提，失败时不能跳过；重试次数、超时和新的恢复策略仍需独立决定。 */
    private boolean detailPrerequisite(String text) {
        return text.contains("详情") && text.contains("失败") && text.matches(".*(?:填充|写入).*" )
                && !text.matches(".*(?:重试|次数|超时|缓存|恢复|旧数据|回滚|权限|脱敏|迁移|调整).*" );
    }

    /** 保留下来的新问题也不能提供违反已定前提的候选；交给现有 Provider 预算修复，不伪造成功。 */
    void validateCandidate(String candidate, String field) {
        String normalized = normalize(candidate);
        // 上传材料已要求分别说明这些状态时，不能借未决的候选处理策略把资料缺失伪装成网络查询失败。
        // 无此证据或用户明确重订状态策略的任务继续保留其原有选择空间。
        for (String clause : normalized.split("[。；;]")) {
            if (separateUnverifiableRegionStates && clause.matches(".*(?:地区.*(?:缺失|无法核验)|无法核验.*地区).*" )
                    && (positiveAction(clause, "(?:按|视为|当作)(?:整个|整体)?查询失败(?:处理)?")
                    || positiveAction(clause, "不(?:向用户)?(?:提示|说明)(?:存在)?(?:此类|无法核验|地区缺失)?(?:记录)?"))) {
                throw new ProviderResponseValidationException(Reason.RULE_CONFLICT, field);
            }
            if (hasDecision(Kind.FIELD_SCOPE) && !mentionsDifferentSubject(clause)
                    && positiveAction(clause, "(?:其他|额外|新增)(?:的)?字段(?:也|仍|均|都|全部)?(?:参与|用于|进行)?(?:自动)?填充"
                    + "|(?:填充|写入)(?:其他|额外|新增)(?:的)?字段")) {
                // 允许其他字段用于校验、排序等未知用途；禁止将这些用途借作扩充已定填充集合的理由。
                throw new ProviderResponseValidationException(Reason.RULE_CONFLICT, field);
            }
            if (hasDecision(Kind.REGION_MATCH_SCOPE)
                    && ((positiveAction(clause, "(?:忽略|跳过|去除|取消|不使用|不校验|不做|不进行|不执行|不限定|不限制)地区(?:范围)?(?:过滤)?(?:条件)?")
                    && clause.matches(".*(?:查询|匹配).*" ))
                    || positiveAction(clause, "(?:不附加|不添加|不带|不加)地区(?:范围)?(?:过滤)?条件|不带地区(?:范围)?查询(?!参数)")
                    || positiveAction(clause, "(?:以|使用|采用)(?:其他|另一个)(?:指定)?地区字段(?:作为|用作)(?:匹配|范围)基准")
                    || positiveAction(clause, "(?:以|使用|采用)(?:患者|病人)(?:基线)?(?:现住址|居住地区|现住地区).{0,8}(?:作为|用作)(?:匹配|范围|地区范围)基准"))) {
                throw new ProviderResponseValidationException(Reason.RULE_CONFLICT, field);
            }
            if (hasDecision(Kind.REGION_MATCH_SCOPE) && replacesRegionPredicateWithMatchKeys(clause)) {
                throw new ProviderResponseValidationException(Reason.RULE_CONFLICT, field);
            }
            if (hasDecision(Kind.DETAIL_BEFORE_FILL) && clause.contains("详情") && clause.contains("失败")
                    && positiveAction(clause, "(?:用|使用|采用)列表(?:返回的)?(?:地址)?摘要")
                    && clause.matches(".*(?:填充|写入).*" )) {
                throw new ProviderResponseValidationException(Reason.RULE_CONFLICT, field);
            }
            if (inheritsDetailFailureReminder(clause) && suppressesKnownErrorFeedback(clause)) {
                throw new ProviderResponseValidationException(Reason.RULE_CONFLICT, field);
            }
            if (hasDecision(Kind.REVIEWER_BLINDING) && disclosesReviewCondition(clause)) {
                throw new ProviderResponseValidationException(Reason.RULE_CONFLICT, field);
            }
            if (hasDecision(Kind.METHOD_SELECTION) && prematureMethodSelection(clause)) {
                throw new ProviderResponseValidationException(Reason.RULE_CONFLICT, field);
            }
            if (hasDecision(Kind.PLAN_INTERACTION_DEFINITION) && clause.contains("plan")
                    && positiveAction(clause, "(?:界定为|定义为|条件指|模式为).{0,60}(?:不向用户提问|不涉及用户交互|不涉及用户问答|无需用户回答|不与用户交互)")) {
                throw new ProviderResponseValidationException(Reason.RULE_CONFLICT, field);
            }
            if (hasDecision(Kind.INFORMATION_PARITY) && clause.matches(".*(?:公平对照|对照组|两组对照).*")) {
                boolean differentContent = positiveAction(clause,
                        CONFIRMATION_CONTENT + "(?:可以|允许|可|能够|均)?(?:不同|不一致|不相同|不等量)"
                                + "|(?:获得|获取|接收)(?:不同|不相同|不一致)确认(?:信息|内容)");
                boolean independentlyGenerated = positiveAction(clause,
                        CONFIRMATION_CONTENT + ".{0,8}(?:各自|分别|独立).{0,8}(?:生成|确定|获取)"
                                + "|(?:各自|分别|独立).{0,8}(?:生成|确定|获取).{0,8}确认(?:内容|信息)");
                boolean omitsInformation = positiveAction(clause,
                        "(?:不|未|无需)(?:获得|获取|接收)确认(?:信息|内容)|(?:省略|取消|去除)确认(?:信息|内容)");
                boolean noSupplement = positiveAction(clause,
                        "(?:不|无需)(?:额外|另外|另行|再)?(?:补充|提供|加入)确认(?:信息|内容)");
                // 信息可预先写入初始提示词而不再交互；仅用相同初始文本并省略信息并不等于公平对照。
                boolean alreadyIncluded = normalized.matches(".*已(?:包含|获得|提供|写入|接收).*" )
                        && sameConfirmationContent(normalized);
                // 实现方式可以不同，但必须保留同样信息；相同轮次不能替代确认内容一致。
                if (differentContent || omitsInformation || (noSupplement && !alreadyIncluded)
                        || (independentlyGenerated && !sameConfirmationContent(normalized))) {
                    throw new ProviderResponseValidationException(Reason.RULE_CONFLICT, field);
                }
            }
        }
    }

    /** 未来按分布选检验的任务只允许拟候选，不把提前定案当作等价方案；否定按动作邻近核对。 */
    private boolean prematureMethodSelection(String clause) {
        var preset = Pattern.compile("(?:预先|提前)(?:指定|选定|确定)(?:一个)?(?:主要|具体)检验").matcher(clause);
        while (preset.find()) {
            String prefix = clause.substring(Math.max(0, preset.start() - 16), preset.start());
            if (prefix.matches(".*(?:不|不得|禁止|不能|不允许|不可|不应)(?:在数据提供前)?$")) continue;
            // 候选方案仍须把实际采用方法的决定留给未来数据，不能只在别处出现“数据”就豁免。
            String tail = clause.substring(preset.end());
            if (tail.matches(".*(?:实际采用|最终检验|最终方法).{0,12}(?:根据|依据|按)未来数据分布.{0,8}(?:选择|确定|决定).*")) continue;
            return true;
        }
        return false;
    }

    /** 盲评只约束实验条件不可见；评审知道评分维度、任务文本或匿名编号不构成违约。 */
    private boolean disclosesReviewCondition(String clause) {
        if (!clause.matches(".*(?:评审|评委).*")) return false;
        var disclosure = Pattern.compile("(?:知道|知晓|获知|看到|知悉).{0,24}(?:实验条件|实验分组|分组信息|对应的实验模式)")
                .matcher(clause);
        while (disclosure.find()) {
            String prefix = clause.substring(Math.max(0, disclosure.start() - 12), disclosure.start());
            // 必须按动作邻近否定判断，不能把“不知道”或“禁止知道”当作正向披露。
            if (!prefix.matches(".*(?:不得|禁止|不能|不允许|不可|不应).*" )
                    && !prefix.matches(".*(?:不(?:会|曾|能|应)?|未(?:曾|能)?|无法)$")) return true;
        }
        return false;
    }

    /** 只识别明确的信息一致保证，不用相同轮次、预算或评分方法推定确认内容相同。 */
    private boolean sameConfirmationContent(String text) {
        return text.matches(".*" + CONFIRMATION_CONTENT + "(?:必须|应当|应|需|始终|完全|保持|保证|均|都|也|要|是|为)*(?:相同|一致|同样|等量).*" )
                || text.matches(".*(?:相同|一致|同样|等量)(?:的|全部|完整)?" + CONFIRMATION_CONTENT + ".*" );
    }

    /** 匹配键不能成为地区匹配的充分条件；分阶段查候选与继续满足地区过滤的方案仍然合法。 */
    private boolean replacesRegionPredicateWithMatchKeys(String clause) {
        if (mentionsDifferentSubject(subjectText(clause))) return false;
        boolean retainsRegion = positiveAction(clause,
                "(?:仍需|仍须|仍必须|仍要)(?:满足|符合|遵守|通过)当前用户(?:所属)?地区(?:范围)?(?:过滤)?(?:条件)?"
                        + "|由(?:服务端|后端)(?:仍|继续)?按当前用户(?:所属)?地区(?:范围)?过滤"
                        + "|仍(?:保留|遵守|满足|符合|校验)(?:当前用户(?:所属)?)?地区(?:范围)?(?:过滤)?条件"
                        + "|当前(?:用户所属)?地区(?:及其下级地区)?(?:有|存在)(?:匹配)?记录(?:时|后)");
        if (retainsRegion) return false;
        String keys = "姓名(?:和|与|及|、)身份证(?:号)?";
        boolean sufficientKeys = positiveAction(clause,
                "(?:只要|只需|仅需|仅凭|只凭|只按|仅按)" + keys + "(?:匹配|一致|相同)?(?:即|就|即可|便)?(?:可)?"
                        + "(?:视为当前地区有记录|(?:提示|进行|执行)(?:自动)?填充)");
        boolean missingRegionFallback = clause.matches(".*(?:地址|现住址|地区(?:字段|数据)?)(?:为空|缺失|无法解析).*" )
                && positiveAction(clause, "仍(?:按|仅按|只按)" + keys + "匹配(?:并)?(?:提示|自动填充)");
        return sufficientKeys || missingRegionFallback;
    }

    /** 否定危险动作的说明不是危险建议；按所在句判断，不能让另一句的“禁止”遮盖正向违约候选。 */
    private boolean positiveAction(String text, String expression) {
        var actions = Pattern.compile(expression).matcher(text);
        while (actions.find()) {
            String prefix = text.substring(Math.max(0, actions.start() - 12), actions.start());
            if (!prefix.matches(".*(?:不得|禁止|不能|不允许|不可|不应).*" )) return true;
        }
        return false;
    }

    /** 取消不写表单是已定效果；后端事务、通知或计费等不同副作用不能由此推断。 */
    private boolean cancelEffect(String text) {
        return text.contains("取消") && text.matches(".*(?:原值|表单|字段|填充).*" )
                // 测试题必须逐项核对所有候选；提到一个已知取消分支不表示其他覆盖要求也已确定。
                && !text.matches(".*(?:测试|验证|覆盖|后端|服务端|数据库|事务|回滚|通知|日志|埋点|计费|额度|请求|脱敏|权限).*" );
    }

    /** 日期和 ID 的降序已经定义并列取舍；空日期、ID 类型或日期解析仍需独立处理。 */
    private boolean recordOrder(String text) {
        return text.contains("调查日期") && text.contains("id")
                && text.matches(".*(?:多条|排序|降序|升序|相同|并列|最大|最小).*" )
                && !text.matches(".*(?:缺失|为空|空值|无效|解析|时区|格式|类型|字符串|字典序|新增|性能).*" );
    }

    /** 双键且缺项不查询已有明确条件；不为没有提出的新补全弹窗另开业务选择。 */
    private boolean matchKeys(String text) {
        return text.contains("姓名") && text.contains("身份证号") && text.contains("查询")
                && text.matches(".*(?:任一为空|任意为空|缺项|同时匹配|同时满足).*" )
                && !text.matches(".*(?:空格|格式|脱敏|校验|模糊匹配|部分匹配|缓存|重试|提示|提醒|弹窗|反馈|禁用).*" );
    }

    /** 只有“无匹配不弹窗”明确存在时才复用，不扩大为所有通知渠道均关闭。 */
    private boolean noMatchUi(String text) {
        return text.matches(".*(?:没有匹配|无匹配|未匹配|当前地区无记录|当前地区没有记录|只有其他地区有).*" )
                && text.contains("弹窗")
                && !text.matches(".*(?:邮件|通知管理员|短信|导出|缓存|重试|迁移|调整|扩大).*" );
    }

    /** 原有异常提醒且不阻断录入已定；未授权新补值分支不要求用户选择，重试/缓存等独立策略继续保留。 */
    private boolean errorUi(String text, PlanQuestion question) {
        if (text.contains("接口异常") && text.matches(".*(?:提醒|提示).*" )
                && text.matches(".*(?:手工录入|手动填写|手动录入|继续本次录入|继续录入|不阻断).*" )
                && !text.matches(".*(?:重试|缓存|旧数据|超时|部分成功|回滚|网络|权限|日志|丢失|次数|毫秒).*" )
                && !mentionsDifferentSubject(text)) return true;
        Pattern independent = Pattern.compile("重试|次数|超时|缓存|恢复|旧数据|回滚|权限|部分|缺失|日志|来源|次数|毫秒");
        return inheritsDetailFailureReminder(text) && text.matches(".*(?:如何|怎样|怎么|处理|提醒|提示).*" )
                && !independent.matcher(text).find()
                && question.options().stream().noneMatch(option -> independent.matcher(normalize(option.label()
                        + " " + option.description() + " " + option.answer())).find());
    }

    /** 只有同任务已定的详情前提和通用异常规则同时成立时才继承；限定列表或其他业务不扩展。 */
    private boolean inheritsDetailFailureReminder(String text) {
        if (!hasDecision(Kind.DETAIL_BEFORE_FILL) || !hasDecision(Kind.ERROR_UI_LOOKUP)
                || safe(input.rawPrompt()).matches("(?s).*(?:仅|只)(?:针对|处理|在)?列表(?:接口)?(?:异常|失败).*" )) return false;
        Pattern boundCall = Pattern.compile("(?:^|当|若|如果|[，,:：])(?:本次|先)?(?:查询|加载)?详情(?:查询|加载|接口|调用)*"
                + "(?:发生|出现|返回)?(?:失败|异常|错误)");
        return boundCall.matcher(text).find() && !mentionsDifferentSubject(boundCall.matcher(text).replaceAll(" "));
    }

    /** 不弹窗可以仍有轻提示；只拦截明确取消提醒或阻断录入，邻近禁令不作为危险建议。 */
    private boolean suppressesKnownErrorFeedback(String text) {
        boolean alternativeReminder = text.matches(".*(?:保留|沿用|给出|显示)(?:现有|统一)?(?:错误|异常|轻量|明确)?(?:提醒|提示).*" );
        var effects = Pattern.compile("不(?:再|额外|另行)?(?:提醒|提示)(?:用户)?|(?:阻断|禁止|禁用|停止)(?:继续)?手工录入")
                .matcher(text);
        while (effects.find()) {
            String prefix = text.substring(Math.max(0, effects.start() - 12), effects.start());
            if (prefix.matches(".*(?:不得|禁止|不能|不允许|不可|不应)$")) continue;
            if (!effects.group().contains("提示") && !effects.group().contains("提醒")) return true;
            if (!alternativeReminder) return true;
        }
        return false;
    }

    /** 已有工程的纯错误提示形态由执行方核查；新增消息渠道、参数和用户主动选择继续确认。 */
    private boolean existingErrorPresentation(PlanQuestion question, String text) {
        if (!text.matches(".*接口异常.*(?:提醒|提示).*(?:方式|形式|形态).*" ) || question.options().isEmpty()
                || !softwareChange(safe(input.rawPrompt()))
                || sources(input).stream().noneMatch(source -> source.origin() == PlanningFactOrigin.PROJECT_SOURCE)
                || reopensExecutionRule(safe(input.rawPrompt()), "异常提醒方式|异常提示方式|错误提示方式")) return false;
        Pattern independent = Pattern.compile("邮件|短信|通知管理员|日志|审计|重试|缓存|超时|权限|数据|来源|性能|费用|阈值|迁移|新增");
        if (independent.matcher(text).find() || mentionsDifferentSubject(text)) return false;
        return question.options().stream().allMatch(option -> {
            String choice = normalize(option.label() + " " + option.description() + " " + option.answer());
            return choice.matches(".*(?:页面内|弹窗|提示条|轻提示|toast|消息提示).*" )
                    && choice.matches(".*(?:手工录入|手动录入|不阻断|继续录入).*" )
                    && !independent.matcher(choice).find() && !mentionsDifferentSubject(optionSubjects(option));
        });
    }

    /** 拟定而未实施是已知研究状态；是否开展预实验或已有部分结果仍是不同问题。 */
    private boolean researchStatus(String text) {
        return text.matches(".*(?:是否需要|是否应|要不要).*(?:说明|写出|声明|明确).*" )
                && text.matches(".*(?:尚未实施|尚未执行|仅为设计|当前.*设计).*" )
                && !text.matches(".*(?:预实验|试测|部分完成|新增|调整|改变|结果数据).*" );
    }

    /** 交互问答已经是研究对象；轮次、终止、实现流程及评价指标仍需独立界定。 */
    private boolean planInteractionDefinition(PlanQuestion question, String text) {
        String details = "轮次|几轮|终止|问题数|数量|实现方式|具体流程|操作性|示例|深度|细节|最多|至少|至多|评价|评分|指标|预算|版本|随机|编码";
        return text.contains("plan") && text.matches(".*(?:界定|定义|指什么|是什么).*" )
                && !text.matches(".*(?:" + details + ").*" )
                && question.options().stream().noneMatch(option -> normalize(option.label() + " "
                        + option.description() + " " + option.answer()).matches(".*(?:" + details + ").*" ));
    }

    /** 沿用已明确的方法部分交付，不让补背景或整篇提纲成为默认必答；主动扩展时不建立该决定。 */
    private boolean methodSectionScope(String text) {
        return text.matches(".*(?:方法提纲|方法部分).*" )
                && text.matches(".*(?:是否|要不要|需不需要).*(?:包含|增加|补充).*(?:背景|意义|引言|文献综述|整篇).*" );
    }

    /** 仅分流按已定研究设计组织提纲的章节题；新的方法、数量、指标和交付重开不由此回答。 */
    private boolean knownMethodOutlineContents(PlanQuestion question, String text) {
        if (!text.matches(".*(?:方法提纲|方法部分).*(?:覆盖哪些内容|包含哪些内容|覆盖哪些部分).*" )
                || question.options().isEmpty()) return false;
        String raw = normalize(safe(input.rawPrompt()));
        Pattern newParameter = Pattern.compile("新增|重新|改为|调整|阈值|效应量(?:阈值|尺度|为)|具体采用|具体检验|具体指标|评分尺度|量表|随机|抽样|数据来源|任务来源|编码|时长|预算");
        if (newParameter.matcher(text).find()) return false;
        return question.options().stream().allMatch(option -> {
            String choice = normalize(option.label() + " " + option.description() + " " + option.answer());
            if (newParameter.matcher(choice).find()) return false;
            var numbers = Pattern.compile("\\d+(?:\\.\\d+)?").matcher(choice);
            while (numbers.find()) {
                if (!Pattern.compile("(?<!\\d)" + Pattern.quote(numbers.group()) + "(?!\\d)").matcher(raw).find()) return false;
            }
            // 候选只命名已规定设计的写作部分；没有据此选择具体评分值、检验或展示方式。
            return switch (normalize(option.label())) {
                case "研究设计" -> raw.contains("任务") && raw.contains("重复");
                case "实验条件" -> hasDecision(Kind.PLAN_INTERACTION_DEFINITION) && hasDecision(Kind.INFORMATION_PARITY);
                case "评审流程" -> hasDecision(Kind.REVIEWER_BLINDING);
                case "分析计划" -> hasDecision(Kind.METHOD_SELECTION);
                case "局限与边界" -> hasDecision(Kind.RESEARCH_STATUS) && choice.contains("尚未实施");
                default -> false;
            };
        });
    }

    /** 已经要求盲评就不再询问是否披露实验条件；匿名编码、顺序与评分流程仍是独立问题。 */
    private boolean reviewerVisibility(String text) {
        return text.matches(".*(?:评审|评委).*(?:知道|知晓|获知|看到|知悉).*(?:实验条件|实验分组|分组信息|对应的实验模式).*" )
                && !text.matches(".*(?:编码|顺序|随机|评分|一致性|流程|任务来源|如何实施|如何保证).*" );
    }

    /** 只复用已经写明的选择原则，不用“未来按分布选检验”回答具体检验、阈值或样本量。 */
    private boolean methodSelection(String text) {
        return text.matches(".*(?:是否需要|是否应|要不要).*(?:说明|描述|写明).*" )
                && text.contains("未来") && text.contains("数据") && text.contains("检验")
                && !text.matches(".*(?:t检验|卡方|方差分析|采用哪|具体检验|样本量|阈值|效应量|分布类型).*" );
    }

    /** 同样确认信息的公平对照已定；随机化、顺序、资源预算及评价口径继续保留。 */
    private boolean informationParity(String text) {
        return text.matches(".*(?:是否需要|是否应|要不要).*(?:说明|描述|写明).*" )
                && text.contains("公平对照")
                && !text.matches(".*(?:随机|顺序|交叉|分配|预算|资源|评分|评价|盲评|样本量).*" );
    }

    /** 已定地区边界的编码/父级链实现交给工程核查；扩大范围、索引变更等新决定不能顺带消除。 */
    private boolean regionImplementation(String text) {
        boolean implementation = text.matches(".*(?:具体如何判定|如何实现|怎么实现|实现方式|判定方式|编码前缀|父级id|递归查询).*" );
        boolean responsibility = text.matches(".*(?:服务端|后端|前端).*(?:过滤|判断).*" )
                && text.matches(".*(?:负责|职责|为准|结果|由谁|哪一层).*" );
        return text.contains("地区") && (implementation || responsibility)
                && !text.matches(".*(?:是否包含|是否排除|调整范围|更换|迁移|新增|性能|耗时|安全|权限|缺失|空值|来源|数据表).*" );
    }

    /** 依据候选实际比较的算法判断核查职责，不要求题干必须使用“实现方式”等固定措辞。 */
    private boolean regionAlgorithmChoice(PlanQuestion question, String text) {
        if (!text.contains("地区") || question.options().isEmpty()
                || text.matches(".*(?:来源|字段|缺失|为空|空值|行政级别|具体层级|调整范围|迁移|性能|耗时|权限|预算).*")) return false;
        return question.options().stream().allMatch(option -> {
            String choice = normalize(option.label() + " " + option.answer());
            String changes = choice.replaceAll("(?:不|不得|禁止)(?:新增|新建|更改)", "");
            return choice.matches(".*(?:编码前缀|行政区划编码|父级关系|地区表|递归|沿用现有|现有工程).*" )
                    && !changes.matches(".*(?:新增|新建|迁移|缺失|空值|性能|耗时|权限|缓存|阈值|预算).*" );
        });
    }

    /** 既定地区业务由执行 Agent 核查实际改动层；新增业务、权限及用户主动选层不能被委托。 */
    private boolean regionLayerSelection(PlanQuestion question, String text) {
        if (!text.contains("前端") || !text.matches(".*(?:服务端|后端).*" )
                || !text.matches(".*(?:修改|改动|涉及).*" ) || question.options().isEmpty()
                || reopensExecutionRule(safe(input.rawPrompt()), "前后端修改范围|修改层|改动层|前后端职责")
                || safe(input.rawPrompt()).matches("(?s).*(?:由用户|由我|用户|我).{0,6}(?:选择|决定).{0,12}(?:前后端修改范围|修改层|改动层|前后端职责).*")) return false;
        Pattern independent = Pattern.compile("新增|新建|迁移|权限|租户|隐私|性能|预算|成本|阈值|指标|字段|来源|空值|缺失|重试|超时|调整范围|扩大范围|更换");
        if (independent.matcher(text).find()) return false;
        return question.options().stream().allMatch(option -> {
            String choice = normalize(option.label() + " " + option.description() + " " + option.answer());
            return normalize(option.label()).matches("(?:仅|只)(?:前端|后端|服务端)(?:改动|修改|变更)?|前后端(?:均|都)?(?:改|改动|修改|变更)|同时修改前后端")
                    && choice.contains("地区") && choice.contains("过滤") && !independent.matcher(choice).find()
                    && !mentionsDifferentSubject(choice);
        });
    }

    /** 混合覆盖题逐项辨认工程层、既定分支与已声明影响路径；一个新决定就保留整题。 */
    private boolean mixedEngineeringCoverage(PlanQuestion question, String text) {
        if (!safe(input.rawPrompt()).contains("不扩展业务范围") || !text.contains("测试") || !text.contains("覆盖")
                || question.options().isEmpty()) return false;
        Pattern newDecision = Pattern.compile("新增|新的|新建|迁移|隐私|权限|指标|阈值|成本|性能|时长|工具|数据集|测试数据|覆盖率|缓存|重试|超时|源字段|源数据|来源|校验|格式|回滚|缺项.*(?:提示|反馈)");
        if (newDecision.matcher(text).find()) return false;
        return question.options().stream().allMatch(option -> {
            String choice = normalize(option.label() + " " + option.description() + " " + option.answer());
            if (newDecision.matcher(choice).find()
                    || !engineeringCoverageScope(option, choice)) return false;
            boolean layer = choice.matches(".*(?:单元测试|集成测试|端到端测试|接口层|界面层|组件层|交互测试|组件测试|e2e).*" );
            boolean target = choice.contains("模块") && choice.matches(".*(?:测试|覆盖).*" );
            return layer || target || knownBehaviorCoverage(option.answer());
        });
    }

    /** 工程层是分类而非业务名字；只清除该类选项中的通用动作，具体退款等对象继续核对。 */
    private boolean engineeringCoverageScope(com.promptoptimizer.enhancement.domain.PlanOption option, String choice) {
        if (!mentionsDifferentSubject(optionSubjects(option)) || declaredTestTarget(option, choice)) return true;
        if (choice.contains("模块") || !normalize(option.label()).matches("(?:接口层|界面层|组件层|单元测试|集成测试|端到端测试)")) return false;
        String scoped = choice.replace("接口层", "接口").replace("界面层", "界面").replace("组件层", "组件")
                .replaceAll("测试|覆盖|查询|匹配|详情|获取|多条记录|排序|选取|逻辑|等", "")
                .replace("接口", "接口 ");
        return !mentionsDifferentSubject(scoped);
    }

    /** 已要求补测试时，已定业务分支必须覆盖；只有每个候选都能逐条对应原始规则才消除确认题。 */
    private boolean existingBehaviorCoverage(PlanQuestion question, String text) {
        if (!safe(input.rawPrompt()).contains("不扩展业务范围") || !text.contains("测试") || !text.contains("覆盖")
                || text.matches(".*(?:新增|隐私|安全|权限|指标|阈值|成本|性能|时长|工具|数据集|测试数据|百分比|覆盖率).*" )
                || question.options().isEmpty()) return false;
        // 不用整句命中一条旧规则来覆盖其他条件：复合候选逐句判断，任何新条件都保留原问题。
        return question.options().stream().allMatch(option -> knownBehaviorCoverage(option.answer()));
    }

    /** 按独立语句核对，保留同句中先给条件、再列效果的表达，不将后半句拆成无条件要求。 */
    private boolean knownBehaviorCoverage(String answer) {
        // 对象识别先保留原有标点，不能把前一个分支的效果拼到后一个接口名上。
        String[] clauses = safe(answer).split("[。;；]|以及|并且");
        boolean matched = false;
        for (String clause : clauses) {
            if (clause.isBlank()) continue;
            if (!knownBehaviorClause(clause)) return false;
            matched = true;
        }
        return matched;
    }

    /** 只识别已有条件与效果的复述，不把测试候选中的新效果或新对象当成原始要求。 */
    private boolean knownBehaviorClause(String clause) {
        String text = normalize(clause);
        if (mentionsDifferentSubject(subjectText(clause))
                || text.matches(".*(?:新增|新建|创建|删除|清空|发送|通知|额外|脱敏|权限|映射|格式|缓存|重试|超时|回滚|日志|数据来源|性能|毫秒|工具|测试数据|源字段|源数据).*" )) return false;
        // 缺项不查询并没有定义提示行为；不能把新弹窗藏在一个已知查询条件后吞掉。
        if (hasUnknownMissingFeedback(text)) return false;
        Set<Kind> required = new LinkedHashSet<>();
        // 同一候选可同时引用触发、空值写入和取消；每个维度必须已定，不能只命中一句就吞掉其他缺口。
        if (text.contains("取消")) required.add(Kind.CANCEL_EFFECT);
        if (text.contains("调查日期") && text.contains("id")) required.add(Kind.RECORD_ORDER);
        if (text.contains("接口异常")) required.add(Kind.ERROR_UI_LOOKUP);
        if (text.matches(".*(?:无匹配|未匹配|当前地区无记录|当前地区没有记录).*" )) required.add(Kind.NO_MATCH_UI);
        if ((text.contains("缺项") && text.contains("查询"))
                || text.matches(".*姓名(?:和|与)身份证号同时匹配.*")) required.add(Kind.MATCH_KEYS);
        if (text.matches(".*(?:null|空字符串|保留0和false|0和false保留).*" )) required.add(Kind.FILL_WRITE_SCOPE);
        if (text.matches(".*当前地区有(?:匹配)?记录.*(?:提示|确认|填充).*" )) required.add(Kind.MATCH_TRIGGER);
        if (text.contains("详情") && text.contains("填充")) required.add(Kind.DETAIL_BEFORE_FILL);
        if (text.matches(".*(?:仅其他地区有|只有其他地区有).*不得匹配.*" )
                || text.matches(".*当前地区(?:及其|及|与|和)下级地区(?:可|允许)?匹配.*")
                || text.matches(".*其他同级地区(?:不|不可|不得)匹配.*")
                || text.matches(".*包含下级地区.*排除其他同级地区.*")) required.add(Kind.REGION_MATCH_SCOPE);
        var symbols = Pattern.compile("\\b[A-Z][A-Z0-9_]{2,64}\\b").matcher(text.toUpperCase(Locale.ROOT));
        while (symbols.find()) {
            String symbol = symbols.group();
            if (symbol.endsWith("FIELDS")) {
                if (!safe(input.rawPrompt()).contains(symbol)) return false;
                required.add(Kind.FIELD_SCOPE);
            }
        }
        return !required.isEmpty() && required.stream().allMatch(this::hasDecision);
    }

    /** 缺项不查询未确定反馈；仅排除明确属于另一已定分支的 UI 表述，未带条件的提示继续保留。 */
    private boolean hasUnknownMissingFeedback(String text) {
        if (!text.contains("缺项")) return false;
        for (String branch : text.split("[,、;。]")) {
            if (!branch.matches(".*(?:弹窗|提示|提醒).*" )) continue;
            if (branch.contains("缺项")) return true;
            boolean noMatch = hasDecision(Kind.NO_MATCH_UI)
                    && branch.matches(".*(?:无匹配|未匹配|当前地区无记录).*不弹窗.*" );
            boolean error = hasDecision(Kind.ERROR_UI_LOOKUP) && branch.contains("接口异常")
                    && branch.contains("提醒") && branch.contains("不阻断");
            boolean matched = hasDecision(Kind.MATCH_TRIGGER)
                    && branch.matches(".*当前地区有(?:匹配)?记录.*提示.*" );
            if (!noMatch && !error && !matched) return true;
        }
        return false;
    }

    /** 所有分支均从同一份请求证据建立，不能使用候选答案自证业务已定。 */
    private boolean hasDecision(Kind kind) {
        return decisions.stream().anyMatch(decision -> decision.kind() == kind);
    }

    /** 只有选项均为现有工程测试层时才委托；覆盖边界、测试数据和验收标准不是测试层。 */
    private boolean testLayerSelection(PlanQuestion question, String text) {
        if (!text.contains("测试") || !text.matches(".*(?:范围|哪一层|哪些层|测试层|层级|层次|类型|哪种测试|什么测试).*" )
                || text.matches(".*(?:数据|验收|指标|标准|阈值|成本|性能|时长|工具).*" )
                || question.options().isEmpty()) return false;
        return question.options().stream().allMatch(option -> {
            String candidate = normalize(option.label() + " " + option.description() + " " + option.answer());
            // 测试层可以换名称，但描述中的新业务或新参数不能借一个熟悉的标签被删除。
            return candidate.matches(".*(?:单元测试|集成测试|端到端测试|接口测试|交互测试|组件测试|界面测试|页面测试|ui测试|e2e).*" )
                    && !candidate.matches(".*(?:新增|生产环境|迁移|脱敏|权限|隐私|阈值|性能|缓存|重试|测试数据|负载|预算|指标).*" )
                    && !mentionsDifferentSubject(optionSubjects(option));
        });
    }

    /** 标签、说明和答案保留词句边界，不把上一项文字拼成业务名称；具体新对象仍须核对。 */
    private String optionSubjects(PlanOption option) {
        return subjectText(option.label() + " " + option.description() + " " + option.answer());
    }

    /** 对象名称保留分支边界；判断动作和条件时继续使用独立的规范化文本。 */
    private String subjectText(String text) {
        return Normalizer.normalize(safe(text), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("[、，,。；;]", " ")
                .replaceAll("等(?:相关)?(?:接口|模块|流程)(?:行为|逻辑|调用)", " ");
    }

    /** 测试本次受影响路径是交付职责；新增模块、指标、成本和用户主动选择的覆盖范围仍需确认。 */
    private boolean testTargetSelection(PlanQuestion question, String text) {
        if (!safe(input.rawPrompt()).contains("不扩展业务范围") || !text.contains("测试")
                || !text.matches(".*(?:范围|边界|覆盖|路径|哪些对象).*" ) || question.options().isEmpty()) return false;
        Pattern independent = Pattern.compile("新增|新的|新建|迁移|权限|隐私|性能|负载|预算|成本|阈值|指标|测试数据|缓存|重试|超时"
                + "|字段来源|源数据|源字段|缺失|时区|单位|数据表|脱敏|审计|日志|通知|计费|测试工具");
        if (independent.matcher(text).find()) return false;
        return question.options().stream().allMatch(option -> {
            String target = normalize(option.label() + " " + option.description() + " " + option.answer());
            return target.matches(".*(?:测试|覆盖).*" )
                    && target.matches(".*(?:模块|页面|接口|路径|逻辑|流程|测试工程|修改范围|覆盖边界).*" )
                    && !independent.matcher(target).find()
                    && (!mentionsDifferentSubject(optionSubjects(option)) || declaredTestTarget(option, target));
        });
    }

    /** 用材料中明确的调用关系核对模块别名；只说明需要核查的影响路径，不声称其实现已验证。 */
    private boolean declaredTestTarget(com.promptoptimizer.enhancement.domain.PlanOption option, String target) {
        List<Source> provided = sources(input);
        for (Source source : provided) {
            if (source.origin() != PlanningFactOrigin.PROJECT_DOCUMENT) continue;
            var declared = MODULE_DECLARATION.matcher(source.text());
            while (declared.find()) {
                String category = declared.group(1).replaceFirst("^(?:两个|多个|本次|现有)", "");
                String referencedFile = declared.group(3);
                if (!safe(input.rawPrompt()).contains(category) || provided.stream().noneMatch(code ->
                        code.origin() == PlanningFactOrigin.PROJECT_SOURCE
                                && code.path().replaceFirst("#chunk-\\d+$", "").endsWith(referencedFile))) continue;
                var names = Pattern.compile("[A-Za-z][A-Za-z0-9_-]*").matcher(declared.group(2));
                while (names.find()) {
                    Pattern named = Pattern.compile("(?<![a-z0-9_-])" + Pattern.quote(names.group().toLowerCase(Locale.ROOT))
                            + "(?![a-z0-9_-])");
                    if (!named.matcher(normalize(option.label() + " " + option.answer())).find()) continue;
                    // 中文说明可使用同类别别名；退款等其他类别仍交回原有跨对象检查，不顺带删除新范围。
                    String scoped = target.replaceAll("[\\p{IsHan}]{0,12}" + Pattern.quote(category) + "模块", " ");
                    // 已声明名字对应的前后端分类不是另一业务；带退款等具体名字的模块不能被该替换吞掉。
                    scoped = scoped.replaceAll("(?:前端|后端|服务端|客户端)(?:两个)?模块|两个(?:前端|后端|服务端|客户端)模块", " ");
                    if (!mentionsDifferentSubject(scoped)) return true;
                }
            }
        }
        return false;
    }

    /** 只消除选择已声明模块子集的题目；任何额外行为、权限或未声明对象均保持可问。 */
    private boolean declaredModuleSelection(PlanQuestion question, String text) {
        if (moduleScope == null || !text.contains("模块")
                || !(FILL_ACTION.matcher(text).find() || text.matches(".*(?:修改|改动|实现|覆盖|哪些|范围).*"))
                || text.matches(".*(?:新增|迁移|权限|隐私|成本|指标|性能|字段|来源|格式|反馈|重试|超时).*")) return false;
        if (question.options().isEmpty()) return false;
        return question.options().stream().allMatch(option -> {
            String target = normalize(option.label() + " " + option.description() + " " + option.answer());
            if (moduleScope.names().stream().noneMatch(target::contains)) return false;
            for (String name : moduleScope.names()) target = target.replace(name.toLowerCase(Locale.ROOT), " ");
            target = target.replace(moduleScope.category(), " ")
                    .replaceAll("按同一规则|不扩展|补测试|本次|两个|都|均|仅|只|在|模块|实现|支持|修改|改动|涉及|覆盖|基线匹配|自动填充|其他|需要|同步|除|外|还|这|有|与|和|及|并", "");
            // 白名单只留下范围句法；不能借命中一个已知模块吞掉退款、审计或其他新业务。
            if (target.matches("[\\p{Punct}\\p{IsPunctuation}\\s]*")) return true;
            // 英文模块的中文业务别名只从本次主要目标取证，不从附件其他章节或候选中推断。
            String goal = taskScope();
            return Arrays.stream(target.split("[\\p{Punct}\\p{IsPunctuation}\\s]+"))
                    .filter(value -> !value.isBlank())
                    .allMatch(value -> value.matches("[\\p{IsHan}]+") && goal.contains(value));
        });
    }

    /** 两个业务目标在本次文本中已同时指定，且文档映射与源码一致时才绑定；附件不能自行扩大范围。 */
    private static ModuleScope declaredModuleScope(String raw, List<Source> provided) {
        if (!raw.contains("不扩展业务范围") || !softwareChange(raw)
                || reopensExecutionRule(raw, "模块范围|模块选择|修改范围")) return null;
        String goal = raw.split("[。；;\\n]", 2)[0];
        Set<List<String>> variants = new LinkedHashSet<>();
        Set<String> evidenceSources = new LinkedHashSet<>(List.of("rawPrompt"));
        Set<String> categories = new LinkedHashSet<>();
        for (Source source : provided) {
            if (source.origin() != PlanningFactOrigin.PROJECT_DOCUMENT) continue;
            var declaration = MODULE_DECLARATION.matcher(source.text());
            while (declaration.find()) {
                String category = declaration.group(1).replaceFirst("^(?:两个|多个|本次|现有)", "");
                if (!goal.matches("(?s).*[\\p{IsHan}]{2,32}(?:和|与|及)[\\p{IsHan}]{2,24}"
                        + Pattern.quote(category) + ".*")) continue;
                String referenced = declaration.group(3);
                var codeSource = provided.stream().filter(code -> code.origin() == PlanningFactOrigin.PROJECT_SOURCE
                        && code.path().replaceFirst("#chunk-\\d+$", "").endsWith(referenced)).findFirst();
                if (codeSource.isEmpty()) continue;
                Set<String> names = new LinkedHashSet<>();
                var name = Pattern.compile("[A-Za-z][A-Za-z0-9_-]*").matcher(declaration.group(2));
                while (name.find()) names.add(name.group().toLowerCase(Locale.ROOT));
                if (names.size() != 2) continue;
                variants.add(names.stream().sorted().toList());
                categories.add(category);
                evidenceSources.add(source.path());
                evidenceSources.add(codeSource.get().path());
            }
        }
        if (variants.size() != 1 || categories.size() != 1) return null;
        return new ModuleScope(categories.iterator().next(), variants.iterator().next(), List.copyOf(evidenceSources));
    }

    /** 跨模块题干宁可保留；不能因当前任务已有某个字段常量就回答退款等其他对象。 */
    private boolean mentionsDifferentSubject(String question) {
        String raw = taskScope();
        // “知道每个任务的条件”是对当前任务逐项描述，不能把前面的动词误当成另一任务名。
        // 有具体名字的“每个退款任务”等仍保留，继续按真实对象判断。
        String scopedQuestion = question.replace("每个任务", " ").replace("每项任务", " ")
                // “本次改动涉及的全部受影响模块”是通用工程指代；紧邻具体业务名时仍保留该名字。
                .replaceAll("(?:改动|修改)(?:涉及|影响)?(?:的)?(?:全部|所有)?(?=受影响(?:模块|页面|接口|流程|任务))", " ")
                // “详情逻辑不覆盖两个模块”中的前文不是模块名字；只在泛指对象前建立语法边界。
                .replaceAll("(?:覆盖|验证|测试|匹配|填充|查询|调用)(?=(?:两个|一个|各个|相关|受影响|涉及)(?:模块|页面|接口|任务))", " ")
                // 枚举中的“无匹配与接口异常”仍是既定分支，不能把连接词和上一项拼成接口名。
                .replaceAll("(?:以及|并且|与|和|及)(?=接口(?:异常|失败|错误))", " ")
                // 问句动词先形成词边界，避免长中文片段把“是否还需要覆盖”误识别成模块名称。
                .replaceAll("(?:是否|能否)(?:还|也|再)?(?:需要|应该|应当|必须)?(?:同时)?(?:覆盖|验证|测试|包含|包括|支持|使用|修改|填充)?", " ")
                .replaceAll("(?:哪些|哪个|什么)(?:模块|页面|接口|任务)", " ");
        var matcher = OTHER_SUBJECT.matcher(scopedQuestion);
        while (matcher.find()) {
            // “是否保持表单”中的“是否保持”是动作，不是另一个模块名；“保持退款表单”仍保留“退款”。
            String subject = matcher.group(1).replaceFirst(
                    "^(?:(?:是否|否|能否|应该|应当|需要|必须|如何|怎样|保持|保留|原有|已有|所有|全部|完整|全程|当前|本次|用户|实际|操作|查询|匹配|确认|取消|自动填充|检查|进行|选择|覆盖|验证|补充|补|测试|两个|一个|各个|相关|受影响|涉及|前后端|前端|后端|服务端|客户端|页面|界面|触发|填充|路径|逻辑|核心|其中|另|都|均|与|和|及|仅|只|不|对|从|到|在|时|的))+", "");
            // 已声明的动作可作场景修饰语；仅从本次主要目标取证，不能清除新的退款等对象。
            if (raw.contains("基线匹配")) {
                subject = subject.replaceFirst("^(?:基线匹配)(?:的|填充|与|和|及|取消)*", "");
            }
            // 已定的“先查详情再填充”可修饰流程；移除动作后仍核对退款等具体对象。
            if (hasDecision(Kind.DETAIL_BEFORE_FILL)) {
                subject = subject.replaceFirst("^先查(?:询)?详情再(?:自动)?填充", "");
            }
            if (subject.isBlank()) continue;
            // 并列的已定失败分支不是业务名；仅识别完整分支描述，不豁免“退款异常”等新对象。
            if (matcher.group().endsWith("场景") && hasDecision(Kind.NO_MATCH_UI) && hasDecision(Kind.ERROR_UI_LOOKUP)
                    && subject.matches("无匹配(?:和|与|及)(?:接口)?异常|(?:接口)?异常(?:和|与|及)无匹配")) continue;
            if (!raw.contains(subject) && !subject.matches("本次|当前|现有|自动填充|基线匹配|基本信息")) return true;
        }
        return false;
    }

    /** 以主要修改目标确定作用对象；附件或后续另一个模块的名称出现，不足以让它继承当前字段规则。 */
    private String taskScope() {
        String[] clauses = safe(input.rawPrompt()).split("[。；;\\n]");
        for (String clause : clauses) if (softwareChange(clause)) return normalize(clause);
        return normalize(clauses.length == 0 ? input.rawPrompt() : clauses[0]);
    }

    /** 建立小型、不可变决定清单；不引入额外模型调用，也不读取额外文件。 */
    private static List<PlanningKnownDecision> build(PlanningProviderRequest input) {
        String raw = safe(input.rawPrompt());
        List<Source> sources = sources(input);
        List<PlanningKnownDecision> decisions = new ArrayList<>();
        collectFieldScopes(raw, sources, decisions);
        collectTrigger(raw, decisions);
        collectExecutionRules(raw, decisions);
        collectResearchRules(raw, decisions);
        if (softwareChange(raw) && sources.stream().anyMatch(source -> source.origin() == PlanningFactOrigin.PROJECT_SOURCE)) {
            ModuleScope modules = declaredModuleScope(raw, sources);
            if (modules != null) {
                decisions.add(new PlanningKnownDecision(Kind.DECLARED_MODULE_SCOPE,
                        modules.category() + "模块范围：" + String.join("、", modules.names()),
                        "本次文本已同时要求这两个业务目标；材料声明其模块映射且对应源码已提供。执行 Agent 核查影响路径，"
                                + "不得把已指定的范围缩减为仅一个模块；不把文档声明冒充已验证实现，新增对象仍需确认。", modules.sources()));
            }
            collectRegionLookup(raw, sources, decisions);
            if (raw.contains("不扩展业务范围")
                    && decisions.stream().anyMatch(decision -> decision.kind() == Kind.MATCH_TRIGGER)
                    && !reopensExecutionRule(raw, "提示触发|触发时机|触发条件")) {
                decisions.add(new PlanningKnownDecision(Kind.EXISTING_UI_TRIGGER_LOOKUP, "已有界面触发事件",
                        "匹配条件与匹配后的提示业务已定。本次不扩展业务范围，执行 Agent 应核查并沿用现有界面触发事件；"
                                + "不能将查不到事件写成已实现，也不能自行新增按钮或防抖参数。", List.of("rawPrompt", "planningContext.PROJECT_SOURCE")));
            }
            if (raw.matches("(?s).*(?:按|以|基于)当前用户所属地区匹配.*包含下级地区.*排除其他同级地区.*")
                    && !reopensExecutionRule(raw, "地区范围|地区基准|范围依据")) {
                decisions.add(new PlanningKnownDecision(Kind.REGION_MATCH_SCOPE, "当前用户地区的匹配范围",
                        "原始需求已明确按当前用户所属地区匹配患者基线，范围为当前地区及其下级地区，其他范围不参与匹配；"
                                + "患者基线现住址是被筛选对象，不能与范围基准作为两个互斥方案。地区字段来源、空值和行政层级仍需独立核查。"
                                + "地区数据缺失的候选仍须保留地区过滤前提，不能仅凭姓名与身份证号判为当前地区有记录；"
                                + "不预设缺失值应剔除或补全，只澄清真正未决的处理方式。",
                        List.of("rawPrompt")));
            }
            if (raw.matches("(?s).*(?:补|补充|完善|编写).{0,6}测试.*")
                    && !raw.matches("(?s).*(?:测试策略|测试方案|测试层|测试成本|选择测试|测试方法|测试设计|覆盖范围).*(?:决定|选择|未定|尚未|设计).*")
                    && !raw.matches("(?s).*(?:用户|我).{0,12}(?:选择|决定).{0,16}(?:测试|测试层).*")) {
                decisions.add(new PlanningKnownDecision(Kind.TEST_LAYER_LOOKUP, "工程测试层",
                        "本次是软件改动并补测试；执行 Agent 应核查现有测试工程，按实际修改范围选择测试层。"
                                + "原始需求已要求的正常、异常与边界分支必须验证，不应重新列成可省略的选项；新覆盖要求与验收阈值不得自行补造。",
                        List.of("rawPrompt", "planningContext.PROJECT_SOURCE")));
                decisions.add(new PlanningKnownDecision(Kind.TEST_TARGET_LOOKUP, "本次改动影响的测试路径",
                        "执行 Agent 应核查实际修改及调用关系，验证本次受影响的模块与接口，不把所选文件一律当成业务范围。"
                                + "补测试不是允许仅挑一个受影响模块而省略其他模块；不能再次把这项交付职责要求用户选择。"
                                + "同样不得以验收范围、核心逻辑与界面路径的取舍重开这项职责。"
                                + "新增模块、性能指标、成本和用户主动要求决定的覆盖范围仍需确认。",
                        List.of("rawPrompt", "planningContext.PROJECT_SOURCE")));
            }
        }
        return List.copyOf(decisions);
    }

    /** 保留原句的条件和效果；只从本次明确软件需求建立索引，不从缺席代码或候选项推断规则。 */
    private static void collectExecutionRules(String raw, List<PlanningKnownDecision> target) {
        if (!softwareChange(raw)) return;
        for (String clause : raw.split("[。；;\\n]")) {
            if (UNKNOWN.matcher(clause).find() || clause.matches(".*[？?].*")) continue;
            String text = normalize(clause);
            if (text.matches(".*(?:只填|仅填).*(?:null|空字符串).*") && !reopensExecutionRule(raw, "空值|覆盖|写入条件|填充条件")) {
                // 这是“只填空值”的逻辑含义，不是推测新增业务：已有非空值也不能再次成为覆盖选项。
                addRule(target, Kind.FILL_WRITE_SCOPE, "当前表单的填充写入条件",
                        clause + "。只填 null 或空字符串意味着所有已有非空值保持不变；保留 0 和 false 不是允许覆盖其他非空值。");
            }
            if (text.matches(".*先查询详情.*再填.*") && raw.contains("不扩展业务范围")
                    && !reopensExecutionRule(raw, "详情失败|详情查询|填充前提")) {
                addRule(target, Kind.DETAIL_BEFORE_FILL, "先取得详情再填充", clause);
            }
            if (text.matches(".*取消.*(?:保持|保留)原值.*") && !reopensExecutionRule(raw, "取消|取消行为")) {
                addRule(target, Kind.CANCEL_EFFECT, "取消自动填充的表单效果", clause);
            }
            if (text.matches(".*多条.*调查日期降序.*id降序.*(?:选一条|取第一条).*") && !reopensExecutionRule(raw, "排序|选取|取舍")) {
                addRule(target, Kind.RECORD_ORDER, "匹配记录排序与选取", clause);
            }
            if (text.contains("姓名和身份证号同时匹配") && text.contains("缺项时不查询")
                    && raw.contains("不扩展业务范围")) {
                addRule(target, Kind.MATCH_KEYS, "缺项时的匹配查询条件", clause);
            }
            if (text.contains("无匹配不弹窗")) {
                addRule(target, Kind.NO_MATCH_UI, "无匹配的弹窗边界", clause);
            }
            if (text.contains("接口异常提醒但不阻断手工录入") && raw.contains("不扩展业务范围")
                    && !reopensExecutionRule(raw, "异常处理|异常交互|异常行为|是否阻断")) {
                target.add(new PlanningKnownDecision(Kind.ERROR_UI_LOOKUP, "已有异常交互",
                        clause.trim() + "。该规则作用范围内的接口调用继承提醒及继续录入要求，不能将静默失败作为可选方案。"
                                + "未要求新增异常补值分支，执行 Agent 应核查现有异常流程，不自行增加缓存填充等业务规则；"
                                + "若需确认重试等新策略，只询问该独立策略，不重新选择已经明确的异常效果。",
                        List.of("rawPrompt")));
            }
        }
    }

    /** 内部索引只记录本次原句；不替换用户确认答案或原始需求。 */
    private static void addRule(List<PlanningKnownDecision> target, Kind kind, String subject, String clause) {
        target.add(new PlanningKnownDecision(kind, subject, clause.trim(), List.of("rawPrompt")));
    }

    /** 明确重开同一业务维度时不复用旧句；确认动作本身不等于重开规则。 */
    private static boolean reopensExecutionRule(String raw, String dimension) {
        return Pattern.compile("(?:" + dimension + ").{0,24}(?:尚未|未确定|未定|重新选择|重新设计|需要用户选择|需要用户决定)"
                + "|(?:调整|修改|更改|重新设计|重新选择|重新确定).{0,16}(?:" + dimension + ")").matcher(raw).find();
    }

    /** 只将研究设计中明说的状态和方法边界供模型复用，不将研究计划写成实测结果。 */
    private static void collectResearchRules(String raw, List<PlanningKnownDecision> target) {
        if (!raw.contains("研究") || !raw.matches("(?s).*(?:论文|方法|研究设计).*")) return;
        String goal = normalize(raw);
        if (goal.matches(".*(?:研究|比较|评估|检验)plan(?:交互)?问答.*" )
                && !goal.matches(".*(?:不|非|不是)(?:研究|比较|评估|检验)?plan(?:交互)?问答.*" )
                && !goal.matches(".*(?:重新选择|重新定义|调整).{0,12}plan.{0,6}定义.*" )) {
            addRule(target, Kind.PLAN_INTERACTION_DEFINITION, "研究对象为 Plan 交互问答",
                    "本次研究 Plan 问答：向用户澄清并获得回答后生成。静态列步骤或套模板不是同一条件；具体轮次、终止与实现方式尚不能推定。");
        }
        if (goal.contains("论文方法部分") && goal.contains("方法提纲")
                && !goal.matches(".*(?:背景|引言|综述|重新选择.{0,16}(?:交付|写作)范围).*" )) {
            addRule(target, Kind.WRITING_SECTION_SCOPE, "交付论文方法提纲",
                    "本次交付已限定为论文方法部分的提纲，按原文篇幅与附表规则执行；按已规定的研究设计组织提纲是写作职责，"
                            + "不重新询问是否覆盖这些设计、改为整篇或加入背景意义。真实缺失的数据来源、评分尺度、检验参数和新增条件仍可提问。");
        }
        for (String clause : raw.split("[。；;\\n]")) {
            if (clause.matches(".*[？?].*")) continue;
            if (clause.matches(".*(?:当前|本次).*(?:拟定|拟开展).*(?:尚未实施|尚未执行).*")) {
                addRule(target, Kind.RESEARCH_STATUS, "本次研究尚未实施", clause);
            }
            if (clause.matches(".*(?:评审|评委).*盲评.*") && !UNKNOWN.matcher(clause).find()
                    && !clause.matches(".*(?:不|未)(?:进行|采用|要求)?盲评.*")
                    && !reopensExecutionRule(raw, "是否盲评|盲评要求|评审条件可见性")) {
                addRule(target, Kind.REVIEWER_BLINDING, "评审不获知实验条件", clause);
            }
            if (clause.matches(".*检验.*未来数据分布.*选择.*")) {
                addRule(target, Kind.METHOD_SELECTION, "检验选择原则", clause
                        + "。可以描述候选检验，但实际采用的主要或具体检验仍须依据未来数据分布选择；"
                        + "不能把预先定案作为另一选项。具体评分、一致性指标与呈现方式仍可澄清。");
            }
            if (clause.matches(".*(?:同样|相同|等量)确认信息.*公平对照.*")) {
                addRule(target, Kind.INFORMATION_PARITY, "公平对照确认信息一致", clause
                        + "。呈现方式可以不同且可以不再交互，但必须提供与 Plan 同样的完整确认信息；"
                        + "仅使用同一个初始提示词、不补充确认信息不满足该前提。具体信息取得及呈现方式仍需决定。");
            }
        }
    }

    /** 本次选用常量加唯一真实声明才构成已定字段集合；未知、变更、子集与冲突声明都不建立事实。 */
    private static void collectFieldScopes(String raw, List<Source> sources, List<PlanningKnownDecision> target) {
        var selected = SELECTED_FIELDS.matcher(raw);
        Set<String> seen = new LinkedHashSet<>();
        while (selected.find() && seen.size() < 4) {
            String symbol = selected.group(1);
            if (!seen.add(symbol) || fieldScopeOpen(raw) || CHANGE_FIELD_SCOPE.matcher(raw).find()) continue;
            Pattern declaration = Pattern.compile("\\b" + Pattern.quote(symbol)
                    + "\\s*=\\s*\\[\\s*['\"]([A-Za-z0-9_]+)['\"](?:\\s*,\\s*['\"][A-Za-z0-9_]+['\"])*\\s*]");
            Set<String> definitions = new LinkedHashSet<>();
            Set<String> paths = new LinkedHashSet<>();
            for (Source source : sources) {
                if (source.origin() != PlanningFactOrigin.PROJECT_SOURCE) continue;
                var matches = declaration.matcher(source.text());
                while (matches.find()) {
                    String array = matches.group().substring(matches.group().indexOf('['));
                    String[] fields = array.replaceAll("[\\[\\]\\s'\"]", "").split(",");
                    Arrays.sort(fields);
                    definitions.add(String.join(",", fields));
                    paths.add(source.path());
                }
            }
            if (definitions.size() == 1) {
                List<String> evidenceSources = new ArrayList<>(List.of("rawPrompt"));
                evidenceSources.addAll(paths);
                target.add(new PlanningKnownDecision(Kind.FIELD_SCOPE, symbol,
                        "本次需求已选用 " + symbol + "；真实源码中的字段集合为：" + definitions.iterator().next()
                                + "。这只确定字段集合，不替代空值、权限、脱敏和新业务对象的独立规则。", evidenceSources));
            }
        }
    }

    /** 未决信息必须与字段集合维度关联，不能因原文另一个参数未知就废弃所有已知决定。 */
    private static boolean fieldScopeOpen(String raw) {
        for (String clause : raw.split("[。；;\\n]")) {
            if (FIELD_SCOPE.matcher(clause).find() && UNKNOWN.matcher(clause).find()) return true;
            if (clause.matches(".*(?:只|仅).*(?:部分字段|字段子集|哪部分字段).*")) return true;
        }
        return false;
    }

    /** 只收录原始需求明说的匹配提示触发；不能从“存在自动填充”推出任何触发机制。 */
    private static void collectTrigger(String raw, List<PlanningKnownDecision> target) {
        if (raw.matches("(?s).*(?:触发时机|触发条件).{0,16}(?:尚未|未定|未确定|调整|重新设计).*")) return;
        for (String clause : raw.split("[。；;\\n]")) {
            if (FILL_ACTION.matcher(clause).find() && clause.contains("提示")
                    && clause.matches(".*(?:当前地区有时|当前地区有匹配记录时|查询到.{0,16}匹配记录.{0,8}后|匹配成功后).*" )
                    && !UNKNOWN.matcher(clause).find()) {
                target.add(new PlanningKnownDecision(Kind.MATCH_TRIGGER, "自动填充提示触发", clause.trim(), List.of("rawPrompt")));
                return;
            }
        }
    }

    /** 只有服务端职责与已定地区范围均有依据时，编码/关系表细节才属于现有工程核查。 */
    private static void collectRegionLookup(String raw, List<Source> sources, List<PlanningKnownDecision> target) {
        if (!raw.matches("(?s).*包含下级地区.*排除其他同级地区.*")
                || reopensExecutionRule(raw, "地区过滤职责|地区过滤责任|地区过滤|地区范围")
                || raw.matches("(?s).*(?:地区范围|下级地区).{0,16}(?:尚未|未定|未确定|是否包含).*")
                || raw.matches("(?s).*(?:选择|决定|更换|调整|重新设计).{0,24}地区.{0,12}(?:算法|实现|判定).*")
                || raw.matches("(?s).*地区.{0,16}(?:算法|实现).{0,12}(?:未定|选择|决定|更换).*")) return;
        Set<String> layers = new LinkedHashSet<>();
        Set<String> paths = new LinkedHashSet<>();
        for (Source source : sources) {
            for (String clause : source.text().split("[。；;\\n]")) {
                if (!clause.contains("地区范围过滤")) continue;
                if (clause.matches(".*(?:待定|未知|尚未|建议|计划|目标|不是|不负责).*")) return;
                var relation = Pattern.compile("(服务端|后端|前端|客户端)负责地区范围过滤|地区范围过滤由(服务端|后端|前端|客户端)负责").matcher(clause);
                while (relation.find()) {
                    String layer = relation.group(1) == null ? relation.group(2) : relation.group(1);
                    layers.add(layer.equals("服务端") || layer.equals("后端") ? "server" : "client");
                    paths.add(source.path());
                }
            }
        }
        if (layers.size() == 1 && layers.contains("server")) {
            List<String> evidenceSources = new ArrayList<>(List.of("rawPrompt"));
            evidenceSources.addAll(paths);
            target.add(new PlanningKnownDecision(Kind.EXISTING_REGION_LOOKUP, "服务端地区范围判定",
                    "用户已明确包含下级地区、排除其他同级地区；材料明确服务端负责地区范围过滤。"
                            + "编码前缀或父级关系的具体实现由执行 Agent 核查现有工程，不自行假定或更改业务范围。", evidenceSources));
        }
    }

    /** 软件改动可委托工程核查；研究方法、测试策略本身作为任务时不使用该捷径。 */
    private static boolean softwareChange(String raw) {
        return raw.matches("(?s).*(?:开发|实现|完善|修复|重构|迁移).{0,40}(?:代码|接口|功能|逻辑|匹配|表单|模块).*" )
                && !raw.matches("(?si).*(?:研究|论文|科研|抽样|死亡率|发病率|yll|arriaga|测试策略|测试设计|性能测试).*" );
    }

    /** 延续用途白名单；测试、示例、模型生成内容和无明确用途的摘要不能证明已有实现。 */
    private static List<Source> sources(PlanningProviderRequest input) {
        List<Source> sources = new ArrayList<>();
        if (input.planningContext() == null) return sources;
        for (String summary : input.planningContext().fileSummaries()) {
            var match = SUMMARY.matcher(summary);
            if (match.matches()) sources.add(new Source(match.group(2), match.group(3), PlanningFactOrigin.valueOf(match.group(1))));
        }
        input.planningContext().factCards().stream()
                .filter(card -> card.origin() == PlanningFactOrigin.PROJECT_SOURCE
                        || card.origin() == PlanningFactOrigin.PROJECT_DOCUMENT || card.origin() == PlanningFactOrigin.USER_MATERIAL)
                .forEach(card -> sources.add(new Source(card.sourcePath(), card.evidence(), card.origin())));
        return List.copyOf(sources);
    }

    private static String normalize(String value) {
        return Normalizer.normalize(safe(value), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("\\s", "");
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
