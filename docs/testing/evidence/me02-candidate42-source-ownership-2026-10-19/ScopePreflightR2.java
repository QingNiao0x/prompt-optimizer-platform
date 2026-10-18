import com.fasterxml.jackson.databind.*;
import com.promptoptimizer.enhancement.domain.*;
import com.promptoptimizer.enhancement.service.impl.EffectiveTaskViewFactory;
import com.promptoptimizer.provider.domain.PromptRewriteStrategy;
import java.nio.file.*;
import java.util.*;

/** 冻结任务与模型载荷的正反检查，不进行真实请求或伪造Plan答案。 */
public class ScopePreflightR2 {
    static int checks;
    static void check(boolean condition, String message) { checks++; if (!condition) throw new IllegalStateException(message); }
    public static void main(String[] args) throws Exception {
        var json = new ObjectMapper(); var cases=json.readTree(Path.of(args[0]).toFile());
        for(var sample:cases) for(var format:sample.path("promptFormats")) {
            var task=EffectiveTaskViewFactory.create(sample.path("rawPrompt").asText(),TemplateCode.AUTO,List.of(),List.of(),sample.path("includeExamples").asBoolean());
            var payload=json.createObjectNode().put("rawPrompt",sample.path("rawPrompt").asText()).put("planConfirmed",sample.path("planConfirmed").asBoolean());
            payload.putObject("template");payload.putArray("conversationHistory");
            payload.putObject("options").put("templateCode","AUTO").put("promptFormat",format.asText()).put("includeExamples",sample.path("includeExamples").asBoolean());
            payload.set("taskView",json.valueToTree(task.modelInput()));payload.set("requirementReferences",Candidate42r2EvaluationServer.expectedReferences(task));
            payload.putObject("rewriteStrategy").put("mode",PromptRewriteStrategy.forTask(task,PromptFormat.valueOf(format.asText())).mode());
            var input=json.createObjectNode();input.putArray("messages").addObject().put("role","user").put("content",payload.toString());
            check(Candidate42r2EvaluationServer.scopedInputAllowed(input,cases,List.of()),"exact task and mode");
            if (payload.path("requirementReferences").isEmpty()) {
                var omitted=payload.deepCopy(); omitted.remove("requirementReferences");
                var valid=json.createObjectNode();valid.putArray("messages").addObject().put("role","user").put("content",omitted.toString());
                check(Candidate42r2EvaluationServer.scopedInputAllowed(valid,cases,List.of()),"optional empty references may be omitted");
            }
            for(String field:List.of("rawPrompt","planConfirmed","taskView","requirementReferences")){
                var changed=payload.deepCopy(); if(field.equals("rawPrompt"))changed.put(field,"foreign task");
                else if(field.equals("planConfirmed"))changed.put(field,!sample.path("planConfirmed").asBoolean());
                else changed.putObject(field);
                var invalid=json.createObjectNode();invalid.putArray("messages").addObject().put("role","user").put("content",changed.toString());
                check(!Candidate42r2EvaluationServer.scopedInputAllowed(invalid,cases,List.of()),"reject changed "+field);
            }
        }
        var result=json.createObjectNode().put("checks",checks).put("passed",checks).put("modelCalls",0).put("safetyBypassed",false);
        Files.writeString(Path.of(args[1]),json.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        System.out.println("Scope checks passed: "+checks);
    }
}
