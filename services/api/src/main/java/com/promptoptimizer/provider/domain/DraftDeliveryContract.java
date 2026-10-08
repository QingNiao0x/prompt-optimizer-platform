package com.promptoptimizer.provider.domain;

import com.promptoptimizer.enhancement.domain.ConfirmedPlanDecision;
import com.promptoptimizer.template.domain.PromptTemplate;
import com.promptoptimizer.template.domain.TaskDeliveryProfile;
import com.promptoptimizer.template.domain.TaskIntentResolver;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 写作任务的交付推进约定：缺少事实只限制依赖内容，不能把正文交付降为补材料问卷。
 * 该约定不选择专业参数、不改变确认状态，也不替代现有事实与计算依赖校验。
 *
 * @param active 本次明确要求写作，且没有只交付准备材料或先确认再起草的限制
 * @author QingNiao
 * @since 0.1.0
 */
public record DraftDeliveryContract(boolean active) {
    private static final Pattern WRITING = Pattern.compile(
            "(?:撰写|起草|编写|写出|写一(?:份|篇)|写份|写篇|写.{0,8}报告|生成.{0,12}(?:报告|正文|稿件))|\\b(?:write|draft)\\b",
            Pattern.CASE_INSENSITIVE);
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

    /** 仅采用当前用户指令及服务端已绑定的交付决定；材料中的引用和代码不建立起草许可。 */
    public static DraftDeliveryContract from(String rawPrompt, PromptTemplate template,
                                             List<ConfirmedPlanDecision> decisions) {
        if (template == null || template.deliveryProfile().softwareTask()
                || template.deliveryProfile() == TaskDeliveryProfile.TRANSLATION) return new DraftDeliveryContract(false);
        String instructions = instructionText(rawPrompt);
        if (hasPreparationLimit(instructions)) return new DraftDeliveryContract(false);
        for (var decision : decisions == null ? List.<ConfirmedPlanDecision>of() : decisions) {
            if ((decision.scope() == ConfirmedPlanDecision.Scope.TARGET || decision.scope() == ConfirmedPlanDecision.Scope.CHOICE)
                    && TaskIntentResolver.overallDeliveryQuestion(decision.question())
                    && hasPreparationLimit(instructionText(decision.answer()))) return new DraftDeliveryContract(false);
        }
        boolean proseProfile = switch (template.deliveryProfile()) {
            case NEWS_RELEASE, INSTITUTIONAL_REPORT, ACADEMIC_WRITING, USER_GUIDE, TEACHING -> true;
            default -> false;
        };
        return new DraftDeliveryContract(proseProfile || hasAffirmativeWriting(instructions));
    }

    /** 保持原有格式与范围；数据充分直接成稿，缺口只在对应位置保留，建议不冒充事实。 */
    public String guidance() {
        return active ? "按原定范围和格式交付正文，材料充分时直接成稿；不足时先写连贯的可修订初稿，不能只交提纲、方案、问卷或大面积待填模板。"
                + "背景、解释、讨论与局限写成段落；缺数据的结果章节集中用简短完整句说明尚不能得出的实证结论，不扩写一串待填句。仅原定交付中必要的具体值在对应位置标注[待补：具体内容]，"
                + "不逐年逐格铺空表或反复列缺口。不新增未请求的附录、指标或清单；已要求的表格和代码仍完整交付。"
                + "不编造数据、引用、已完成的分析或已选口径；常规写作和方法候选可自行组织，专业建议标明适用前提，不能冒充用户确认。资料提示供后续完善参考，不要求用户逐项答复后才起草；真实冲突仍保留，仅暂停依赖未决条件的内容，其余照常完成。" : "";
    }

    /** 优化模型生成的是提示词，须将起草要求传给执行者，不能在增强阶段直接撰写作品。 */
    public String modelGuidance() {
        return active ? "\n【本次正文交付】\n本次 TASK 只保留用户要完成的正文目标，OUTPUT 只说明本次具体交付内容、范围和格式，不把正文改成方案或问卷。"
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
            if (!prefix.matches(".*(?:不能|不要|不得|不应|并非|不是|不|未)\\s*$")
                    && !prefix.matches(".*(?:如果|假如|以后|将来|例如|示例|原文|引用).{0,20}")) return true;
        }
        return false;
    }

    /** 只接受肯定的写作动作；不能从未来、示例或明确否定的正文要求激活契约。 */
    private static boolean hasAffirmativeWriting(String instructions) {
        for (String clause : instructions.split("[。；;，,\\r\\n]")) {
            var matcher = WRITING.matcher(clause);
            if (matcher.find() && !clause.substring(0, matcher.start())
                    .matches(".*(?:不需要|不要|不得|无需|暂不|如果|以后|将来|例如|示例|原文|引用).{0,20}")) return true;
        }
        return false;
    }

    /** 资料标题后的多行仍属引用；任务与约束标题恢复当前指令，不修改原始输入或归档材料。 */
    private static String instructionText(String rawPrompt) {
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
            if (text.matches("^(?:原文|引用|示例|材料原文|参考资料)[:：].*")) {
                referenceSection = true;
                continue;
            }
            if (!referenceSection) lines.add(text);
        }
        return String.join("\n", lines);
    }
}
