package com.promptoptimizer.textpolish.service;
import com.promptoptimizer.PromptOptimizerApplication;
import com.promptoptimizer.provider.infrastructure.openai.OpenAiCompatibleProperties;
import com.promptoptimizer.identity.mapper.UserAccountMapper;
import com.promptoptimizer.common.logging.*;
import com.promptoptimizer.textpolish.domain.TextPolishModels.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.boot.SpringApplication;
import java.nio.file.*;
import java.util.*;

/** 冻结作品的Provider层对照，不创建账户或写入业务历史。 */
public class PolishEvaluation {
 public static void main(String[] args) throws Exception {
  Path root=Path.of(args[0]).toAbsolutePath();
  var application=new SpringApplication(PromptOptimizerApplication.class);
  try(var context=application.run("--server.port=0","--server.address=127.0.0.1","--spring.flyway.enabled=false")) {
   var json=context.getBean(ObjectMapper.class);
   var account=json.readTree(Files.readString(Path.of("tmp/local-acceptance/account.json")));
   if(!account.path("kind").asText().equals("LOCAL_SYNTHETIC_FIXTURE")) throw new IllegalStateException("Canonical fixture required");
   UUID userId=UUID.fromString(account.path("userId").asText());
   UUID tenantId=UUID.fromString(account.path("tenantId").asText());
   var users=context.getBean(UserAccountMapper.class);
   var user=users.selectById(userId);
   if(user==null || !user.getTenantId().equals(tenantId) || !users.selectDefaultWorkspaceId(userId,tenantId).toString().equals(account.path("workspaceId").asText())) throw new IllegalStateException("Canonical scope mismatch");
   var models=context.getBean(TextPolishModelService.class);
   var model=models.require(null);
   var props=context.getBean(OpenAiCompatibleProperties.class);
   Map<String,Object> metadata=new LinkedHashMap<>();
   metadata.put("modelId",model.publicId());metadata.put("displayName",model.displayName());metadata.put("routeKey",model.routeKey());metadata.put("upstreamModel",model.upstreamModel());
   metadata.put("temperature",props.getTemperature());metadata.put("maxTokens",Math.min(8000,props.getMaxTokens()));metadata.put("jsonResponseFormat",props.isJsonResponseFormatEnabled());metadata.put("canonicalScopeVerified",true);
   if(args[1].equals("inspect")) {json.writerWithDefaultPrettyPrinter().writeValue(root.resolve("metadata.json").toFile(),metadata);return;}
   var frozen=json.readTree(root.resolve("metadata.json").toFile());
   if(!frozen.equals(json.valueToTree(metadata))) throw new IllegalStateException("Runtime drift");
   var protocol=json.readTree(root.resolve("protocol.json").toFile());
   var ledger=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(root.resolve("ledger.json").toFile());
   if(ledger.path("closed").asBoolean()) throw new IllegalStateException("Closed batch");
   var provider=context.getBean(OpenAiTextPolishProvider.class);
   for(var job:protocol.path("jobs")) {
    if(!job.path("phase").asText().equals(args[1])) continue;
    String id=job.path("id").asText(); Path target=root.resolve(id+".json");
    if(Files.exists(target)) continue;
    if(ledger.path("reserved").asInt()!=0) throw new IllegalStateException("Unsettled reservation");
    int used=ledger.path("actualCalls").asInt();
    if(used+2>50) throw new IllegalStateException("Budget exhausted");
    ledger.put("reserved",2);ledger.put("activeJob",id);json.writerWithDefaultPrettyPrinter().writeValue(root.resolve("ledger.json").toFile(),ledger);
    var input=json.treeToValue(job.path("input"),Input.class);
    provider.configureStrategy(job.path("version").asText());
    List<ModelCallObservation> observations=new ArrayList<>();
    Map<String,Object> result=new LinkedHashMap<>();result.put("job",job);result.put("metadata",metadata);
    long start=System.nanoTime();
    try(var scope=ModelCallAuditContext.bind(new ModelCallAuditContext.Snapshot(tenantId,(tenant,call)->observations.add(call)))) {
     result.put("polishedText",provider.polish(input,models.require(model.publicId())));result.put("status","COMPLETED");
    } catch(RuntimeException failure) {result.put("status","FAILED");result.put("failureClass",failure.getClass().getSimpleName());}
    result.put("elapsedMs",(System.nanoTime()-start)/1000000);result.put("observations",observations);
    json.writerWithDefaultPrettyPrinter().writeValue(target.toFile(),result);
    int charged=observations.isEmpty()?2:observations.size();
    ledger.put("actualCalls",used+charged);ledger.put("reserved",0);ledger.remove("activeJob");
    ((com.fasterxml.jackson.databind.node.ArrayNode)ledger.get("entries")).addObject().put("id",id).put("calls",charged).put("status",String.valueOf(result.get("status")));
    json.writerWithDefaultPrettyPrinter().writeValue(root.resolve("ledger.json").toFile(),ledger);
    System.out.println("EVAL_RESULT "+id+" "+result.get("status")+" total="+(used+charged));
    if(observations.isEmpty()) throw new IllegalStateException("Audit missing, charged reserved maximum");
   }
  }
 }
}
