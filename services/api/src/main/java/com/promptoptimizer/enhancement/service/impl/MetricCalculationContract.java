package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.provider.domain.ProviderResponseValidationException;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 区分规则依据、参数确认与计算就绪，仅拦截指标表中明确的自身矛盾。
 * 不推测专业依赖，不授权计算，不执行用户代码，沿用共享模型修复预算。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class MetricCalculationContract {
    private static final Logger LOGGER = LoggerFactory.getLogger(MetricCalculationContract.class);
    private static final Pattern FENCE = Pattern.compile("^(`{3,}|~{3,})(.*)$");
    private static final Pattern READY = Pattern.compile("(?:^|[；;。])(?:计算状态[:：])?(?:可直接计算|可计算|可执行|已就绪|已具备计算条件)(?:$|[。；;（(])");
    private static final Pattern PENDING = Pattern.compile("待确认|待核实|尚未确定|尚未确认|尚未核实|未决|未知");
    private static final Set<String> STATUS_COLUMNS = Set.of("状态", "计算状态", "执行状态", "计算就绪");
    private static final Set<String> DEPENDENCY_COLUMNS = Set.of("分母", "阈值", "观察窗口", "机构对应", "分母或所需参数",
            "所需参数", "参数状态", "计算前提", "执行前提", "必要依赖");
    static final String GUIDANCE = "口径依据、参数确认与计算就绪分别判断：状态列说明已定参数及尚缺计算前提，不能用一个“已确认”概括。"
            + "只有本指标实际依赖的分母、机构对应、观察窗口等均有适用依据并满足必要确认，才可标为可计算；不适用的依赖说明原因，不强加。"
            + "伪代码必须实际检查并使用这些具名参数，未知时只暂停依赖它的计算，不能仅声明未知却继续计算。";

    private MetricCalculationContract() { }

    /** 只校验明确指标表的同一行；引文、代码示例与普通工作表不按当前计算声明处理。 */
    static void validate(String text, String field) {
        List<String> header = List.of();
        String fence = null;
        for (String line : text.lines().toList()) {
            String stripped = line.strip();
            var marker = FENCE.matcher(stripped);
            if (marker.matches()) {
                String token = marker.group(1);
                if (fence == null) fence = token;
                else if (token.charAt(0) == fence.charAt(0) && token.length() >= fence.length()
                        && marker.group(2).isBlank()) fence = null;
                header = List.of();
                continue;
            }
            if (fence != null || !stripped.startsWith("|")) { header = List.of(); continue; }
            List<String> cells = Arrays.stream(stripped.replaceFirst("^\\|", "").replaceFirst("\\|$", "")
                    .split("(?<!\\\\)\\|", -1)).map(MetricCalculationContract::normalize).toList();
            if (cells.stream().allMatch(value -> value.matches(":?-+:?"))) continue;
            if (header.isEmpty()) { header = cells; continue; }
            if (cells.size() != header.size() || header.stream().noneMatch(value -> value.equals("指标") || value.equals("指标名称"))) continue;
            boolean ready = false;
            boolean pending = false;
            for (int index = 0; index < cells.size(); index++) {
                String value = cells.get(index);
                if (STATUS_COLUMNS.contains(header.get(index))) ready |= currentlyReady(value);
                if (STATUS_COLUMNS.contains(header.get(index)) || DEPENDENCY_COLUMNS.contains(header.get(index))) {
                    pending |= Arrays.stream(value.split("[，,。；;]+"))
                            .filter(clause -> !clause.matches("(?:无|没有|不存在)(?:待确认项|未决参数|未知参数)"))
                            .anyMatch(clause -> PENDING.matcher(clause).find());
                }
            }
            if (ready && pending) {
                LOGGER.warn("event=decision.delivery.validation_failed field={} check=METRIC_READINESS_CONFLICT", field);
                throw new ProviderResponseValidationException(ProviderResponseValidationException.Reason.RULE_CONFLICT, field);
            }
        }
    }

    /** 明确的未来条件不是当前就绪，不能把“确认后可计算”倒置成“已可计算”。 */
    private static boolean currentlyReady(String value) {
        return Arrays.stream(value.split("[；;。]"))
                .filter(clause -> !clause.matches(".*(?:仅在|只有|若|如果|确认后|核实后|条件满足后).*"))
                .anyMatch(clause -> READY.matcher(clause).find());
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).replaceAll("[\\s*`]", "");
    }
}
