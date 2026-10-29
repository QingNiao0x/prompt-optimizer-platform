import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.provider.domain.*;
import com.promptoptimizer.enhancement.domain.ProviderMetadata;
import com.promptoptimizer.provider.infrastructure.openai.*;
import java.nio.file.*;
import java.util.*;

/** Offline only: replay actual first body, record and actual patch through current product checks. */
public final class DiagnoseBodyFirst {
    static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    static ObjectNode user(JsonNode trace)throws Exception{String text=trace.path("request").path("messages").get(1).path("content").asText();return (ObjectNode)JSON.readTree(text.substring(text.indexOf('{')));}
    static JsonNode content(JsonNode trace)throws Exception{return JSON.readTree(trace.path("response").path("choices").get(0).path("message").path("content").asText());}
    static List<String> causes(Throwable failure){var out=new ArrayList<String>();for(Throwable c=failure;c!=null;c=c.getCause())out.add(c.getClass().getSimpleName()+": "+c.getMessage());return out;}
    static void check(String step,JsonNode body,NaturalPromptInput input)throws Exception{
        var mismatches=new ArrayList<Map<String,Object>>();
        for(int i=0;i<body.path("task").path("requirements").size();i++)for(var evidence:body.path("task").path("requirements").get(i).path("evidence")){
            String id=evidence.path("sourceId").asText(),quote=evidence.path("quote").asText();
            var source=input.taskSources().stream().filter(x->x.id().equals(id)).findFirst();
            if(source.isEmpty()||!source.get().text().contains(quote))mismatches.add(Map.of("requirement",i,"sourceId",id,"sourceExists",source.isPresent(),"quote",quote));
        }
        System.out.println(JSON.writeValueAsString(Map.of("step",step,"sourceMismatches",mismatches)));
        try{
            var provider=new OpenAiCompatibleNaturalPromptProvider(null,JSON,new OpenAiCompatibleProperties(),null);
            var read=provider.getClass().getDeclaredMethod("readEnhancementPhase",JsonNode.class,NaturalPromptInput.class,ProviderMetadata.class);read.setAccessible(true);
            var draft=(NaturalPromptDraft)read.invoke(provider,body,input,new ProviderMetadata("deepseek","deepseek:deepseek-flash",false));
            var task=draft.taskUnderstanding().resolve(input);
            NaturalPromptRequirementsOnlyPreparation.validateUserGoals(task);
            var type=com.promptoptimizer.enhancement.service.impl.NaturalEnhancementService.class;
            var assemble=type.getDeclaredMethod("validateBody",NaturalPromptDraft.class,List.class,String.class);assemble.setAccessible(true);
            var service=new com.promptoptimizer.enhancement.service.impl.NaturalEnhancementService(null,null,null,null,null,null);
            String assembled=(String)assemble.invoke(service,draft,input.mandatoryRules(),input.strategyVersion());
            task.review().validateEnhancement(task,draft.optimizedPrompt());task.review().validateEnhancement(task,assembled);
            System.out.println(JSON.writeValueAsString(Map.of("step",step,"passed",true)));
        }catch(Throwable failure){System.out.println(JSON.writeValueAsString(Map.of("step",step,"passed",false,"causes",causes(failure))));}
    }
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]);var first=JSON.readTree(root.resolve("traces/001.json").toFile());var record=JSON.readTree(root.resolve("traces/002.json").toFile());var patch=JSON.readTree(root.resolve("traces/003.json").toFile());
        var original=user(record);var typed=original.deepCopy();typed.remove(List.of("taskSources","inputBoundary","fidelityDraft","taskScope","preparedRequirements"));
        var input=JSON.treeToValue(typed,NaturalPromptInput.class);
        var body=(ObjectNode)content(record);body.put("optimizedPrompt",content(first).path("optimizedPrompt").asText());
        check("ORIGINAL_RECORD",body,input);
        Class<?> type=Class.forName("com.promptoptimizer.provider.infrastructure.openai.NaturalPromptMetadataRepair");
        var inspect=type.getDeclaredMethod("inspect",JsonNode.class,JsonNode.class,NaturalPromptInput.class);inspect.setAccessible(true);
        var repair=((Optional<?>)inspect.invoke(null,original,body,input)).orElseThrow();
        var apply=repair.getClass().getDeclaredMethod("apply",JsonNode.class);apply.setAccessible(true);
        JsonNode corrected=(JsonNode)apply.invoke(repair,content(patch));check("ACTUAL_PATCHED_RECORD",corrected,input);
        System.out.println(JSON.writeValueAsString(Map.of("fixtureOnly",true,"actualCalls",0)));
    }
}
