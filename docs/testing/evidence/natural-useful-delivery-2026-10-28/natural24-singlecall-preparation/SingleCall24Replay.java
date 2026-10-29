package com.promptoptimizer.provider.infrastructure.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.enhancement.domain.ProviderMetadata;
import com.promptoptimizer.provider.domain.NaturalPromptInput;
import com.promptoptimizer.provider.domain.NaturalPromptDraft;
import com.promptoptimizer.provider.domain.NaturalPromptPhasePayloads;
import com.promptoptimizer.provider.domain.NaturalPromptRequirementsOnlyPreparation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;

/** Thin24 adapter over the existing network-disabled product replay; extra53 goal check is mechanism-only. */
public final class SingleCall24Replay {
 public static void main(String[] args)throws Exception{
  Path repo=Path.of(args[0]);String id=args[1];if(!List.of("P02_CONFIRMED","P02_UNKNOWN").contains(id))throw new IllegalArgumentException("Finite case required");
  Path evidence=repo.resolve("docs/testing/evidence/natural-useful-delivery-2026-10-28"),stage=evidence.resolve("natural24-singlecall");
  String jobId=id+"_V24_SINGLE_EXEC";JsonNode job=null;for(JsonNode item:NaturalModelResultReplay.read(stage.resolve("jobs.json")))if(item.path("id").asText().equals(jobId))job=item;
  if(job==null)throw new IllegalArgumentException("Frozen job missing");
  String text=job.path("approvedModelRequest").path("messages").path(1).path("content").asText();ObjectNode data=(ObjectNode)NaturalModelResultReplay.JSON.readTree(text.substring(text.indexOf('{')));data.remove("taskSources");
  NaturalPromptInput input=NaturalModelResultReplay.JSON.treeToValue(data,NaturalPromptInput.class);
  if(!input.strategyVersion().equals("24"))throw new IllegalStateException("Actual24 required");
  Path resultPath=repo.resolve("tmp/natural-useful-delivery-2026-10-28/natural24-singlecall/results/"+jobId+".json");JsonNode result=NaturalModelResultReplay.read(resultPath);
  var report=new LinkedHashMap<String,Object>();report.put("actualModelCalls",0);report.put("caseId",id);report.put("semanticCorrectnessClaimed",false);
  report.put("resultSha256",NaturalModelResultReplay.hash(Files.readAllBytes(resultPath)));report.put("reviewOrder",List.of("optimizedPrompt semantic review","task semantic review"));
  NaturalModelResultReplay replay=new NaturalModelResultReplay();String phase="finishReason";
  try{
   if(result.path("status").asInt()!=200||!result.path("body").path("data").path("finishReason").asText().equals("stop"))throw new IllegalStateException("Incomplete native response");
   phase="actual24StrictReaderAndResolve";JsonNode parsed=replay.parseModelContent(result.path("body").path("data").path("content").asText());
   report.put("observedRawOptimizedPromptSha256",NaturalModelResultReplay.hash(parsed.path("optimizedPrompt").asText()));
   NaturalPromptPhasePayloads.Enhancement payload=replay.strictReader("diagnosticEnhancementReader").readValue(parsed);
   NaturalPromptDraft draft=payload.toDraft(input,new ProviderMetadata("deepseek","deepseek:deepseek-flash",false));report.put(phase,"PASS");
   phase="originalApplicationSourceSafetyRawAndAssembled";var checked=replay.checkDraft(draft,input);
   report.put("originalApplicationSteps",checked.steps());report.put(phase,checked.firstRejection()==null?"PASS":"FAIL");
   if(checked.firstRejection()!=null)report.put("originalFirstRejection",checked.firstRejection());
   if(checked.assembled()!=null)report.put("assembledBodySha256",NaturalModelResultReplay.hash(checked.assembled()));
   phase="mechanismOnly53UserGoalSourceCheck";NaturalPromptRequirementsOnlyPreparation.validateUserGoals(draft.taskUnderstanding());report.put(phase,"PASS");
   report.put("mechanismChecksPassed",checked.firstRejection()==null);
  }catch(Throwable failure){report.put("mechanismChecksPassed",false);report.put("firstFailureAt",phase);report.put("rejection",NaturalModelResultReplay.reason(failure));}
  Path output=evidence.resolve("natural24-singlecall-preparation/"+id+"-validation.json");Files.write(output,NaturalModelResultReplay.JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(report),StandardOpenOption.CREATE_NEW);
  System.out.println(NaturalModelResultReplay.JSON.writeValueAsString(report));
 }
}
