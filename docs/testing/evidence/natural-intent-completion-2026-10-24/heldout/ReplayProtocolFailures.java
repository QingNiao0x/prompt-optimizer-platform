import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.provider.domain.*;
import com.promptoptimizer.provider.infrastructure.openai.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** 只用冻结类和已保存响应离线回放解析；不创建HTTP客户端、不登录、不调用模型。 */
class ReplayProtocolFailures {
    public static void main(String[] args) throws Exception {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        var provider = new OpenAiCompatibleNaturalPromptProvider(null,json,new OpenAiCompatibleProperties(),
                (com.promptoptimizer.provider.service.PlatformModelCatalog)null);
        var outputs = new ArrayList<Map<String,Object>>();
        for (String id : List.of("004","006")) {
            JsonNode trace = json.readTree(Path.of(args[0],"traces",id+".json").toFile());
            String user = trace.path("request").path("messages").get(1).path("content").asText();
            ObjectNode payload = (ObjectNode) json.readTree(user.substring(user.indexOf('{')));
            payload.remove(List.of("taskSources","inputBoundary","taskScope"));
            NaturalPromptInput input = json.treeToValue(payload,NaturalPromptInput.class);
            String content = trace.path("response").path("choices").get(0).path("message").path("content").asText();
            var row = new LinkedHashMap<String,Object>(); row.put("trace",id+".json");row.put("jobId",trace.path("jobId").asText());
            String name = id.equals("004") ? "diagnosticEnhancementReader" : "scopeReader";
            Field field = OpenAiCompatibleNaturalPromptProvider.class.getDeclaredField(name);field.setAccessible(true);
            try {
                Object value = ((ObjectReader)field.get(provider)).readValue(content);
                row.put("deserialization","PASS");
                if (value instanceof NaturalTaskScope scope) {
                    var checks = new ArrayList<Map<String,Object>>();
                    for (int index=0;index<scope.actions().size();index++) {
                        var action=scope.actions().get(index);
                        var ids=action.evidence().stream().map(NaturalTaskScope.Evidence::sourceId).toList();
                        boolean userSource=input.taskSources().stream().anyMatch(source -> ids.contains(source.id())
                            && Set.of("ORIGINAL_REQUEST","USER_CONVERSATION","BOUND_PLAN_ANSWER").contains(source.kind().name()));
                        checks.add(Map.of("index",index,"timing",action.timing().name(),"sourceIds",ids,"hasRequiredUserSource",userSource));
                    }
                    row.put("actionSources",checks);
                    try { scope.resolve(input);row.put("scopeResolve","PASS"); }
                    catch (RuntimeException failure) {row.put("scopeResolve","FAIL");row.put("exception",failure.getClass().getName());row.put("message",failure.getMessage());}
                }
            } catch (Exception failure) {
                row.put("deserialization","FAIL");row.put("exception",failure.getClass().getName());row.put("message",failure.getMessage());
            }
            outputs.add(row);
        }
        Files.writeString(Path.of(args[1]),json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("status","OFFLINE_REPLAY_COMPLETE","modelCalls",0,"results",outputs)),StandardOpenOption.CREATE_NEW);
        System.out.println("{\"event\":\"PROTOCOL_FAILURES_REPLAYED_OFFLINE\",\"modelCalls\":0}");
    }
}
