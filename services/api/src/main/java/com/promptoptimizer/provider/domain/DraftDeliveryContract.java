package com.promptoptimizer.provider.domain;

import com.promptoptimizer.enhancement.domain.ConfirmedPlanDecision;
import com.promptoptimizer.template.domain.PromptTemplate;
import com.promptoptimizer.template.domain.TaskDeliveryProfile;
import com.promptoptimizer.template.domain.TaskIntentResolver;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 写作任务的交付推进约定：缺少事实只限制依赖内容，不能把正文交付降为补材料问卷。
 * 该约定不选择专业参数、不改变确认状态，也不替代现有事实与计算依赖校验。
 *
 * @param active 本次明确要求写作，且没有只交付准备材料或先确认再起草的限制
 * @param proseRequired 本次仍以正文为目标；先确认或禁止占位不等于允许改成交付方案
 * @param promptTarget 用户明确要交付的提示词目标；空字符串表示按普通作品交付检查
 * @author QingNiao
 * @since 0.1.0
 */
public record DraftDeliveryContract(boolean active, boolean proseRequired, String promptTarget) {
    private static final Pattern PROMPT_DELIVERY = Pattern.compile(
            "(?:准备|提供|生成|编写|改写|优化|增强|撰写|设计|拟定|交付|输出|整理|转写|写(?:出|一份|一个)?)"
                    + "[^。；;，,\\r\\n]{0,80}提示词"
                    + "|\\b(?:write|create|prepare|provide|generate|deliver|output|improve|enhance)\\b"
                    + "[^.;,\\r\\n]{0,100}\\bprompt\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern PREPARATION_ONLY = Pattern.compile(
            "(?:仅|只)(?:需要|要求|提供|交付|输出|给出|写|要).{0,16}(?:方案|提纲|大纲|目录|问题清单|空表|模板)"
                    + "|\\boutline only\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PROSE_DELIVERABLE = Pattern.compile(
            "(?:报告|论文|文章|新闻稿|稿件|正文|全文|指南|教案)(?![的\\s]*(?:方案|提纲|大纲|目录|模板|结构|空表))"
                    + "|\\b(?:report|article|manuscript)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern WRITING_PROHIBITION = Pattern.compile(
            "(?:暂不|不要|不得|不需要|无需|禁止|不允许|不应|不)(?:再|直接|立即|自行|开始)?"
                    + "(?:起草|撰写|编写|写|生成|输出|提供|交付)(?:.{0,12}(?:报告|正文|全文|稿件|文章|论文)|$)"
                    + "|\\bdo not draft\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern NO_PLACEHOLDER = Pattern.compile(
            "(?:禁止|不要|不得|不使用|不允许|不接受|无需).{0,6}占位|(?:不|无)占位(?:符)?"
                    + "|\\bno placeholders?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern ASK_FIRST = Pattern.compile(
            "(?:先|首先).{0,8}(?:问我|向我提问|提问|询问|澄清|确认)|\\bask me first\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CALCULATION_PREREQUISITE = Pattern.compile(
            "(?:计算|估计|估算|检验|分解).{0,10}前.{0,18}(?:问我|提问|询问|澄清|确认)"
                    + "|(?:问我|提问|询问|澄清|确认).{0,32}(?:再|后(?:再)?|才(?:能)?)(?:进行)?(?:计算|估计|估算|检验|分解)");
    private static final Pattern READ_ONLY_COMPARISON = Pattern.compile("(?:只读|只做|仅做|只作|仅作).{0,8}(?:比较|对照)");
    private static final Pattern EXCLUSIVE_DATA_FORMAT = Pattern.compile(
            "(?:只|仅)(?:输出|返回|提供|交付)\\s*(?:纯|有效的?|合法的?)?\\s*(?:JSON|CSV)(?![A-Za-z])",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FENCE = Pattern.compile("^(`{3,}|~{3,})(.*)$");
    private static final Pattern PREPARATION_DELIVERY = Pattern.compile(
            "(?:撰写|起草|编写|写出|生成|交付|输出|提供|给出)\\s*[:：]?[^。；;，,\\r\\n]{0,100}"
                    + "(?:方案|提纲|大纲|问卷|问题清单|空表|填空模板)(?:文档|文本)?\\s*$");
    private static final Pattern PROSE_AS_PREPARATION_NAME = Pattern.compile(
            "(?:报告|论文|文章|正文)(?:的)?(?:撰写|写作|方法)?(?=方案|提纲|大纲|目录|模板|结构|空表)");
    private static final Pattern ADDITIONAL_DELIVERY = Pattern.compile("^(?:并|同时|另外|另|还|附带|附|以及|补充)");
    private static final Pattern DELIVERY_ACTION = Pattern.compile("(?:撰写|起草|编写|写出|生成|交付|输出|提供|给出|写)");
    private static final Pattern WHOLE_DRAFT_PREREQUISITE = Pattern.compile(
            "(?:先|须|需|必须|待|等)[^。；;，,\\r\\n]{0,50}(?:补齐|齐全|确认)[^。；;，,\\r\\n]{0,30}"
                    + "(?:后|再|才|才能|[，,]\\s*再)(?:开始|继续|正式|着手)?"
                    + "(?:撰写|起草|编写|输出|交付)[^。；;，,\\r\\n]{0,16}(?:正文|全文|报告)"
                    + "|(?:资料|材料|数据|信息)[^。；;，,\\r\\n]{0,30}(?:未|没有|不)[^。；;，,\\r\\n]{0,16}"
                    + "(?:前|时)[^。；;，,\\r\\n]{0,8}(?:不得|不能|不要|暂停)[^。；;，,\\r\\n]{0,8}"
                    + "(?:撰写|起草|编写|输出|交付)[^。；;，,\\r\\n]{0,16}(?:正文|全文|报告)");

    /** 兼容仅有推进状态的内部调用；从用户需求创建时分别判断目标与起草许可。 */
    public DraftDeliveryContract(boolean active) {
        this(active, active);
    }

    /** 保持原有正文调用兼容；提示词交付只有显式用户要求才能激活。 */
    public DraftDeliveryContract(boolean active, boolean proseRequired) {
        this(active, proseRequired, "");
    }

    /** 仅采用当前用户指令及服务端已绑定的交付决定；材料中的引用和代码不建立起草许可。 */
    public static DraftDeliveryContract from(String rawPrompt, PromptTemplate template,
                                             List<ConfirmedPlanDecision> decisions) {
        String instructions = instructionText(rawPrompt);
        String promptTarget = promptDeliveryTarget(instructions);
        for (var decision : decisions == null ? List.<ConfirmedPlanDecision>of() : decisions) {
            if ((decision.scope() == ConfirmedPlanDecision.Scope.TARGET || decision.scope() == ConfirmedPlanDecision.Scope.CHOICE)
                    && TaskIntentResolver.overallDeliveryQuestion(decision.question())) {
                String answer = instructionText(decision.answer());
                String confirmedPrompt = promptDeliveryTarget(answer);
                if (!confirmedPrompt.isBlank()) promptTarget = confirmedPrompt;
                else if (hasAffirmativeWriting(answer)) promptTarget = "";
            }
        }
        // “为论文准备写作提示词”仍交付提示词，领域名词和篇幅限制不能把它改成论文提纲。
        if (!promptTarget.isBlank()) return new DraftDeliveryContract(false, false, promptTarget);
        if (template == null || template.deliveryProfile().softwareTask()
                || template.deliveryProfile() == TaskDeliveryProfile.TRANSLATION) return new DraftDeliveryContract(false);
        boolean preparationLimit = hasPreparationLimit(instructions);
        boolean preparationTarget = preparationIsMainTarget(instructions);
        boolean writingRequested = TaskIntentResolver.requestsWriting(instructions);
        for (var decision : decisions == null ? List.<ConfirmedPlanDecision>of() : decisions) {
            if ((decision.scope() == ConfirmedPlanDecision.Scope.TARGET || decision.scope() == ConfirmedPlanDecision.Scope.CHOICE)
                    && TaskIntentResolver.overallDeliveryQuestion(decision.question())) {
                String answer = instructionText(decision.answer());
                preparationLimit |= hasPreparationLimit(answer);
                preparationTarget |= preparationIsMainTarget(answer);
                writingRequested |= TaskIntentResolver.requestsWriting(answer);
            }
        }
        // 模板用于组织表达，不能成为写作授权；动作证据复用上游解析，背景事实不再被独立动词扫描推翻。
        boolean proseRequired = !preparationTarget && writingRequested;
        return new DraftDeliveryContract(proseRequired && !preparationLimit, proseRequired);
    }

    /** 仅补通用交付边界；具体章节、方法和占位格式不由这一跨场景契约决定。 */
    public String guidance() {
        return active ? "按原定范围和格式直接交付正文，不先发补材料问卷；材料充分时直接成稿，不足时完成有依据的可修订初稿，不以大片填空模板替代正文。"
                + "缺口只限制对应内容，不编造数据、引用或结论；重要未决条件集中说明一次，同一缺口不逐节重复标待确认；原要求的表格、代码及篇幅继续保留。" : "";
    }

    /**
     * 为直接角色指令区分启动与实际交付，避免空对话也套用完整报告格式。
     * 明确写提示词、先问、禁问和严格输出的用户要求不由这一默认分支覆盖。
     */
    public static String roleExecutionGuidance(String rawPrompt, PromptTemplate template) {
        if (template == null || template.code() != com.promptoptimizer.enhancement.domain.TemplateCode.GENERAL) return "";
        String instructions = instructionText(rawPrompt).strip();
        if (!instructions.matches("(?is)^(?:请)?(?:你是|你将(?:作为|担任)|你扮演|扮演|you are\\b|act as\\b).*")
                || !promptDeliveryTarget(instructions).isBlank() || ASK_FIRST.matcher(instructions).find()
                || instructions.matches("(?is).*(?:(?:只|仅)(?:输出|回复|返回)|(?:不要|不得|禁止|无需|不需要).{0,6}(?:提问|询问|问我|反问)|(?:启动|开场|首次).{0,12}(?:先|必须|需要|仅|只)|\\b(?:only output|only return|do not ask|no questions)\\b).*")
        ) return "";
        return "已有具体问题或待处理材料时直接完成任务，并按下列要求输出；仅收到角色设定时，简短进入角色并请用户提出问题，不列启动问卷或空分析章节。问题分类与常规组织由你完成。";
    }

    /** 优化模型生成的是提示词，须将起草要求传给执行者，不能在增强阶段直接撰写作品。 */
    public String modelGuidance() {
        if (!promptTarget.isBlank()) return "\n【本次提示词交付】\n用户目标：" + promptTarget
                + "\nTASK 明确交付可直接使用的提示词；OUTPUT 有独立交付说明时保持该层级，无新增说明可留空。领域说明用于充实这个提示词，"
                + "不能改为直接撰写论文、报告、方法提纲或执行代码。用户对提示词所指导作品的限制继续保留。\n";
        return active ? "\n【本次正文交付】\nTASK 明确原定正文目标，OUTPUT 仅补尚未表达的交付内容、范围和格式，无新增信息可留空；不把正文改成方案或问卷。"
                + "常规组织、衔接和说明由执行者自行完成；缺少数据只限制依赖它的实证数值和结论。"
                + "不新增用户未要求的空表、附录或指标，用户明确要求的表格继续交付；专业建议标明适用前提，不能冒充已确认事实。"
                + "\n起草推进、资料缺口和待补处理由平台统一追加；不要复制通用起草段落或待补机制。"
                + "实际缺口只按已有歧义清单契约返回，不在背景、任务、输出和约束重复列举。\n" : "";
    }

    /** 全量未决条件继续随复制正文保留，标题说明它们只限制相应内容。 */
    public String prerequisiteHeading() {
        return active ? "资料缺口与待定选择（仅影响对应内容；其余正文先完成）"
                : "执行前须确认（仅涉及下列未决条件的步骤需等待确认；不得自行假定答案）";
    }

    /**
     * 校验模型原始 TASK/OUTPUT，拒绝明确替换正文目标或将整篇起草绑定到补齐资料。
     * 调用方须在追加平台指导语前执行；普通方案讨论、引用和局部计算前提不构成冲突。
     */
    public void validateDelivery(String content, String field) {
        if (!promptTarget.isBlank()) {
            if (promptDeliveryTarget(instructionText(content)).isBlank()) throw new ProviderResponseValidationException(
                    ProviderResponseValidationException.Reason.RULE_CONFLICT, field);
            return;
        }
        // 平台产物是提示词，不代表执行者也应产出提示词；非写作任务和角色指令同样保护这一层级。
        if (!promptDeliveryTarget(instructionText(content)).isBlank()) {
            throw new ProviderResponseValidationException(
                    ProviderResponseValidationException.Reason.DELIVERY_LEVEL_CONFLICT, field);
        }
        if (!proseRequired || content == null || content.isBlank()) return;
        String instructions = instructionText(content);
        if (hasPreparationTarget(instructions)) {
            throw new ProviderResponseValidationException(ProviderResponseValidationException.Reason.RULE_CONFLICT, field);
        }
        if (!active) return;
        // 保留“补齐资料，再起草”的逗号连接；条件后的动作必须直接指向正文，不能跨过计算动作。
        for (String clause : instructions.split("[。；;\\r\\n]")) {
            if (affirmativeMatch(WHOLE_DRAFT_PREREQUISITE, clause)) {
                throw new ProviderResponseValidationException(ProviderResponseValidationException.Reason.RULE_CONFLICT, field);
            }
        }
    }

    /** 从肯定的当前交付动作提取目标，忽略否定、候选、引用和未来请求。 */
    static String promptDeliveryTarget(String instructions) {
        // 研究方案可合法描述被比较的提示词生成步骤；只有明确以提示词为最终交付才跨层级。
        boolean studyWorkflow = instructions.matches("(?is).*(?:撰写|写(?:一(?:个|份))?|设计|交付|编写|生成|一份).{0,25}研究(?:设计|方案)(?!的?(?:写作)?提示词).*" );
        for (String clause : instructions.split("[。；;，,\\r\\n]")) {
            var target = PROMPT_DELIVERY.matcher(clause);
            while (target.find()) {
                String prefix = clause.substring(0, target.start());
                if (studyWorkflow && !clause.strip().matches("(?is)^(?:[-*0-9.、\\s]*)(?:最终|仅|只)?(?:交付|输出|返回).{0,30}提示词(?:文本|文档)?[。.!！\\s]*$")) continue;
                if (prefix.lastIndexOf('“') > prefix.lastIndexOf('”') || prefix.lastIndexOf('‘') > prefix.lastIndexOf('’')) continue;
                // 研究一种增强流程时，提示词是研究对象；不是本次要求交付的作品。
                if (prefix.matches("(?is).*(?:研究(?!生)|比较|评估|检验|study|compare|evaluate).*")
                        && !prefix.matches("(?is).*(?:为|用于).{0,30}(?:研究|比较|评估).{0,30}(?:请|帮我|需要|要求|交付|输出|准备|编写)\\s*$")) continue;
                if (prefix.matches("(?is).*(?:不需要|不要求|不要|不得|不应|无需|禁止|暂不|不|未)(?:再|只|仅|直接|改成|改为|转为)?\\s*$")
                        || prefix.matches("(?is).*(?:如果|假如|以后|将来|例如|示例|原文|引用).{0,30}")
                        || prefix.matches("(?is).*(?:翻译|translate)[^。；;\\r\\n]*[:：‘“\"].*")) continue;
                return clause.strip();
            }
        }
        return "";
    }

    /** 只把肯定交付指令的准备材料宾语视为降级；正文附方案或方法中讨论方案仍属原目标。 */
    private static boolean hasPreparationTarget(String instructions) {
        boolean prosePresent = PROSE_DELIVERABLE.matcher(PROSE_AS_PREPARATION_NAME.matcher(instructions).replaceAll("")).find();
        boolean methodBodyDelivered = Arrays.stream(instructions.split("[。；;，,\\r\\n]"))
                .anyMatch(clause -> clause.contains("方法部分正文") && affirmativeMatch(DELIVERY_ACTION, clause));
        boolean proseDelivered = false;
        for (String clause : instructions.split("[。；;，,\\r\\n]")) {
            String prose = PROSE_AS_PREPARATION_NAME.matcher(clause).replaceAll("");
            var body = PROSE_DELIVERABLE.matcher(prose);
            boolean bodyIncludesPreparation = false;
            while (body.find()) {
                // “用于撰写报告的方案”不是交付报告；并列附件和正文中的局部说明才保留正文语义。
                String suffix = prose.substring(body.end());
                if (suffix.matches("^(?:并|和|与|及|、|中|内|里).*|^的.{0,12}(?:中|内).*")) {
                    bodyIncludesPreparation = true;
                    break;
                }
            }
            if (bodyIncludesPreparation) continue;
            // “另外”不能遮蔽明确的仅交准备材料限制；正文与空表并列的许可已在上面保留。
            if (affirmativeMatch(PREPARATION_ONLY, clause)) {
                // 同一方法部分已要求实质正文时，“方法与空表”是局部并列交付；仅提纲/空表或禁正文仍是降级。
                boolean localMethodWithTables = proseDelivered && methodBodyDelivered
                        && clause.strip().matches("(?:方法部分|方法正文)(?:仅|只)(?:交付|提供|输出)方法(?:正文)?(?:与|和|及)空表(?:模板)?")
                        && !WRITING_PROHIBITION.matcher(instructions).find();
                if (localMethodWithTables) continue;
                return true;
            }
            if (prosePresent && ADDITIONAL_DELIVERY.matcher(clause.strip()).find()) continue;
            boolean preparationDelivery = affirmativeMatch(PREPARATION_DELIVERY, clause);
            // 正文先成立后，普通方案/附录可为并列交付；“只交方案”仍覆盖目标，后置正文也不能救前句降级。
            if (preparationDelivery && !proseDelivered) return true;
            if (!preparationDelivery && PROSE_DELIVERABLE.matcher(prose).find()
                    && affirmativeMatch(DELIVERY_ACTION, clause)) proseDelivered = true;
        }
        return false;
    }

    /** 原始需求可同时要求正文与准备材料；只有明确“本轮只交准备材料”才覆盖正文目标。 */
    private static boolean preparationIsMainTarget(String instructions) {
        boolean proseRequested = false;
        for (String clause : instructions.split("[。；;，,\\r\\n]")) {
            if (affirmativeMatch(PREPARATION_ONLY, clause) && !PROSE_DELIVERABLE.matcher(clause).find()) return true;
            if (hasAffirmativeWriting(clause) && PROSE_DELIVERABLE.matcher(clause).find()
                    && !hasPreparationTarget(clause)) proseRequested = true;
        }
        return !proseRequested && hasPreparationTarget(instructions);
    }

    /**
     * 按子句保护本次交付限制；计算前确认只限制计算，不跨过标点将后面的起草也绑定为待确认。
     * 报告附带空表仍要求正文，只有准备材料的限制才关闭起草契约。
     */
    private static boolean hasPreparationLimit(String instructions) {
        if (READ_ONLY_COMPARISON.matcher(instructions).find() && !PROSE_DELIVERABLE.matcher(instructions).find()) return true;
        boolean proseRequested = PROSE_DELIVERABLE.matcher(instructions).find();
        for (String clause : instructions.split("[。；;，,\\r\\n]")) {
            // 纯数据格式不增加正文；报告附 CSV 或以 JSON 承载已要求的正文仍保留原定作品。
            if (!proseRequested && affirmativeMatch(EXCLUSIVE_DATA_FORMAT, clause)) return true;
            if (affirmativeMatch(WRITING_PROHIBITION, clause) || affirmativeMatch(NO_PLACEHOLDER, clause)) return true;
            if (affirmativeMatch(ASK_FIRST, clause) && !CALCULATION_PREREQUISITE.matcher(clause).find()) return true;
            if (affirmativeMatch(PREPARATION_ONLY, clause) && !PROSE_DELIVERABLE.matcher(clause).find()) return true;
        }
        return false;
    }

    /** “不能只给提纲”不建立提纲限定；候选条件与资料示例也不是当前用户指令。 */
    private static boolean affirmativeMatch(Pattern pattern, String clause) {
        var matcher = pattern.matcher(clause);
        while (matcher.find()) {
            String prefix = clause.substring(0, matcher.start());
            if (!prefix.matches(".*(?:不能|不要|不得|不应|并非|不是|不|未)(?:再|只|仅|直接)?\\s*$")
                    && !prefix.matches(".*(?:如果|假如|以后|将来|例如|示例|原文|引用).{0,20}")) return true;
        }
        return false;
    }

    /** 起草与提示词层级保护共用上游动作解析，不能各自维护一套正文授权规则。 */
    private static boolean hasAffirmativeWriting(String instructions) {
        return TaskIntentResolver.requestsWriting(instructions);
    }

    /** 资料标题后的多行仍属引用；任务与约束标题恢复当前指令，不修改原始输入或归档材料。 */
    static String instructionText(String rawPrompt) {
        var lines = new java.util.ArrayList<String>();
        String fence = null;
        boolean referenceSection = false;
        for (String line : (rawPrompt == null ? "" : rawPrompt).split("\\R")) {
            String text = line.trim();
            var fenceLine = FENCE.matcher(text);
            if (fenceLine.matches()) {
                String marker = fenceLine.group(1);
                if (fence == null) fence = marker;
                else if (fence.charAt(0) == marker.charAt(0) && marker.length() >= fence.length()
                        && fenceLine.group(2).isBlank()) fence = null;
                continue;
            }
            if (fence != null || text.startsWith(">") || text.startsWith("|")) continue;
            String heading = text.replaceAll("^[#*\\s]+|[*\\s]+$", "");
            if (heading.matches("(?:背景|项目背景|示例|参考资料|原文|引用|材料|资料|已知资料|材料原文)[:：]?")) {
                referenceSection = true;
                continue;
            }
            if (heading.matches("(?:任务|任务目标|目标|需求|输出|交付|输出要求|期望输出|约束|约束条件|限制|权限红线|验收标准)[:：]?")) {
                referenceSection = false;
                continue;
            }
            if (text.matches("(?i)^(?:原文|引用|示例|材料原文|参考资料|待译文本|source text)[:：].*")) {
                referenceSection = true;
                continue;
            }
            if (!referenceSection) lines.add(text);
        }
        return String.join("\n", lines);
    }
}
