package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.PlanningFactOrigin;
import com.promptoptimizer.provider.domain.PlanningProviderRequest;
import java.util.Locale;
import java.util.Set;

/**
 * 逐项核对测试范围候选是否只是本次已有业务分支的复述。
 * 新指标、新对象或没有证据的一个分支都会保留整题；不允许用候选文字证明其自身已定。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class KnownTestCoverage {
    private static final Set<String> GROUPS = Set.of("后端匹配与补值逻辑", "前端确认与取消交互",
            "并发与失效分支", "异常与失败分支");

    private KnownTestCoverage() { }

    /** 仅委托已要求测试清单的软件任务，不处理测试策略设计或用户主动重新选择范围的任务。 */
    static boolean repeatsKnownBranches(PlanQuestion question, PlanningProviderRequest input) {
        String raw = input.rawPrompt() == null ? "" : input.rawPrompt();
        if (!raw.contains("测试") || !raw.matches("(?s).*(?:实现方案|开发|表单).*" )
                || raw.matches("(?s).*(?:测试策略|性能测试|测试设计|选择测试范围|决定测试范围|询问.{0,12}测试范围|(?:选择|决定|询问).{0,12}测试(?:层|层级)).*" )
                || !question.question().matches(".*测试(?:清单)?(?:需要|应|应该)?覆盖(?:哪些范围|到哪一层)[？?]$")
                || question.options().isEmpty()) return false;
        StringBuilder sources = new StringBuilder(raw);
        if (input.planningContext() != null) {
            var accepted = Set.of(PlanningFactOrigin.PROJECT_SOURCE, PlanningFactOrigin.PROJECT_DOCUMENT,
                    PlanningFactOrigin.USER_MATERIAL);
            input.planningContext().factCards().stream().filter(card -> accepted.contains(card.origin()))
                    .forEach(card -> sources.append('\n').append(card.evidence()));
            input.planningContext().fileSummaries().stream()
                    .filter(summary -> summary.matches("(?s)^\\[(?:PROJECT_SOURCE|PROJECT_DOCUMENT|USER_MATERIAL)].*"))
                    .forEach(summary -> sources.append('\n').append(summary));
        }
        String evidence = normalize(sources.toString());
        if (question.question().endsWith("到哪一层？") || question.question().endsWith("到哪一层?")) {
            // 工程层委托仍需逐句核对所有展示信息，不能用熟悉的层级名称吞掉新业务、参数或缺失值策略。
            return knownLayerText(question.hint(), evidence) && question.options().stream().allMatch(option ->
                    knownLayerText(option.label(), evidence) && knownLayerText(option.description(), evidence)
                            && knownLayerText(option.answer(), evidence) && knownLayerText(option.recommendationReason(), evidence));
        }
        // 问题依据与选项展示文字同样可能携带新要求，不能仅凭提交答案为已知便隐藏整题。
        if (!knownMetadata(question.hint(), evidence)) return false;
        return question.options().stream().allMatch(option -> {
            // 列表前的工程分组只是标题，冒号后的每个实际分支都需独立有证据。
            String answer = option.answer();
            int label = Math.max(answer.indexOf('：'), answer.indexOf(':'));
            if (label < 0 || !answer.substring(0, label).startsWith("测试清单覆盖")) return false;
            String group = answer.substring("测试清单覆盖".length(), label);
            if (!GROUPS.contains(group) || !normalize(option.label()).equals(group)
                    || !knownMetadata(option.description(), evidence)
                    || !knownMetadata(option.recommendationReason(), evidence)) return false;
            String[] branches = answer.substring(label + 1).split("[、，,。；;]");
            boolean matched = false;
            for (String branch : branches) {
                if (branch.isBlank()) continue;
                if (!knownBranch(normalize(branch), evidence)) return false;
                matched = true;
            }
            return matched;
        });
    }

    /** 仅消除闭集工程用语与有原文证据的业务分支；任何剩余文字都保留整题。 */
    private static boolean knownLayerText(String text, String evidence) {
        if (text == null || text.isBlank()) return true;
        String remaining = normalize(text);
        String[][] aliases = {
                {"修改身份键后旧候选失效", "修改姓名或证件号后旧候选失效"},
                {"迟到请求失效", "迟到响应失效"}, {"信息不一致分支", "详情返回编号不一致时停止写入"},
                {"匹配条件", "姓名与证件号双键匹配"}, {"地区过滤", "地区条件"}, {"白名单写入", "字段白名单"},
                {"null/空字符串判断", "仅null或空字符串可补值"}, {"取消", "取消保持全部原值"},
                {"异常分支", "失败提示"}, {"异常", "失败提示"}, {"失败提示", "失败提示"},
                {"匹配", "姓名与证件号双键匹配"}, {"过滤", "地区条件"}, {"排序", "排序"}
        };
        for (String[] alias : aliases) {
            if (remaining.contains(alias[0]) && knownBranch(alias[1], evidence)) remaining = remaining.replace(alias[0], "");
        }
        // 层、已有接口和交付措辞仅是组织方式，不据此新增业务事实；未知对象不会被此闭集移除。
        remaining = remaining.replaceAll("原文要求按业务行为组织测试表|但未说明是否包含|这会影响交付的测试清单范围"
                + "|测试清单|业务行为|候选查询接口|候选查询|查询接口|详情接口|共用补值服务|前端交互层|前端交互|服务层|接口层"
                + "|在基础上|聚焦|覆盖|补充|的行为测试|行为测试|交互行为|交互测试|前端|确认|写入|分支|再|包括|以及|并|的|与|及|加|仅|在|基础上|等"
                + "|[、，,。；;：:/]", "");
        // 确认与读详情也有独立证据，层级说明不能凭工程名词自证这些行为已经存在。
        if (normalize(text).contains("详情接口") && !evidence.contains("读取单条详情的接口")
                && !evidence.contains("查询或详情失败")) return false;
        if (normalize(text).contains("确认") && !evidence.matches("(?s).*(?:用户同意|必须询问用户|确认框).*")) return false;
        return remaining.isBlank();
    }

    /** 只接受无附加要求的分组标题或已逐项证明的分支；无法证明的说明保留给用户。 */
    private static boolean knownMetadata(String text, String evidence) {
        if (text == null || text.isBlank()) return true;
        String[] clauses = text.split("[、，,。；;]");
        for (String clause : clauses) {
            if (clause.isBlank()) continue;
            String normalized = normalize(clause);
            if (!GROUPS.contains(normalized) && !knownBranch(normalized, evidence)) return false;
        }
        return true;
    }

    /** 闭集匹配已定动作，不以出现几个关键词推断新的效果或未决缺失值策略。 */
    private static boolean knownBranch(String branch, String evidence) {
        return switch (branch) {
            case "地区条件" -> evidence.contains("必须符合当前用户所属地区条件");
            case "姓名与证件号双键匹配" -> evidence.contains("匹配使用姓名与证件号两个键");
            case "排序" -> evidence.contains("更新时间降序") && evidence.contains("记录编号升序");
            case "字段白名单" -> evidence.contains("字段白名单只有");
            case "仅null或空字符串可补值" -> evidence.contains("只有目标值为null或空字符串时允许补入来源值");
            case "候选摘要展示" -> evidence.contains("查询接口返回候选摘要");
            case "确认后获取最新详情" -> evidence.contains("在用户同意后") && evidence.contains("获取该候选的最新详情");
            case "取消保持全部原值" -> evidence.contains("用户点击取消必须保持全部字段原值不变");
            case "失败提示" -> evidence.contains("失败提示");
            case "修改姓名或证件号后旧候选失效" -> evidence.contains("用户修改这两个键后") && evidence.contains("之前的候选必须失效");
            case "迟到响应失效" -> evidence.contains("迟到的旧请求必须失效");
            case "详情返回编号不一致时停止写入" -> evidence.contains("详情接口返回另一条编号") && evidence.contains("应停止写入");
            case "查询或详情失败时保留原值并显示可理解的失败提示" -> evidence.contains("查询或详情失败时保留原值并显示可理解的失败提示");
            case "不得伪装为没有匹配记录" -> evidence.contains("不得伪装为没有匹配记录");
            default -> false;
        };
    }

    /** 只消除空白和排版字符，不去掉否定、业务对象、数值与条件。 */
    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[\\s`*]", "");
    }
}
