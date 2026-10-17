import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
/** 只在内存验证范围与批准规则，不启动HTTP或预占真实调用。 */
public class HarnessScopeTest {
 public static void main(String[] args)throws Exception{
  var j=Candidate31EvaluationServer.JSON;Candidate31EvaluationServer.version="candidate31-live";
  var p=j.createObjectNode();var b=p.putObject("activeBatch");
  b.put("version","candidate31-live").put("authorizationStatus","APPROVED_BY_USER").put("secondRoundAuthorized",true).put("status","OPEN");
  b.put("casesFile","candidate31-project-cases.json");
  byte[] source=java.nio.file.Files.readAllBytes(Path.of("tmp/me00a-20261012/candidate31-project-cases.json"));
  b.put("casesSha256",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(source)));
  b.putArray("expectedTaskViews").add(j.readTree(Path.of("tmp/me00a-candidate31-live-20261017/taskview-existing.json").toFile()));var cases=Candidate31EvaluationServer.frozenCases(b);int checks=0;
  if(Candidate31EvaluationServer.approvedBatch(p)!=b)throw new AssertionError();checks++;
  var raw=cases.get(0).path("rawPrompt").asText();var payload=j.createObjectNode().put("rawPrompt",raw);payload.putArray("conversationHistory");payload.put("questionScope","current");payload.putObject("taskIntent");payload.putArray("knownDecisions");
  if(!allowed(payload,cases,b))throw new AssertionError("exact plan");checks++;
  payload.put("rawPrompt",raw+" forged");if(allowed(payload,cases,b))throw new AssertionError("prefix input");checks++;payload.put("rawPrompt",raw);
  var generation=j.createObjectNode().put("rawPrompt",raw);generation.putArray("conversationHistory");generation.putObject("template");generation.putObject("options").put("includeExamples",false);generation.set("taskView",b.path("expectedTaskViews").get(0));
  if(!allowed(generation,cases,b))throw new AssertionError("exact generation");checks++;
  ((ObjectNode)generation.path("options")).put("includeExamples",true);if(allowed(generation,cases,b))throw new AssertionError("example toggle");checks++;((ObjectNode)generation.path("options")).put("includeExamples",false);
  generation.set("taskView",j.createObjectNode());if(allowed(generation,cases,b))throw new AssertionError("task identity");checks++;
  var closed=p.deepCopy();((ObjectNode)closed.path("activeBatch")).put("status","CLOSED");try{Candidate31EvaluationServer.approvedBatch(closed);throw new AssertionError("closed batch");}catch(java.io.IOException expected){}checks++;
  var escape=b.deepCopy();((ObjectNode)escape).put("casesFile","../outside.json");try{Candidate31EvaluationServer.frozenCases(escape);throw new AssertionError("path escape");}catch(java.io.IOException expected){}checks++;
  System.out.println("HarnessScopeTest: "+checks+" passed; 0 model calls");
 }
 static boolean allowed(JsonNode payload,JsonNode cases,JsonNode batch){var r=Candidate31EvaluationServer.JSON.createObjectNode();r.putArray("messages").addObject().put("role","user").put("content","JSON:\n"+payload);return Candidate31EvaluationServer.inputAllowed(r,cases,batch);}
}