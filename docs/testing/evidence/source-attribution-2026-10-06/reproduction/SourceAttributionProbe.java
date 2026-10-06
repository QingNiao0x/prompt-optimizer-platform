package com.promptoptimizer.enhancement.service.impl;
import java.util.List;
public class SourceAttributionProbe {
    public static void main(String[] args) {
        String raw = "整理草稿A和草稿B。维修条款一版写承租方承担日常维护，另一版新增全部设施故障费用。草稿A维修条款写承租方承担日常维护。";
        var contract = SourceObjectContract.from(raw, null, List.of());
        for (String candidate : List.of(
                "草稿A维修条款写承租方承担日常维护，草稿B作为对照版本。",
                "以草稿A为主文本，草稿A维修条款写承租方承担日常维护，草稿B作为对照版本。")) {
            try { contract.validate(candidate, "questions.options.answer"); System.out.println("VALID_CANDIDATE_ACCEPTED"); }
            catch (com.promptoptimizer.provider.domain.ProviderResponseValidationException error) {
                System.out.println("VALID_CANDIDATE_REJECTED reason=" + error.getReason());
            }
        }
    }
}
