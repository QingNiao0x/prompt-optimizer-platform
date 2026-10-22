import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.ProviderMetadata;
import com.promptoptimizer.provider.domain.*;
import java.nio.file.*;
import java.util.*;

/** Reads paid stage responses through product validation; structural success needs independent semantic assessment. */
class GroundedFidelityFixtureCheck {
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    public static void main(String[] args) throws Exception {
        Path evidence=Path.of(args[0]), runtime=Path.of(args[1]);
        boolean repairs=args.length>2 && args[2].equals("--repairs");
        var checks=new ArrayList<Map<String,Object>>();
        for(var fixture:JSON.readTree(evidence.resolve("review-fixtures.json").toFile()).path("fixtures")) {
            String id=fixture.path("id").asText();var item=new LinkedHashMap<String,Object>();
            item.put("id",id);item.put("expected",fixture.path("expected").asText());
            boolean required=!fixture.path("role").asText().equals("OBSERVATION_ONLY");
            item.put("role",required?"REQUIRED":"OBSERVATION_ONLY");
            try {
                NaturalPromptInput input;
                if(fixture.has("input")) {
                    ObjectNode source=fixture.path("input").deepCopy();source.remove(List.of("taskSources","completionSuggestions"));
                    input=JSON.treeToValue(source,NaturalPromptInput.class).withStrategyVersion("19");
                } else input=new NaturalPromptInput(fixture.path("rawPrompt").asText(),
                        new ContextSnapshot("",List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),"test"),
                        List.of(),List.of(),List.of(),null).withStrategyVersion("19");
                var task=JSON.treeToValue(fixture.path("taskUnderstanding"),NaturalTaskUnderstanding.class).resolve(input);
                String body=fixture.path("body").asText();task.review().validateEnhancement(task,body);
                var draft=new NaturalPromptDraft(body,NaturalPromptDraft.Framework.valueOf(fixture.path("framework").asText("APE")),
                        new ProviderMetadata("fixture","paid-stages",false),task);
                var findings=JSON.readValue(content(runtime,id+"_DETECT"),NaturalPromptFidelityFindings.class);
                var targets=NaturalPromptFidelityTargets.resolve(input,draft,findings);item.put("targets",targets);
                item.put("protocolValid",true);item.put("issues",findings.issues());
                item.put("observedIssue",!findings.issues().isEmpty());
                if(required)item.put("mechanicalExpectation",fixture.path("expected").asText().equals("UNCHANGED")?findings.issues().isEmpty():!findings.issues().isEmpty());
                if(repairs) {
                    var revised=findings.issues().isEmpty()?draft:NaturalPromptFidelityTargets.applyRepair(input,draft,findings,
                            JSON.readValue(content(runtime,id+"_PATCH"),NaturalPromptFidelityTargets.Repair.class));
                    item.put("bodyEqual",body.equals(revised.optimizedPrompt()));item.put("revisedBody",revised.optimizedPrompt());
                    item.put("repairProtocolValid",true);
                }
            } catch(Exception failure) {
                item.put("protocolValid",false);item.put("failureType",failure.getClass().getSimpleName());
                item.put("message",failure.getMessage());if(required)item.put("mechanicalExpectation",false);
            }
            checks.add(item);
        }
        Files.writeString(evidence.resolve(repairs?"fixture-repair-checks.json":"fixture-detection-checks.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(checks));
        System.out.println("Required structural expectations: "+checks.stream().filter(c->Boolean.TRUE.equals(c.get("mechanicalExpectation"))).count()+"/"+
                checks.stream().filter(c->c.get("role").equals("REQUIRED")).count()+"; observation-only: "+
                checks.stream().filter(c->c.get("role").equals("OBSERVATION_ONLY")).count());
    }
    /** Captured response content only; does not load credentials, hidden reasoning, or arbitrary files. */
    static String content(Path runtime,String job) throws Exception {
        var paid=JSON.readTree(runtime.resolve("results/"+job+".json").toFile());
        if(paid.path("status").asInt()!=200 || !paid.path("body").path("data").path("finishReason").asText().equals("stop"))
            throw new IllegalArgumentException("No completed response");
        String value=paid.path("body").path("data").path("content").asText().strip(), fence=Character.toString(96).repeat(3);
        if(value.startsWith(fence)&&value.endsWith(fence)) {
            int newline=value.indexOf('\n');
            String label=newline<0?"invalid":value.substring(3,newline).strip();
            if(label.isEmpty()||label.equalsIgnoreCase("json"))value=value.substring(newline+1,value.length()-3).strip();
        }
        return value;
    }
}
