package com.promptoptimizer.enhancement.service.impl;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 精简同一已绑定提醒内部的未决状态复述，不识别新决定或改变确认状态。
 * 调用方须先确认唯一事项；完整对象、限定及状态动词相同才消费重复前缀，其后解释原样保留。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class BoundStateReminderCompactor {
    private static final Pattern CLAUSE = Pattern.compile("[^。；;\\r\\n]+[。；;]?");
    private static final Pattern STATE = Pattern.compile("^(.{4,180}?)(?:仍然|目前|现在|仍)?"
            + "(?:尚未|仍未|暂未|未)(核实|确认|确定|决定|明确|选定|提供)$");
    private static final Pattern NON_CURRENT = Pattern.compile("^(?:[>‘’“”\\\"'`]|若|如果|假如|假设|仅当|仅在|当(?!前)|例如|示例|引用|不要|不得|不应|禁止)");

    private BoundStateReminderCompactor() { }

    /** 只减去已出现的同项状态；数值、年份、运算符、审批条件与全部剩余说明均不改写。 */
    static String compact(String registered, String detail) {
        if (registered == null || registered.isBlank() || detail == null || detail.isBlank()) return detail;
        Set<StateKey> known = new LinkedHashSet<>();
        for (String part : registered.split("用户说明[：:]|补充说明[：:]|[。；;\\r\\n]")) {
            StateKey state = stateKey(header(part));
            if (state != null) known.add(state);
        }
        if (known.isEmpty()) return detail;
        var result = new StringBuilder();
        var clauses = CLAUSE.matcher(detail);
        int previousEnd = 0;
        boolean changed = false;
        while (clauses.find()) {
            result.append(detail, previousEnd, clauses.start());
            String clause = clauses.group().strip();
            String heading = header(clause);
            StateKey state = stateKey(heading);
            int separator = firstComma(clause);
            if (state != null && known.contains(state)) {
                changed = true;
                // 只移开已登记状态和紧接的分隔符，新说明仍留在原事项中，不能连同后半句删除。
                if (separator >= 0) result.append(clause.substring(separator + 1).strip());
            } else {
                result.append(clauses.group());
            }
            previousEnd = clauses.end();
        }
        result.append(detail, previousEnd, detail.length());
        return changed ? result.toString().strip() : detail;
    }

    /** 中文句号和分号不进入身份；逗号后的依赖解释不参与删除判断。 */
    private static String header(String text) {
        int separator = firstComma(text);
        return (separator < 0 ? text : text.substring(0, separator)).strip().replaceFirst("[。；;]+$", "");
    }

    private static int firstComma(String text) {
        int chinese = text.indexOf('，');
        int ascii = text.indexOf(',');
        return chinese < 0 ? ascii : ascii < 0 ? chinese : Math.min(chinese, ascii);
    }

    /** 只统一空白及同一状态动词的程度词，不把“未核实”和“未选定”当成同一业务状态。 */
    private static StateKey stateKey(String text) {
        String value = Normalizer.normalize(text, Normalizer.Form.NFC).replaceAll("\\s+", "");
        if (NON_CURRENT.matcher(value).find() || value.matches(".*[？?：:‘’“”\\\"'`].*")) return null;
        var state = STATE.matcher(value);
        return state.matches() ? new StateKey(state.group(1), state.group(2)) : null;
    }

    private record StateKey(String subject, String statusVerb) { }
}
