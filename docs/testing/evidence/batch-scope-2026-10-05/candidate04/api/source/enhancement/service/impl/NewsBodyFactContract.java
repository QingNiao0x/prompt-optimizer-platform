package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.template.domain.TaskDeliveryProfile;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 让新闻正文继承同一报道对象的明确公开状态，保持输出范围、资料用途和披露权限的独立边界。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class NewsBodyFactContract {
    private static final Pattern CURRENT_STATE = Pattern.compile(
            "(?:当前|目前|尚|仍)(?:尚未|仍未|未|处于|为|是|已|正在|仅|只|不|尚处于|仍处于)|发布性质(?:是|为)");
    private static final Pattern UNVERIFIED = Pattern.compile(
            "[？?]|是否|未核实|未经核实|未确认|未明确|尚未确定|尚未决定|待确认|待批准|待定|证据不足|无法确认|尚不清楚|资料未说明");
    private static final Pattern NON_FACT = Pattern.compile(
            "例如|示例|假设|假如|如果|反例|排版测试|^(?:若|当)|忽略.{0,12}(?:平台|系统|安全)|绕过.{0,12}(?:平台|系统|安全)");
    private static final Pattern NEWS_TARGET = Pattern.compile(
            "(?:为|给)([^。\\n，,]{1,40}?)(?:撰写|编写|起草|写).{0,12}(?:新闻稿|发布稿)");
    private static final Pattern STAGE_OR_BOUNDARY = Pattern.compile(
            "(?i)MVP|阶段|发布性质|内测|公测|试点|试运行|正式(?:发布|上线|商用)|不(?:直接|自动)?执行|不承诺");
    private static final Pattern EDITOR_PROCESS = Pattern.compile(
            "我目前|编写者|写作者|编辑工作|内部审核|核对稿件|审核稿件|写作过程|校对过程");
    private static final Pattern PRIVATE_SCOPE = Pattern.compile(
            "手机号|电话号码|电子邮箱|身份证|个人健康|患者.{0,8}(?:姓名|病历|身份|诊断结果)|"
                    + "不得公开|不披露|禁止披露|不要披露|不要写入|仅供内部");
    private static final Pattern BODY_EXCLUSION = Pattern.compile(
            "(?:不要|无需|不用|禁止|不)(?:(?:额外|另外|另行|再)?(?:生成|产出|输出|提供|撰写|编写|写|交付))?"
                    + "(?:任何|额外|其他)?(?:新闻稿?|新闻)?正文");
    private static final Pattern ONLY_DELIVERY = Pattern.compile(
            "(?:只|仅)(?:输出|返回|提供|给出|给|交付)\\s*([^。；;\\n]{1,100})");
    private static final Pattern POSITIVE_BODY = Pattern.compile(
            "(?:输出|返回|提供|给出|交付|生成|产出|撰写|编写|起草|写)\\s*(?:新闻稿?|新闻)?正文|正文\\s*\\d");
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s*(.*?)\\s*#*\\s*$");
    private static final Pattern EXCLUDED_PURPOSE = Pattern.compile("示例|例如|假设|测试|反例|编写过程");
    private static final Pattern INTERNAL_PURPOSE = Pattern.compile(
            "仅供内部|内部(?:资料|材料|参考|记录|信息)|不披露|禁止披露|不得公开|^内部$");
    private static final Pattern PUBLIC_PURPOSE = Pattern.compile(
            "^(?:可公开事实|公开事实|对外公开事实|已批准公开的信息)[：:]?$");
    private static final Pattern DISCLOSURE_PERMISSION = Pattern.compile(
            "^(?:已确认|已批准)(?:允许|同意)在本次新闻稿(?:的)?正文(?:中)?(?:公开)?披露"
                    + "(.{1,40}?)(?:的)?(?:产品阶段|当前阶段|当前状态|发布性质)[。.]?$");
    private static final Pattern STAGE_DENIAL = Pattern.compile(
            "(?:不得|不要|禁止|不)(?:公开)?(?:披露|提及|报道|公开)([^。\\n，,]{0,40}?)"
                    + "(?:产品阶段|当前阶段|当前状态|发布性质|阶段|MVP|内测)");
    private static final String STATE_SUBJECT_START =
            "(?:(?:的)?(?:发布性质|当前状态|当前阶段|产品阶段)(?:为|是|[:：])|当前|目前|尚|仍)";
    private static final Pattern PRONOUN_STATE_SUBJECT = Pattern.compile(
            "^(?:该|本)(?:产品|项目|品牌|平台)(?=" + STATE_SUBJECT_START + ")");
    private static final Pattern ELIDED_STATE_SUBJECT = Pattern.compile("^(?:当前|目前|尚|仍|发布性质(?:是|为))");
    private static final Pattern STAGE_ONLY_VALUE = Pattern.compile(
            "(?i)^(?:(?:当前|目前|尚|仍)(?:尚未|仍未|未|处于|为|是|已|尚处于|仍处于)"
                    + "|(?:的)?(?:产品阶段|当前阶段|当前状态|发布性质)(?:为|是|[:：]))"
                    + "(?:本地|封闭|小范围|邀请制|公开|正式)?"
                    + "(?:MVP(?:阶段)?|(?:内测|公测|试点|试运行|商用|发布|上线)(?:阶段)?)(?:中)?[。.!！]?$"
    );

    private NewsBodyFactContract() { }

    /** 返回只适用于当前新闻任务的成品事实要求；没有明确事实时不推断。 */
    static String guidance(String rawPrompt) {
        if (rawPrompt == null || TaskDeliveryProfile.identify(rawPrompt) != TaskDeliveryProfile.NEWS_RELEASE
                || rawPrompt.matches("(?is).*(?:纯\\s*JSON|(?:只|仅)(?:输出|返回|提供|给出)\\s*JSON).*")) return "";
        // 正文出现在否定指令中不意味着允许正文；互相矛盾的交付范围留给既有歧义链路。
        if (outputScope(rawPrompt) != OutputScope.BODY_ALLOWED) return "";
        var targetMatch = NEWS_TARGET.matcher(rawPrompt);
        if (!targetMatch.find()) return "";
        String target = targetName(targetMatch.group(1));
        if (target.isBlank() || hasExplicitStageDenial(rawPrompt, target)) return "";
        var facts = new LinkedHashSet<String>();
        var safety = new SensitiveValueDetector();
        var scopes = new SourceScopes();
        Pattern namedStateSubject = Pattern.compile("^(?:合成)?(?:(?:产品|项目|品牌|平台)\\s*)?[「“\"]?"
                + Pattern.quote(target) + "[」”\"]?(?=" + STATE_SUBJECT_START + ")");
        boolean previousSubjectIsTarget = false;
        String fence = "";
        for (String line : rawPrompt.lines().toList()) {
            String current = line.strip();
            if (current.startsWith("```") || current.startsWith("~~~")) {
                String marker = current.substring(0, 3);
                if (fence.isEmpty()) fence = marker;
                else if (fence.equals(marker)) fence = "";
                previousSubjectIsTarget = false;
                continue;
            }
            if (!fence.isEmpty()) continue;
            var heading = HEADING.matcher(current);
            if (heading.matches()) {
                scopes.heading(heading.group(1).length(), heading.group(2));
                previousSubjectIsTarget = false;
                continue;
            }
            if (scopes.label(current)) {
                previousSubjectIsTarget = false;
                continue;
            }
            for (String source : line.split("(?<=[。！？!?])")) {
                String sentence = source.strip().replaceFirst("^(?:[-*•]\\s+|\\d+[.)、]\\s*)", "");
                ScopeState scope = scopes.current();
                if (scope.excluded()) {
                    previousSubjectIsTarget = false;
                    continue;
                }
                var permission = DISCLOSURE_PERMISSION.matcher(sentence);
                if (permission.matches() && targetName(permission.group(1)).equals(target)
                        && !UNVERIFIED.matcher(sentence).find() && !safety.containsCredential(sentence)) {
                    scopes.update(scope.permit(new DisclosurePermission(target, FactAspect.CURRENT_STAGE)));
                    // 明确许可已经命名本对象，紧邻的“该产品”仍需通过同一阶段和安全校验。
                    previousSubjectIsTarget = true;
                    continue;
                }
                if (scope.internalOnly() && (!scope.permits(target, FactAspect.CURRENT_STAGE)
                        || !onlyApprovedStage(sentence, namedStateSubject, previousSubjectIsTarget))) {
                    previousSubjectIsTarget = false;
                    continue;
                }
                // 只核对状态断言的实际主语；目标名出现在团队、竞品或资料说明中不能作为报道对象。
                boolean ownsTarget = (namedStateSubject.matcher(sentence).find()
                        || previousSubjectIsTarget && PRONOUN_STATE_SUBJECT.matcher(sentence).find())
                        && hasOnlyTargetStates(sentence, namedStateSubject, previousSubjectIsTarget);
                previousSubjectIsTarget = (ownsTarget || namesTaskTarget(sentence, target))
                        && !NON_FACT.matcher(sentence).find() && !UNVERIFIED.matcher(sentence).find()
                        && !PRIVATE_SCOPE.matcher(sentence).find() && !safety.containsCredential(sentence);
                // 保留完整否定与例外；含未知/示例的整句不升级为事实，也不截短过长条件制造新结论。
                if (ownsTarget && sentence.length() <= 240 && CURRENT_STATE.matcher(sentence).find()
                        && STAGE_OR_BOUNDARY.matcher(sentence).find() && !EDITOR_PROCESS.matcher(sentence).find()
                        && !UNVERIFIED.matcher(sentence).find() && !NON_FACT.matcher(sentence).find()
                        && !PRIVATE_SCOPE.matcher(sentence).find() && !safety.containsCredential(sentence)) facts.add(sentence);
            }
        }
        if (facts.isEmpty()) return "";
        return "正文必须交代下列用户已明确提供的当前状态，不能只留在提示词背景或标题中：\n- "
                + String.join("\n- ", facts) + "\n融入成品叙述，不增加声明清单、编造能力或改变原有篇幅与格式。";
    }

    /** 先求解封闭交付范围，不把“不生成正文”中的宾语反向当作正向交付。 */
    private static OutputScope outputScope(String rawPrompt) {
        boolean excluded = BODY_EXCLUSION.matcher(rawPrompt).find();
        String withoutExclusions = BODY_EXCLUSION.matcher(rawPrompt).replaceAll("");
        boolean titlesOnly = ONLY_DELIVERY.matcher(withoutExclusions).results()
                .anyMatch(match -> match.group(1).contains("标题") && !match.group(1).contains("正文"));
        if (!excluded && !titlesOnly) return OutputScope.BODY_ALLOWED;
        return POSITIVE_BODY.matcher(withoutExclusions).find() ? OutputScope.UNRESOLVED : OutputScope.BODY_EXCLUDED;
    }

    /** 仅以本次新闻指令绑定对象，包装引号和“产品”等名词不改变对象身份。 */
    private static String targetName(String value) {
        return value.strip().replaceAll("[「」“”\"]", "")
                .replaceFirst("^(?:合成)?(?:产品|项目|品牌|平台)", "").strip();
    }

    /** 指令中的命名目标只给紧邻的代词提供对象，不将整段指令升级为状态事实。 */
    private static boolean namesTaskTarget(String sentence, String target) {
        var goal = NEWS_TARGET.matcher(sentence);
        return goal.find() && targetName(goal.group(1)).equals(target);
    }

    /** 多分句中的其他对象状态不能随第一句目标状态一起强制公开；明确同主语省略仍可继承。 */
    private static boolean hasOnlyTargetStates(String sentence, Pattern namedSubject, boolean previousTarget) {
        boolean target = previousTarget;
        for (String part : sentence.split("[，,；;]")) {
            String clause = part.strip();
            if (CURRENT_STATE.matcher(clause).find() && STAGE_OR_BOUNDARY.matcher(clause).find()) {
                if (!namedSubject.matcher(clause).find() && !(target
                        && (PRONOUN_STATE_SUBJECT.matcher(clause).find() || ELIDED_STATE_SUBJECT.matcher(clause).find()))) return false;
                target = true;
            } else if (!clause.matches("^(?:但|并|且|同时|仅|只|不|未).+")) target = false;
        }
        return true;
    }

    /** 内部阶段许可只放行完整的阶段断言；同句额外能力、商业条款或其他属性不能随阶段公开。 */
    private static boolean onlyApprovedStage(String sentence, Pattern namedSubject, boolean previousTarget) {
        var named = namedSubject.matcher(sentence);
        if (named.find()) return STAGE_ONLY_VALUE.matcher(sentence.substring(named.end())).matches();
        var pronoun = PRONOUN_STATE_SUBJECT.matcher(sentence);
        return previousTarget && pronoun.find()
                && STAGE_ONLY_VALUE.matcher(sentence.substring(pronoun.end())).matches();
    }

    /** 明确不披露本对象阶段的指令优先；另一产品的限制不能关闭本对象的公开事实。 */
    private static boolean hasExplicitStageDenial(String rawPrompt, String target) {
        return STAGE_DENIAL.matcher(rawPrompt).results().map(match -> match.group(1).strip()
                        .replaceFirst("的$", "").replaceFirst("^(?:本|该)(?=产品|项目|品牌|平台)", ""))
                .anyMatch(subject -> subject.isEmpty() || subject.equals("该")
                        || subject.equals("本") || subject.equals("其")
                        || subject.matches("(?:该|本)(?:产品|项目|品牌|平台)")
                        || targetName(subject).equals(target));
    }

    private enum OutputScope { BODY_ALLOWED, BODY_EXCLUDED, UNRESOLVED }
    private enum FactAspect { CURRENT_STAGE }
    private record DisclosurePermission(String target, FactAspect aspect) { }

    /** 资料用途与公开许可独立；许可只能放行同对象同属性，不能解除示例或安全检查。 */
    private record ScopeState(boolean excluded, boolean internalOnly, Set<DisclosurePermission> permissions) {
        private static final ScopeState PUBLIC = new ScopeState(false, false, Set.of());

        private ScopeState { permissions = Set.copyOf(permissions); }

        private boolean permits(String target, FactAspect aspect) {
            return permissions.contains(new DisclosurePermission(target, aspect));
        }

        /** 许可只追加同一对象属性，不清除资料用途、内部标记或任何安全限制。 */
        private ScopeState permit(DisclosurePermission permission) {
            var values = new LinkedHashSet<>(permissions);
            values.add(permission);
            return new ScopeState(excluded, internalOnly, values);
        }

        /** 普通子标题只继承，不自行解除父资料的用途或内部范围。 */
        private ScopeState inherit(String title) {
            return new ScopeState(excluded || EXCLUDED_PURPOSE.matcher(title).find(),
                    internalOnly || INTERNAL_PURPOSE.matcher(title).find(), permissions);
        }
    }

    private record HeadingScope(int level, ScopeState baseline, ScopeState state) { }

    /** 标题用层级栈，独立用途标签用当前章节范围；同级章节结束旧范围，子章节保持继承。 */
    private static final class SourceScopes {
        private final Deque<HeadingScope> headings = new ArrayDeque<>();
        private ScopeState root = ScopeState.PUBLIC;

        private ScopeState current() {
            return headings.isEmpty() ? root : headings.peek().state();
        }

        private void update(ScopeState state) {
            if (headings.isEmpty()) root = state;
            else {
                HeadingScope heading = headings.pop();
                headings.push(new HeadingScope(heading.level(), heading.baseline(), state));
            }
        }

        /** 只弹出同级及更深标题；“示例”下面的普通子标题不能重新解释为公开事实。 */
        private void heading(int level, String title) {
            while (!headings.isEmpty() && headings.peek().level() >= level) headings.pop();
            ScopeState inherited = current().inherit(title);
            headings.push(new HeadingScope(level, inherited, inherited));
        }

        /** 跨行用途由独立标签开启；明确公共标签只结束本层范围，不解除父章节限制。 */
        private boolean label(String line) {
            if (PUBLIC_PURPOSE.matcher(line).matches()) {
                ScopeState inherited = inherentScope();
                update(new ScopeState(inherited.excluded(), inherited.internalOnly(), current().permissions()));
                return true;
            }
            if (!line.endsWith(":") && !line.endsWith("：")) return false;
            if (!EXCLUDED_PURPOSE.matcher(line).find() && !INTERNAL_PURPOSE.matcher(line).find()) return false;
            update(current().inherit(line));
            return true;
        }

        /** 公共标签只结束同层临时标签范围；章节自身或父章节的内部/示例限制不能解除。 */
        private ScopeState inherentScope() {
            if (headings.isEmpty()) return ScopeState.PUBLIC;
            return headings.peek().baseline();
        }
    }
}
