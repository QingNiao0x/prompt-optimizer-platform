import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.ProviderMetadata;
import com.promptoptimizer.provider.domain.*;
import java.nio.file.*;
import java.util.*;

/** Replays paid review responses through the product domain checks; does not invoke a model or access credentials. */
class FidelityFixtureCheck {
    public static void main(String[] args) throws Exception {
        var json = new ObjectMapper().findAndRegisterModules();
        Path evidence = Path.of(args[0]), runtime = Path.of(args[1]);
        var results = new ArrayList<Map<String, Object>>();
        for (var fixture : json.readTree(evidence.resolve("review-fixtures.json").toFile()).path("fixtures")) {
            var item = new LinkedHashMap<String, Object>();
            String id = fixture.path("id").asText();
            item.put("id", id); item.put("expected", fixture.path("expected").asText());
            try {
                NaturalPromptInput input;
                if (fixture.has("input")) {
                    ObjectNode source = fixture.path("input").deepCopy();
                    source.remove(List.of("taskSources", "completionSuggestions"));
                    input = json.treeToValue(source, NaturalPromptInput.class);
                } else {
                    input = new NaturalPromptInput(fixture.path("rawPrompt").asText(),
                            new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "test"),
                            List.of(), List.of(), List.of(), null).withStrategyVersion("17");
                }
                var task = json.treeToValue(fixture.path("taskUnderstanding"), NaturalTaskUnderstanding.class).resolve(input);
                String body = fixture.path("body").asText();
                task.review().validateEnhancement(task, body);
                var draft = new NaturalPromptDraft(body, NaturalPromptDraft.Framework.valueOf(fixture.path("framework").asText("APE")),
                        new ProviderMetadata("fixture", "paid-review", false), task);
                var paid = json.readTree(runtime.resolve("results/" + id + "_REVIEW.json").toFile());
                if (paid.path("status").asInt() != 200 || !paid.path("body").path("data").path("finishReason").asText().equals("stop"))
                    throw new IllegalArgumentException("No completed review response");
                String content = paid.path("body").path("data").path("content").asText().strip();
                String fence = Character.toString(96).repeat(3);
                if (content.startsWith(fence + "json\n") && content.endsWith(fence)) content = content.substring(8, content.length() - 3).strip();
                var review = json.readValue(content, NaturalPromptFidelityReview.class);
                var revised = review.apply(input, draft);
                item.put("protocolValid", true); item.put("sameDraft", revised == draft);
                item.put("bodyEqual", body.equals(revised.optimizedPrompt())); item.put("issues", review.issues());
                item.put("revisedBody", revised.optimizedPrompt());
                item.put("mechanicalExpectation", fixture.path("expected").asText().equals("UNCHANGED") ? revised == draft : revised != draft);
            } catch (Exception failure) {
                item.put("protocolValid", false); item.put("failureType", failure.getClass().getSimpleName());
                item.put("message", failure.getMessage()); item.put("mechanicalExpectation", false);
            }
            results.add(item);
        }
        Files.writeString(evidence.resolve("fixture-checks.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(results));
        long valid = results.stream().filter(r -> Boolean.TRUE.equals(r.get("mechanicalExpectation"))).count();
        System.out.println("Fixture structural checks: " + valid + "/" + results.size() + "; semantic assessment remains independent.");
    }
}
