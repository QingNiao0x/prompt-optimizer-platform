package com.promptoptimizer.enhancement.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.enhancement.domain.*;
import com.promptoptimizer.provider.domain.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Offline replay of real Plan adapter output through existing helpers; no auth or session binding. */
public final class TutorialPlanReview {
    public static void main(String[] args) throws Exception {
        var json = new ObjectMapper().findAndRegisterModules();
        var root = Path.of("tmp/template-tutorial-20261005");
        var input = json.readTree(Files.readString(root.resolve("cases.json")));
        for (var c : input.path("cases")) {
            String id = c.path("id").asText();
            var file = root.resolve("plan-" + id + ".json");
            if (!Files.exists(file)) continue;
            var original = json.readTree(Files.readString(file));
            if (!original.path("status").asText().equals("SUCCESS")) continue;
            var response = json.treeToValue(original.path("plan"), PlanningProviderResponse.class);
            var summaries = new ArrayList<String>();
            var paths = new ArrayList<String>();
            c.path("files").forEach(f -> { paths.add(f.path("path").asText());
                summaries.add(f.path("path").asText() + ": " + f.path("content").asText()); });
            var digest = new PlanningContextDigest(c.path("contextDescription").asText(),
                    List.of(), List.of(), paths, summaries, "COMPLETE", paths.size(), List.of());
            var request = PlanningDecisionPolicy.enrich(new PlanningProviderRequest(c.path("rawPrompt").asText(),
                    c.path("contextDescription").asText(), List.of(), digest, input.path("modelId").asText()));
            var filtered = new PlanQuestionFilter().filter(response.questions(), request);
            var explicitRules = new RequirementFidelityGuard().explicitRules(request.rawPrompt(), List.of());
            var candidateFailures = new ArrayList<Map<String,String>>();
            var policy = PlanningDecisionPolicy.from(request);
            for (var question : filtered) {
                var values = new ArrayList<String>(); values.add(question.hint()); values.addAll(question.examples());
                for (var option : question.options()) values.addAll(List.of(option.label(), option.description(),
                        option.answer(), option.recommendationReason()));
                for (String value : values) {
                    try { policy.validateCandidate(value, question.question(), "offlineReplay");
                        new RequirementFidelityGuard().validate(value, explicitRules, "offlineReplay"); }
                    catch (ProviderResponseValidationException e) {
                        candidateFailures.add(Map.of("questionId",question.id(),"reason",e.getReason().name(),"field",e.getField()));
                    }
                }
            }
            var aligned = filtered.stream().map(PlanChoiceCompleter::complete)
                    .map(q -> PlanRecommendationAligner.align(q,request)).toList();
            var out = new LinkedHashMap<String,Object>();
            out.put("scope","OFFLINE_HELPER_REPLAY_OF_REAL_PLAN_OUTPUT_NO_AUTH_OR_BINDING");
            out.put("caseId",id); out.put("rawCount",response.questions().size()); out.put("filteredCount",filtered.size());
            out.put("candidateValidationFailures",candidateFailures); out.put("questions",aligned);
            String value=json.writerWithDefaultPrettyPrinter().writeValueAsString(out);
            if (new SensitiveValueDetector().containsCredential(value)) throw new IllegalStateException("SENSITIVE_OUTPUT");
            Files.writeString(root.resolve("plan-replay-"+id+".json"),value,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
            System.out.println(json.writeValueAsString(Map.of("caseId",id,"rawCount",response.questions().size(),
                    "filteredCount",filtered.size(),"candidateFailures",candidateFailures.size())));
        }
    }
}
