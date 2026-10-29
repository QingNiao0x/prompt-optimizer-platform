import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Finite profile/whole-request negatives only, no application startup, HTTP, model or identity mutation. */
public final class NativePro52SelfTest {
 static int passed;
 public static void main(String[] args)throws Exception{
  var j=NaturalUsefulDeliveryEvaluationServer.JSON;Path evidence=Path.of(args[0]);
  JsonNode bundle=j.readTree(evidence.resolve("native-pro52-preparation/bundle.draft.json").toFile());
  ObjectNode p=j.createObjectNode().put("modelId","deepseek:deepseek-v4-pro").put("upstreamModel","deepseek-v4-pro").put("endpoint","https://api.deepseek.com/chat/completions").put("maximumNewActualCalls",2);
  p.set("parameters",bundle.path("parameters"));p.set("phaseLimits",bundle.path("phaseLimits"));
  ObjectNode profile=bundle.path("modelSelectionProfile").deepCopy();profile.put("upstreamModel","deepseek-v4-pro").put("routeKey","deepseek").put("endpoint","https://api.deepseek.com/chat/completions").put("maximumActualCalls",2);p.set("modelSelectionProfile",profile);
  JsonNode job=bundle.path("jobs").path(0),payload=job.path("approvedModelRequest");
  NaturalUsefulDeliveryEvaluationServer.validateModelParameters(p,job,payload);passed++;
  ObjectNode x=payload.deepCopy();x.put("model","deepseek-flash");reject(p,job,x);
  x=p.deepCopy();x.put("endpoint","https://example.invalid");reject(x,job,payload);
  x=p.deepCopy();((ObjectNode)x.path("modelSelectionProfile")).put("routeKey","tokenhub");reject(x,job,payload);
  x=job.deepCopy();x.put("id","UNKNOWN");reject(p,x,payload);
  x=p.deepCopy();x.put("maximumNewActualCalls",3);reject(x,job,payload);
  x=payload.deepCopy();((ObjectNode)x.path("messages").path(1)).put("content",payload.path("messages").path(1).path("content").asText()+" ");reject(p,job,x);
  x=payload.deepCopy();x.put("max_tokens",10000);reject(p,job,x);
  x=p.deepCopy();((ObjectNode)x.path("modelSelectionProfile")).put("unknown",true);reject(x,job,payload);
  Files.createDirectories(NaturalUsefulDeliveryEvaluationServer.ROOT.resolve("traces"));
  Path prior=NaturalUsefulDeliveryEvaluationServer.ROOT.resolve("traces/001.json");j.writeValue(prior.toFile(),Map.of("jobId",job.path("id").asText()));reject(p,job,payload);Files.delete(prior);
  JsonNode oldProtocol=j.readTree(evidence.resolve("model-selection50-development/protocol.json").toFile()),oldJob=j.readTree(evidence.resolve("model-selection50-development/jobs.json").toFile()).path(0);
  NaturalUsefulDeliveryEvaluationServer.validateOfficialModelProfile(oldProtocol,oldJob);passed++;
  System.out.println(j.writeValueAsString(Map.of("passed",passed,"failed",0,"actualModelCalls",0,"legacyGEN50AcceptedUnchanged",true)));
 }
 static void reject(JsonNode p,JsonNode job,JsonNode payload)throws Exception{try{NaturalUsefulDeliveryEvaluationServer.validateModelParameters(p,job,payload);throw new AssertionError("Unexpected native Pro52 acceptance");}catch(java.io.IOException expected){passed++;}}
}
