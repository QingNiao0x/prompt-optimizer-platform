import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
/** 只在内存验证范围与批准规则，不启动HTTP或预占真实调用。 */
public class HarnessScopeTest {
 public static void main(String[] args)throws Exception{
  var j=Candidate30EvaluationServer.JSON;Candidate30EvaluationServer.version="candidate30-live";
  var p=j.readTree(Path.of("tmp/me00a-20261012/protocol.json").toFile());var b=p.path("activeBatch");var cases=Candidate30EvaluationServer.frozenCases(b);int checks=0;
  if(Candidate30EvaluationServer.approvedBatch(p)!=b)throw new AssertionError();checks++;
  var raw=cases.get(0).path("rawPrompt").asText();var payload=j.createObjectNode().put("rawPrompt",raw);payload.putArray("conversationHistory");payload.put("questionScope","current");payload.putObject("taskIntent");payload.putArray("knownDecisions");
  if(!allowed(payload,cases,b))throw new AssertionError("exact plan");checks++;
  payload.put("rawPrompt",raw+" forged");if(allowed(payload,cases,b))throw new AssertionError("prefix input");checks++;payload.put("rawPrompt",raw);
  var generation=j.createObjectNode().put("rawPrompt",raw);generation.putArray("conversationHistory");generation.putObject("template");generation.putObject("options").put("includeExamples",false);generation.set("taskView",b.path("expectedTaskViews").get(0));
  if(!allowed(generation,cases,b))throw new AssertionError("exact generation");checks++;
  ((ObjectNode)generation.path("options")).put("includeExamples",true);if(allowed(generation,cases,b))throw new AssertionError("example toggle");checks++;((ObjectNode)generation.path("options")).put("includeExamples",false);
  generation.set("taskView",j.createObjectNode());if(allowed(generation,cases,b))throw new AssertionError("task identity");checks++;
  var closed=p.deepCopy();((ObjectNode)closed.path("activeBatch")).put("status","CLOSED");try{Candidate30EvaluationServer.approvedBatch(closed);throw new AssertionError("closed batch");}catch(java.io.IOException expected){}checks++;
  var escape=b.deepCopy();((ObjectNode)escape).put("casesFile","../outside.json");try{Candidate30EvaluationServer.frozenCases(escape);throw new AssertionError("path escape");}catch(java.io.IOException expected){}checks++;
  System.out.println("HarnessScopeTest: "+checks+" passed; 0 model calls");
 }
 static boolean allowed(JsonNode payload,JsonNode cases,JsonNode batch){var r=Candidate30EvaluationServer.JSON.createObjectNode();r.putArray("messages").addObject().put("role","user").put("content","JSON:\n"+payload);return Candidate30EvaluationServer.inputAllowed(r,cases,batch);}
}