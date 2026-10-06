package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.template.domain.TaskDeliveryProfile;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 将用户指南中已明确的能力边界和常规写法交给执行者，不把写法重新变成业务选择。
 * 仅处理具名的表达维度；发布、授权、专业口径、数值或复合问题继续保留。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class RoutineGuideDecision {
    private static final Pattern EXTRA_DECISION = Pattern.compile(
            "新增|另外|此外|另需|还需(?:要)?(?:确认|确定|决定)|授权(?:期限|范围)|审批|审核|法域|"
                    + "医学口径|统计口径|收费|付费|退款|有效期|保存天数|保留天数|新增脱敏权限|[<>≤≥]|\\d");
    private static final Pattern EXPLICIT_CHOICE = Pattern.compile(
            "(?:请|需要|希望)(?:先)?(?:让我选择|由我选择|询问我|向我确认|由用户选择)[^。；\\n]{0,40}"
                    + "(?:按钮|界面名称|资料类型|保留|历史期限|常见问题|术语|用词|英文词)");
    private static final Pattern EXPLICIT_FACT_CHECK = Pattern.compile(
            "(?:请|需要|希望)(?:先)?(?:向我确认|询问我|让我(?:来)?(?:确认|选择|决定)|由我(?:确认|选择|决定))"
                    + "[^。；\\n]{0,60}(?:未说明|未核实|功能|行为|操作|失败|删除|界面)"
                    + "|(?:未核实|未说明|产品行为)[^。；\\n]{0,40}(?:由我|由用户|需要我)(?:先)?(?:确认|选择|决定)");
    private static final Pattern EVIDENCE_GAP = Pattern.compile(
            "(?:材料|资料|附件)[^。；\\n]{0,100}(?:未说明|没有说明|未证实|未明确|没有明确|未提供|未给出)");
    private static final Pattern CURRENT_OBSERVATION = Pattern.compile(
            "当前(?:账户|用户|产品|工具|界面|页面|操作|流程)|现有(?:产品|工具|界面|页面|操作|流程)|实际"
                    + "|用户[^。；\\n]{0,100}(?:后|时|发生|在哪里)|界面|页面|列表|输入框|按钮|对话框"
                    + "|(?:资料|文件)(?:提交|上传|处理|解析|提取)?(?:完成)?(?:后|时)");
    private static final Pattern NEW_PRODUCT_DECISION = Pattern.compile(
            "本次希望|希望|新增|另外|此外|另需|还需(?:要)?(?:确认|确定|决定)|未来|拟(?:定|实现)|"
                    + "设计|策略|偏好|方案|应如何|应该|应当|采用哪|"
                    + "哪些用户|其他人|权限|授权|审批|审核|法域|收费|付费|赔偿|退款|医学|诊断|治疗|"
                    + "患者|统计口径|观察窗口|统计分母|阈值|有效期|保存天数|保留天数|[<>≤≥]|\\d");
    private static final Pattern GUIDE_WORDING_CONTEXT = Pattern.compile(
            "^(?:指南|教程)(?:中|里).*(?:界面|页面|历史|重试|失败)|^常见问题(?:中|里).*");
    private static final Pattern GUIDE_WORDING_END = Pattern.compile(
            "(?:应如何|应该如何|如何)(?:提示|表述|说明|描述|写)[？?]$");
    private static final Pattern WORDING_BOUNDARY = Pattern.compile(
            "不(?:描述|编造|补写|虚构)(?:具体)?(?:操作|步骤|界面|细节)|以(?:实际|当前)界面为准"
                    + "|只(?:介绍|说明|描述|写)(?:已证实|证实|已有|材料明确)");
    private static final Pattern EXTRA_WORDING_CAPABILITY = Pattern.compile(
            "跨(?:设备|账号|账户|租户|工作区)|自动(?:恢复|撤销|同步)|绕过|免(?:登录|验证码|授权)"
                    + "|不(?:需要|用)(?:登录|验证码|授权)|[一二三四五六七八九十百千万两半]+(?:天|日|周|月|年|次|轮|小时|分钟)");

    private RoutineGuideDecision() { }

    /** 先确认任务与现有决定，再检查整题是否夹带新要求；不按行业名称批量删题。 */
    static boolean delegated(PlanQuestion question, String raw) {
        if (TaskDeliveryProfile.identify(raw) != TaskDeliveryProfile.USER_GUIDE
                || EXPLICIT_CHOICE.matcher(raw).find() || EXPLICIT_FACT_CHECK.matcher(raw).find()) return false;
        String text = question.question();
        if (unverifiedInterfaceObservation(question, raw)) return true;
        if (unverifiedGuideWording(question, raw)) return true;
        if (unverifiedCapabilityPresentation(question, raw)) return true;
        boolean known = buttonPresentation(text, raw) || contextPresentation(text, raw)
                || unspecifiedHistoryLimit(text, raw) || knownSecurityPlacement(text, raw)
                || terminologyPresentation(text, raw) || knownCopyCoverage(text, raw)
                || unverifiedGuideEntry(text, raw);
        if (!known) return false;
        List<String> details = java.util.stream.Stream.concat(
                java.util.stream.Stream.of(text, question.hint()),
                java.util.stream.Stream.concat(question.examples().stream(), question.options().stream()
                        .flatMap(option -> java.util.stream.Stream.of(option.label(), option.description(),
                                option.answer(), option.recommendationReason())))).toList();
        return details.stream().noneMatch(value -> EXTRA_DECISION.matcher(value).find());
    }

    /**
     * 已给出忠于资料和当前界面回退时，产品观察事实交给执行者核查，不让用户猜出一个“现状”。
     * 委派只取消本次问答，不确认任何候选；原始需求中的未核实和界面核查前提仍须进入指南。
     */
    private static boolean unverifiedInterfaceObservation(PlanQuestion question, String raw) {
        if (!raw.matches("(?s).*(?:不虚构|不编造|不得编造|不能编造|只介绍附件明确|只写已有).*")
                || !raw.contains("以当前界面为准")) return false;
        String text = question.question();
        String evidence = text + " " + java.util.Objects.toString(question.hint(), "");
        // 问法和功能名称不是业务身份；“在哪里/看到什么/展示哪些”同样可以是当前可核查的操作事实。
        // 仅在用户已授权按真实界面回退、模型明确指出证据缺口且问题描述当前观察时委派。
        if (!EVIDENCE_GAP.matcher(evidence).find()
                || !CURRENT_OBSERVATION.matcher(text).find() && !explicitObservedOperation(text, raw)
                || !text.matches("(?s).*[？?]$")) return false;
        // 完整检查题干、提示、例子和每个候选的理由；观察题夹带新权限、数值或专业口径时保留整题。
        return java.util.stream.Stream.concat(java.util.stream.Stream.of(text, question.hint()),
                java.util.stream.Stream.concat(question.examples().stream(), question.options().stream()
                        .flatMap(option -> java.util.stream.Stream.of(option.label(), option.description(),
                                option.answer(), option.recommendationReason()))))
                .map(value -> java.util.Objects.toString(value, ""))
                .noneMatch(value -> NEW_PRODUCT_DECISION.matcher(value).find());
    }

    /** 用户已明确要求介绍某操作发生后的现状时，按完整操作名核对，不依赖产品功能词表。 */
    private static boolean explicitObservedOperation(String text, String raw) {
        var event = Pattern.compile("^([^，,。；;？?]{2,60}?)(?:后|时)[，,]").matcher(text);
        return event.find() && raw.contains(event.group(1));
    }

    /**
     * 已要求忠于材料且按当前界面回退时，指南中的“如何表述未说明行为”由撰写者处理。
     * 只移除题尾已识别的写法询问语法，不全局忽略“应如何”；全部候选仍接受独立决定校验。
     */
    private static boolean unverifiedGuideWording(PlanQuestion question, String raw) {
        if (!raw.matches("(?s).*(?:不虚构|不编造|不得编造|不能编造|只介绍附件明确|只写已有).*")
                || !raw.contains("以当前界面为准")) return false;
        String text = question.question();
        if (!GUIDE_WORDING_CONTEXT.matcher(text).find() || !GUIDE_WORDING_END.matcher(text).find()
                || !EVIDENCE_GAP.matcher(text + " " + java.util.Objects.toString(question.hint(), "")).find()
                || question.options().isEmpty()) return false;
        // 每个候选都必须明示不编造细节或遵循当前界面；不替用户采纳其中任何一个候选答案。
        if (!question.options().stream().allMatch(option -> WORDING_BOUNDARY.matcher(
                option.label() + " " + option.description() + " " + option.answer()).find())) return false;
        String wording = GUIDE_WORDING_END.matcher(text).replaceFirst("指南写法");
        return java.util.stream.Stream.concat(java.util.stream.Stream.of(wording, question.hint()),
                java.util.stream.Stream.concat(question.examples().stream(), question.options().stream()
                        .flatMap(option -> java.util.stream.Stream.of(option.label(), option.description(),
                                option.answer(), option.recommendationReason()))))
                .map(value -> java.util.Objects.toString(value, ""))
                .noneMatch(value -> NEW_PRODUCT_DECISION.matcher(value).find()
                        || EXTRA_WORDING_CAPABILITY.matcher(value).find());
    }

    /**
     * 已要求忠于材料的指南不能让用户把未证实功能选成产品事实。
     * 这里只委派“如何表述未说明能力”的写法；明确要求补核事实或夹带发布授权时保留。
     */
    private static boolean unverifiedCapabilityPresentation(PlanQuestion question, String raw) {
        if (!raw.matches("(?s).*(?:不虚构|不编造|不得编造|未核实|只介绍附件明确|只写已有).*")
                || raw.matches("(?s).*(?:请|需要|希望)(?:先)?(?:向我确认|询问我|确认|让我选择)[^。；\\n]{0,40}(?:未说明|未核实|恢复功能|产品能力|具体功能).*")) return false;
        String text = question.question();
        boolean wording = text.matches("(?s).*(?:材料|资料|附件).*(?:未说明|没有说明|未证实|只说明|未明确).*")
                && text.matches("(?s).*(?:指南|教程).*(?:应如何表述|如何表述|如何说明|怎么写|如何描述)[？?]$");
        // 用户已给出缺资料时的写法，不能再让其补编界面字段或未核实的编辑行为。
        boolean interfaceFallback = raw.contains("以当前界面为准")
                && java.util.Objects.toString(question.hint(), "").matches("(?s).*(?:材料|资料|附件).*(?:未说明|没有说明|未证实|只说明|未明确).*")
                && text.matches("(?s)^指南中.*(?:历史.*具体展示哪些信息.*能.*哪些操作|编辑和复制.*有哪些明确行为)[？?]$");
        // 资料缺口可在题干或提示中说明；既已要求只写证实内容，章节取舍和写法由撰写者按原范围处理。
        // 不按“指南”二字删题：完整题干中的业务授权、专业口径和数值决定仍须保留。
        String evidenceGap = text + " " + java.util.Objects.toString(question.hint(), "");
        boolean coveragePresentation = raw.contains("以当前界面为准") && text.startsWith("指南中")
                && evidenceGap.matches("(?s).*(?:材料|资料|附件).*(?:未说明|没有说明|未证实|只说明|未明确|没有明确).*")
                && text.matches("(?s).*(?:需要介绍|是否(?:还)?需要(?:说明|写明|补充|包含|提及|写)|指南应如何(?:处理|写|表述|说明|描述)).*")
                && !EXTRA_DECISION.matcher(text).find();
        if (!wording && !interfaceFallback && !coveragePresentation) return false;
        String details = text + " " + question.hint() + " " + String.join(" ", question.examples()) + " "
                + question.options().stream().map(option -> option.label() + " " + option.description() + " "
                + option.answer() + " " + option.recommendationReason()).collect(java.util.stream.Collectors.joining(" "));
        // 拟新增的功能不能因题目提到指南而被隐藏；真正独立的审批、数据授权继续提问。
        return !details.matches("(?s).*(?:另外|此外|另需|还需(?:要)?确认|同时还).*(?:审批|批准|授权|决定|法域|收费|数据范围|期限|阈值).*" );
    }

    /** 界面事实以已展示界面为准，措辞位置不另作业务选择。 */
    private static boolean buttonPresentation(String question, String raw) {
        return question.matches(".*(?:按钮|入口名称|界面名称).*(?:哪种方式处理|如何处理|怎么写|怎样写|写法|具体位置|界面区域).*" )
                && raw.matches("(?s).*(?:不编造|不得编造|不能编造).*(?:按钮|界面).*" )
                && raw.contains("以当前界面为准");
    }

    /** 原文已要求复制必要规则和未决前提，不能再让用户选择省略这些内容。 */
    private static boolean knownCopyCoverage(String question, String raw) {
        return question.matches(".*(?:复制).*(?:复制结果的范围|复制范围).*" )
                && raw.contains("复制应包含重要规则及未决前提");
    }

    /** 未证明的入口位置沿用原文的界面核对规则，具名权限或新前提仍由整题检查保留。 */
    private static boolean unverifiedGuideEntry(String question, String raw) {
        return question.matches(".*历史记录.*再次增强.*(?:操作入口|入口位置).*" )
                && raw.contains("以当前界面为准") && raw.contains("只介绍附件明确提供的行为");
    }

    /** 用户已指定资料种类及 OCR 边界，指南继承这些说明。 */
    private static boolean contextPresentation(String question, String raw) {
        return question.matches(".*(?:添加上下文|资料类型|材料类型).*(?:是否需要说明|是否列出|是否需要列出).*" )
                && raw.contains("代码索引") && raw.contains("办公文件")
                && raw.contains("图片仅元数据") && raw.matches("(?s).*扫描PDF.*不支持OCR.*");
    }

    /** 未披露的期限保持未知，不让指南写作阶段替产品决定数字。 */
    private static boolean unspecifiedHistoryLimit(String question, String raw) {
        return question.matches(".*历史.*(?:保留期限|保留时间|保存期限|数量限制|条数限制).*" )
                && raw.matches("(?s).*(?:没有在材料中说明|材料没有说明|材料未说明|附件未说明).*(?:不写具体数字|不得写具体数字).*" )
                && raw.contains("历史");
    }

    /** 已要求的安全边界由指南组织呈现，不重新询问是否包含。 */
    private static boolean knownSecurityPlacement(String question, String raw) {
        return question.matches(".*(?:常见问题|权限边界).*(?:是否需要包含|是否需要说明|放在哪里|写在哪里).*" )
                && question.matches(".*(?:管理员|密钥).*" )
                && raw.contains("权限边界") && raw.matches("(?s).*(?:不要求|不得要求|不能要求).*密码.*");
    }

    /** 明确的首次解释要求优先，英文词写法交给撰写者处理。 */
    private static boolean terminologyPresentation(String question, String raw) {
        return question.matches("(?s).*Plan.*(?:英文词|中文解释|统一用词|用词).*" )
                && raw.matches("(?s).*首次出现Plan.*解释.*");
    }
}
