package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.provider.domain.ProviderResponseValidationException;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException.Reason;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 守住材料已明确的地区补值准入条件，不替用户决定未核验候选的展示方式或行政层级。
 * 仅从本次可信用途的原文建立前提；候选答案与模型自报元数据不能证明准入已满足。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class PlanEligibilityGuard {
    private static final Pattern REQUIRED_REGION = Pattern.compile(
            "(?:记录|候选).{0,30}(?:必须|须|需要)(?:符合|满足|通过).{0,16}(?:当前用户|登录用户)(?:所属)?地区"
                    + "|(?:符合|满足)(?:当前用户|登录用户)(?:所属)?地区.{0,20}(?:才|方可).{0,12}(?:补值|填充|候选)");
    private static final Pattern UNVERIFIED_REGION = Pattern.compile(
            "地区.{0,24}(?:缺失|为空|未核验|无法核验|未验证|无法验证|不符|不匹配)"
                    + "|(?:无法核验|无法验证|缺失|未核验).{0,24}地区");
    private static final Pattern USE_CANDIDATE = Pattern.compile(
            "(?:选择|使用)(?:该|此)?候选.{0,16}(?:进入|进行|执行|继续)(?:补值|填充)"
                    + "|进入(?:补值|填充)流程|(?:允许|可以|仍可|可|继续)(?:进行|执行)?(?:补值|填充)"
                    + "|继续使用(?:该|此)?候选|(?:直接|继续|仍然|照常)(?:自动)?(?:补值|填充)"
                    + "|(?:视为|作为|判为|认定为)(?:可)?补值候选"
                    + "|(?:由|让)用户(?:自行)?决定是否继续确认(?!地区信息|地区条件|核验|资料|原因)"
                    + "|用户(?:仍可|可以|可)(?:继续)?确认(?:使用|选择)(?:该|此)?候选");
    private final boolean requiredRegion;

    private PlanEligibilityGuard(boolean requiredRegion) { this.requiredRegion = requiredRegion; }

    /** 调用方须先完成用途及敏感信息过滤；不凭只有地区关键词的缺口推断业务禁令。 */
    static PlanEligibilityGuard from(List<String> evidence) {
        return new PlanEligibilityGuard(evidence.stream().map(PlanEligibilityGuard::normalize)
                .anyMatch(value -> REQUIRED_REGION.matcher(value).find()));
    }

    boolean requiresRegion() { return requiredRegion; }

    /**
     * 按危险动作附近的否定与核验成功条件校验；另一句的“不得”不能掩盖用户确认后绕过准入。
     * 问题可补足选项省略的条件，但不能作为准入已满足的证据。
     */
    void validate(String candidate, String question, String field) {
        if (!requiredRegion) return;
        String text = normalize(candidate);
        boolean questionUnverified = UNVERIFIED_REGION.matcher(normalize(question)).find();
        for (String sentence : text.split("[。；;\\n]")) {
            if (!questionUnverified && !UNVERIFIED_REGION.matcher(sentence).find()) continue;
            var actions = USE_CANDIDATE.matcher(sentence);
            while (actions.find()) {
                String prefix = sentence.substring(0, actions.start());
                String local = prefix.substring(Math.max(0, Math.max(prefix.lastIndexOf('，'), prefix.lastIndexOf(',')) + 1));
                if (local.matches(".*(?:不|不得|禁止|不能|不允许|不可|不应)(?:(?:再|直接|继续|用户确认后|被|用户|人工|系统)){0,3}$")) continue;
                // 必须明确“核验成功/条件满足后才可”，仅补资料、再提示或用户确认不等于核验通过。
                String successfulGate = "(?:核验成功|验证通过|核验通过|当前地区匹配成功|(?:符合|满足)当前(?:用户(?:所属|所在)?)?地区条件(?:的(?:记录|候选记录|候选))?)"
                        + "(?:且(?:符合|满足)当前(?:用户所属)?地区条件)?(?:后|时)?";
                if (local.matches(".*" + successfulGate + "才(?:可|能|允许)?$")
                        || prefix.matches(".*" + successfulGate + "[,，]才(?:可|能|允许)?$")) continue;
                throw new ProviderResponseValidationException(Reason.RULE_CONFLICT, field);
            }
        }
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }
}
