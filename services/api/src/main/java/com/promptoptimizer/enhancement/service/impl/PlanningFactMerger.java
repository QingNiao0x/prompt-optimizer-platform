package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.PlanningFactCard;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 保留首次已绑定证据，同时把确认答案召回的新证据加入最终上下文；预算不足时明确提醒。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class PlanningFactMerger {
    private static final int MAX_FINAL_FACTS = 24;
    private final PlanningFactCardExtractor extractor = new PlanningFactCardExtractor();

    record MergeResult(List<PlanningFactCard> facts, List<PlanningFactCard> boundFacts, int omittedCount) {
        MergeResult {
            facts = List.copyOf(facts);
            boundFacts = List.copyOf(boundFacts);
        }

        MergeResult(List<PlanningFactCard> facts, int omittedCount) {
            this(facts, List.of(), omittedCount);
        }
    }

    /** 去重不删除运算符或标点，避免把相反的阈值规则合为一条。 */
    MergeResult merge(List<PlanningFactCard> bound, ContextSnapshot context, String query) {
        List<PlanningFactCard> retained = extractor.filterBoundFacts(bound, context, query);
        List<PlanningFactCard> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (PlanningFactCard card : retained) {
            if (seen.add(key(card))) result.add(card);
        }
        var fresh = extractor.extract(context, query);
        int omitted = fresh.omittedCount();
        int index = 1;
        for (PlanningFactCard card : fresh.cards()) {
            if (!seen.add(key(card))) continue;
            if (result.size() >= MAX_FINAL_FACTS) {
                omitted++;
                continue;
            }
            result.add(new PlanningFactCard("R" + String.format(Locale.ROOT, "%02d", index++),
                    card.category(), card.origin(), card.sourcePath(), card.evidence()));
        }
        return new MergeResult(result, retained, omitted);
    }

    private String key(PlanningFactCard card) {
        return card.sourcePath().replace('\\', '/') + "\u0000" + card.category() + "\u0000"
                + Normalizer.normalize(card.evidence(), Normalizer.Form.NFKC)
                .replaceAll("\\s+", " ").trim();
    }
}
