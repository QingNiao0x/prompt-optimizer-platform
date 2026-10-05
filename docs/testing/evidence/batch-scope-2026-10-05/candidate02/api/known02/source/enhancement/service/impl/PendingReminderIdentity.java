package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.ConfirmedPlanDecision;
import java.text.Normalizer;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 为已经绑定的未决子项生成完整对象签名，仅归一化语法，不以行业或事实类别判断同一决定。
 * 跨题合并必须有具名子项；继承对象和条件仍参与签名，不能让不同医院的相同属性相互覆盖。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class PendingReminderIdentity {
    private static final Pattern OWNER = Pattern.compile("^(?:对于|关于)?([\\p{IsHan}A-Za-z0-9_]{1,20}?"
            + "(?:医院|机构|公司|部门|工作区|租户|研究组|问卷|评分表))");
    private final ConfirmedDecisionSet decisions;

    PendingReminderIdentity(ConfirmedDecisionSet decisions) {
        this.decisions = decisions;
    }

    /** 只为完整具名子项提供跨题键；裸未知与依赖原题的泛指子项继续保留 questionId。 */
    Optional<String> namedKey(ConfirmedPlanDecision pending) {
        String subject = subject(pending.question(), pending);
        if (subject.length() < 4 || subject.matches("^(?:其余|其他|该|这|它).*")) return Optional.empty();
        if (!PlanAnswerSemantics.namesPendingSubject(pending.question())) return Optional.empty();
        return Optional.of(owner(pending) + "\u0000" + subject);
    }

    /** 继承的明确对象需要同时展示；否则不同对象虽有不同键，用户仍会看到两份相同文案。 */
    String owner(ConfirmedPlanDecision pending) {
        List<String> owners = originals(pending).stream().map(original -> {
            var context = Pattern.compile("^(?:对于|关于)([^，,。；;]{2,120})[，,]").matcher(compact(original.question()));
            if (context.find()) return context.group(1);
            var matcher = OWNER.matcher(original.question());
            if (matcher.find()) return matcher.group(1);
            var propertyOwner = Pattern.compile("^(?:对于|关于)?([^，,。；;？?]{2,30}?)(?:的)?"
                    + "(?:评分尺度|评分表|观察窗口|审批标准|退款标准|统计口径)").matcher(original.question());
            return propertyOwner.find() && !propertyOwner.group(1)
                    .matches(".*(?:采用|使用|哪|如何|怎样|是否|希望|之间|应).*" ) ? propertyOwner.group(1) : "";
        // 问句中的已确认区间/选择过程不是对象名称；不能把它继承回来再次标成未决。
        }).filter(value -> !value.isEmpty() && !value.matches(
                ".*(?:是否|如何|怎样|哪|采用|使用|检查区间).*"))
                .distinct().toList();
        if (owners.size() != 1 || compact(pending.question()).contains(owners.getFirst())) return "";
        return owners.getFirst();
    }

    /** 未决断言必须保留全部属性和条件；完整已知前缀只有在已确认回答或同题范围中有出处时移开。 */
    boolean matches(String heading, ConfirmedPlanDecision pending) {
        if (heading.matches("(?s).*(?:另外|此外|另需|还需|同时还).*")) return false;
        String candidate = subject(heading, pending);
        String known = subject(pending.question(), pending);
        if (!candidate.isEmpty() && !known.isEmpty()) return candidate.equals(known);
        // 只有完整的观察对象与属性吻合才兼容提问语序，不按“观察窗口”一个主题删题。
        return questionSubject(heading).equals(questionSubject(pending.question()))
                && questionSubject(heading).length() >= 6;
    }

    /** 只在原题明确写出的范围前缀上归一化，不将模型新加的对象或条件解释为同题。 */
    private String subject(String text, ConfirmedPlanDecision pending) {
        String normalized = compact(text);
        String subject = PlanAnswerSemantics.pendingSubject(normalized);
        if (subject.isEmpty()) return "";
        for (ConfirmedPlanDecision original : originals(pending)) {
            var context = Pattern.compile("^(?:对于|关于)([^，,。；;]{2,120})[，,]").matcher(compact(original.question()));
            if (context.find()) subject = subject.replaceFirst("^(?:对于|关于)"
                    + Pattern.quote(context.group(1)) + "[，,]", "");
        }
        var prefix = Pattern.compile("^([^，,]+)[，,](?:但)?(.+)$").matcher(subject);
        if (prefix.matches()) {
            String known = decisions.knownDecisions().stream().map(value -> compact(value.answer()))
                    .collect(java.util.stream.Collectors.joining("\u0000"));
            if (prefix.group(1).contains("采用") && known.contains(prefix.group(1))) subject = prefix.group(2);
        }
        // 只是未决等级/锚点的语法差异；“一致性指标”“计分权重”等其他属性仍完整保留。
        subject = subject.replaceFirst("^(?:具体|评分)(?=等级(?:和|与|及)评分锚点)", "");
        return subject.replace("等级与评分锚点", "等级及评分锚点")
                .replace("等级和评分锚点", "等级及评分锚点")
                .replace("等级以及评分锚点", "等级及评分锚点");
    }

    /** 医院同一预约未到诊对象的已给定别名可换写；其他括注、数值和条件一概保留。 */
    private String questionSubject(String text) {
        return compact(text).replaceFirst("^对于", "")
                .replace("预约后未到诊（爽约）", "预约未到诊")
                .replace("预约后未到诊(爽约)", "预约未到诊")
                .replace("的判定，", "的").replace("的判定,", "的")
                .replaceFirst("(?:应如何|如何)(?:设定|定义|确定)[？?]?$", "")
                .replaceAll("[。？?]+$", "");
    }

    private List<ConfirmedPlanDecision> originals(ConfirmedPlanDecision pending) {
        return decisions.decisions().stream().filter(original -> java.util.Objects.equals(
                original.questionId(), pending.questionId())).toList();
    }

    private String compact(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).replaceAll("\\s+", "");
    }
}
