import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.enhancement.service.impl.EffectiveTaskViewFactory;
import com.promptoptimizer.provider.domain.TaskEnhancementAssessment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

/** 对本批真实原件执行冻结生产类的离线观测，不修改原件、不调用模型或数据库。 */
public final class LiveFailureInspection {
    /** 逐项记录组装追加来源，并重现两份合法JSON的协议拒绝理由。 */
    public static void main(String[] args) throws Exception {
        var json=new ObjectMapper();
        var publicRoot=Path.of("docs/testing/evidence/me00a-candidate26-r2-live-2026-10-17");
        var local=Path.of("tmp/me00a-candidate26-r2-live-20261017/real");
        JsonNode trace=json.readTree(Path.of("tmp/me00a-20261012/traces/329.json").toFile());
        var raw=json.readTree(trace.path("response").path("choices").get(0).path("message").path("content").asText());
        var sections=new EnumMap<PromptSectionType,PromptSection>(PromptSectionType.class);
        for(var node:raw.path("sections")){
            var section=json.treeToValue(node,PromptSection.class);sections.put(section.type(),section);
        }
        var sample=json.readTree(publicRoot.resolve("cases.json").toFile()).get(0);
        List<PlanAnswer> answers=json.readerForListOf(PlanAnswer.class).readValue(local.resolve("Q05-NATURAL/actual-answers.json").toFile());
        var task=EffectiveTaskViewFactory.create(sample.path("rawPrompt").asText(),TemplateCode.AUTO,answers,List.of(),false);
        var integrator=Class.forName("com.promptoptimizer.enhancement.service.impl.PlanDecisionIntegrator");
        var missing=integrator.getDeclaredMethod("missingStatements",java.util.Map.class,
                Class.forName("com.promptoptimizer.enhancement.domain.EffectiveTaskView$ExecutionRequirement"));
        missing.setAccessible(true);
        var observations=new ArrayList<Object>();
        for(var requirement:task.executionRequirements())observations.add(java.util.Map.of(
                "subject",requirement.subject(),"statement",requirement.statement(),"sourceQuestionId",requirement.questionId(),
                "missingInOriginalModelSections",missing.invoke(null,sections,requirement)));
        var before=new EnumMap<>(sections);
        var integrate=integrator.getDeclaredMethod("integrate",java.util.Map.class,
                Class.forName("com.promptoptimizer.enhancement.domain.EffectiveTaskView"));
        integrate.setAccessible(true);integrate.invoke(null,sections,task);
        var changed=new ArrayList<Object>();
        for(var entry:sections.entrySet()){
            String previous=before.get(entry.getKey()).content(),current=entry.getValue().content();
            if(!previous.equals(current))changed.add(java.util.Map.of("section",entry.getKey().name(),
                    "before",previous,"after",current,"appended",current.startsWith(previous)?current.substring(previous.length()):"NON_APPEND_CHANGE"));
        }
        var decisions=new ArrayList<Object>();
        for(int number:new int[]{330,332}){
            var saved=json.readTree(Path.of("tmp/me00a-20261012/traces/"+number+".json").toFile());
            var response=json.readTree(saved.path("response").path("choices").get(0).path("message").path("content").asText());
            var assessment=json.treeToValue(response.path("assessment"),TaskEnhancementAssessment.class);
            List<String> findings=json.readerForListOf(String.class).readValue(response.path("ambiguities"));
            String reason="NO_REJECTION";
            try{assessment.validateModelDecisions(findings);}
            catch(com.promptoptimizer.provider.domain.ProviderResponseValidationException failure){reason=failure.getReason().name();}
            decisions.add(java.util.Map.of("trace",number,"findings",findings,"gapKinds",assessment.gaps().stream().map(gap->gap.kind().name()).toList(),
                    "reproducedReason",reason,"modelResponseModified",false));
        }
        var result=java.util.Map.of("kind","OFFLINE_FROZEN_PRODUCTION_OBSERVATION","modelCalls",0,
                "sourceTrace",329,"requirements",observations,"integratorChanges",changed,"protocolReplays",decisions);
        Files.writeString(publicRoot.resolve("live-frozen-method-observations.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(result),java.nio.file.StandardOpenOption.CREATE_NEW);
        System.out.println("Saved frozen original-response observations; 0 model calls");
    }
}
