package com.promptoptimizer.textpolish.service;

import com.promptoptimizer.textpolish.domain.TextPolishModels.Check;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 提示中文轻度稿中可明确对齐的局部变化。整句换写、遗漏及复杂条件仍由读者复核。 */
final class TextPolishChineseClauseReview {
    private static final String BREAK = "[，。,；;！？!?\\r\\n]";
    private static final Pattern MODE = Pattern.compile("(?:^|" + BREAK + ")(?<clause>(?:(?:这|此|其)?期间)?"
            + "(?<actor>\\p{IsHan}{0,16}?)(?<word>可以|可(?=从|在|由|向|先|直接|申请|提交|领取|通行|办理)"
            + "|必须|应当|应(?=在|于|先|提交|通知)|须|请(?=从|在|于|由|向|先|直接|申请|提交|通知|联系|领取)"
            + "|(?:会|将)(?=在|于|通知|告知|联系|提交|退款|退回|发放|跟进|核对|复核|处理|登记|支付|记录|查验|收取|确认))"
            + "(?<action>[\\p{IsHan}\\p{N}]{4,120}))(?=$|" + BREAK + ")");
    private static final Pattern NON_ACTOR_END = Pattern.compile("(?:不|未|不再|无需|无须|不得|是否|能否|可能|也|仍|还|只|才|建议|认为|决定)$");
    private static final Pattern TIMING = Pattern.compile("(?:^|" + BREAK + ")(?<clause>(?:在|于)?"
            + "(?<event>复查|复核|审核|审批|验收|检查|登记|确认)(?<phase>开始|结束|完成|通过)?(?<side>前|后)"
            + "[，, ]*(?<action>[^，。,；;！？!?\\r\\n]{4,120}))(?=$|" + BREAK + ")");
    private static final Set<String> EXPLICIT_ROLES = Set.of("前台", "客服", "物业", "值班人员", "保安", "管理员",
            "审核员", "档案员", "维护组", "采购组", "申请人", "负责人");

    private TextPolishChineseClauseReview() { }

    /** 逐类取第一处可定位的变化，避免相似或重复句被当成同一个动作。 */
    static List<Check> find(String source, String output) {
        List<Check> findings = new ArrayList<>();
        var beforeModes = index(MODE.matcher(source), false);
        var afterModes = index(MODE.matcher(output), false);
        for (var entry : beforeModes.entrySet()) {
            Clause before = unique(entry.getValue());
            Clause after = unique(afterModes.get(entry.getKey()));
            if (before == null || after == null) continue;
            if (!before.marker().equals(after.marker()) && sameActor(before, after)) {
                add(findings, "ZH_LIGHT_MODALITY_CHANGED", "许可、要求或承诺的措辞可能发生变化，请核对。", before, after);
            } else if (!before.actor().equals(after.actor()) && EXPLICIT_ROLES.contains(before.actor())
                    && EXPLICIT_ROLES.contains(after.actor())) {
                add(findings, "ZH_LIGHT_RESPONSIBILITY_CHANGED", "执行同一动作的角色可能发生变化，请核对。", before, after);
            }
        }
        var beforeTimes = index(TIMING.matcher(source), true);
        var afterTimes = index(TIMING.matcher(output), true);
        for (var entry : beforeTimes.entrySet()) {
            Clause before = unique(entry.getValue());
            Clause after = unique(afterTimes.get(entry.getKey()));
            if (before != null && after != null && !before.marker().equals(after.marker())) {
                add(findings, "ZH_LIGHT_TIMING_CHANGED", "同一事项的时间界限可能发生变化，请核对。", before, after);
            }
        }
        return List.copyOf(findings);
    }

    /** 只对齐字面相同的动作；时间界限还须指向同一事件。 */
    private static Map<String, List<Clause>> index(Matcher matches, boolean timing) {
        Map<String, List<Clause>> indexed = new LinkedHashMap<>();
        while (matches.find()) {
            String action = matches.group("action");
            String actor = timing ? "" : matches.group("actor");
            if (!timing && NON_ACTOR_END.matcher(actor).find()) continue;
            String key = timing ? matches.group("event") + " " + action : action;
            String marker = timing ? Objects.toString(matches.group("phase"), "") + matches.group("side")
                    : modality(matches.group("word"));
            indexed.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new Clause(actor, marker, matches.group("clause")));
        }
        return indexed;
    }

    /** 合并同一作用的词，如“可”和“可以”。变化只作为提示。 */
    private static String modality(String word) {
        return switch (word) {
            case "可", "可以" -> "PERMISSION";
            case "必须", "应当", "应", "须" -> "OBLIGATION";
            case "请" -> "REQUEST";
            default -> "COMMITMENT";
        };
    }

    /** 两个显式角色须相同；省略角色的“请”仍可与同一动作的许可或要求配对。 */
    private static boolean sameActor(Clause before, Clause after) {
        return before.actor().equals(after.actor()) || (before.actor().isEmpty() && before.marker().equals("REQUEST"))
                || (after.actor().isEmpty() && after.marker().equals("REQUEST"));
    }

    private static Clause unique(List<Clause> matches) { return matches != null && matches.size() == 1 ? matches.getFirst() : null; }

    /** 结果界面按提示代码展示，用原稿顺序选取可复核的片段。 */
    private static void add(List<Check> findings, String code, String message, Clause before, Clause after) {
        if (findings.stream().noneMatch(check -> check.code().equals(code)))
            findings.add(new Check(code, message, before.excerpt(), after.excerpt()));
    }

    private record Clause(String actor, String marker, String excerpt) { }
}
