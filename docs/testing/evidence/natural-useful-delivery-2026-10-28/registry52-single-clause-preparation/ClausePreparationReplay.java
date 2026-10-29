import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.provider.domain.NaturalPromptInput;
import com.promptoptimizer.provider.infrastructure.openai.OpenAiCompatibleNaturalPromptProvider;
import com.promptoptimizer.provider.infrastructure.openai.OpenAiCompatibleProperties;
import com.promptoptimizer.provider.service.PlatformModelCatalog;
import org.springframework.web.client.RestClient;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Read one immutable result from this finite mechanism using the actual old52 strict reader; no HTTP factory can send. */
public final class ClausePreparationReplay {
 public static void main(String[] args)throws Exception{
  ObjectMapper json=new ObjectMapper().findAndRegisterModules();Path repo=Path.of(args[0]);String id=args[1];
  boolean only53=args.length>2&&args[2].equals("53");
  if(!List.of("P02_CONFIRMED","P02_UNKNOWN").contains(id))throw new IllegalArgumentException("Finite case required");
  String stageName=only53?"registry53-requirements-prepare":"registry52-single-clause-prepare";
  String outputDirectory=only53?"registry53-first-layer-preparation":"registry52-single-clause-preparation";
  Path evidence=repo.resolve("docs/testing/evidence/natural-useful-delivery-2026-10-28"),stage=evidence.resolve(stageName);
  JsonNode protocol=json.readTree(stage.resolve("protocol.json").toFile());if(!protocol.path("status").asText().equals("FROZEN"))throw new IllegalStateException("Frozen source required");
  String jobId=id+(only53?"_V53_REQUIREMENTS_PREPARE":"_V52_CLAUSE_PREPARE");JsonNode job=null;for(JsonNode item:json.readTree(stage.resolve("jobs.json").toFile()))if(item.path("id").asText().equals(jobId))job=item;
  if(job==null)throw new IllegalArgumentException("Frozen job missing");
  String user=job.path("approvedModelRequest").path("messages").path(1).path("content").asText();ObjectNode input=(ObjectNode)json.readTree(user.substring(user.indexOf('{')));
  input.remove(List.of("taskSources","inputBoundary"));NaturalPromptInput original=json.treeToValue(input,NaturalPromptInput.class);
  if(!original.strategyVersion().equals(only53?"53":"52"))throw new IllegalStateException("Wrong finite wire version");
  Path resultPath=repo.resolve("tmp/natural-useful-delivery-2026-10-28/"+stageName+"/results/"+jobId+".json");
  byte[] bytes=Files.readAllBytes(resultPath);JsonNode result=json.readTree(bytes);boolean ok=false;String error="";
  if(result.path("status").asInt()==200&&result.path("body").path("data").path("finishReason").asText().equals("stop")){
   try{Object prepared;
    if(only53)prepared=resolve53(json,original,result.path("body").path("data").path("content").asText());
    else{Method read=Class.forName("NaturalRegistry49CallGuard").getDeclaredMethod("normalizeActualPreparation",ObjectMapper.class,NaturalPromptInput.class,String.class);read.setAccessible(true);prepared=read.invoke(null,json,original,result.path("body").path("data").path("content").asText());}
    if(!prepared.getClass().getName().endsWith(only53?"NaturalPromptRequirementsOnlyPreparation$Registered":"NaturalPromptRequirementRegistry$Prepared"))throw new IllegalStateException("Wrong reader product");ok=true;
    if(only53)Files.write(evidence.resolve(outputDirectory+"/"+id+"-registered.json"),json.writerWithDefaultPrettyPrinter().writeValueAsBytes(prepared),StandardOpenOption.CREATE_NEW);
   }catch(InvocationTargetException rejected){error=rejected.getCause().getClass().getSimpleName();}
   catch(IOException rejected){error=rejected.getClass().getSimpleName();}
  }
  JsonNode report=json.valueToTree(Map.of("caseId",id,"actualModelCalls",0,only53?"strict53RequirementsOnlyReaderAndResolve":"strictOld52ReaderAndResolve",ok,"semanticPassClaimed",false,
    "resultSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),"errorType",error,"selectedReader",only53?"requirementsOnlyPreparationReader":"requirementPreparationReader"));
  Path output=evidence.resolve(outputDirectory+"/"+id+"-strict-report.json");Files.write(output,json.writerWithDefaultPrettyPrinter().writeValueAsBytes(report),StandardOpenOption.CREATE_NEW);
  System.out.println(json.writeValueAsString(report));
 }
 /** Use the real strict53 reader and Registered resolver with a network-denied HTTP factory. */
 private static Object resolve53(ObjectMapper json,NaturalPromptInput input,String content)throws Exception{
  RestClient client=RestClient.builder().requestFactory((uri,method)->{throw new IOException("Offline53 replay cannot use HTTP");}).build();
  var provider=new OpenAiCompatibleNaturalPromptProvider(client,json,new OpenAiCompatibleProperties(),(PlatformModelCatalog)null);
  Method unwrap=OpenAiCompatibleNaturalPromptProvider.class.getDeclaredMethod("unwrap",String.class),read=OpenAiCompatibleNaturalPromptProvider.class.getDeclaredMethod("readResponse",String.class);
  var field=OpenAiCompatibleNaturalPromptProvider.class.getDeclaredField("requirementsOnlyPreparationReader");unwrap.setAccessible(true);read.setAccessible(true);field.setAccessible(true);
  JsonNode body=(JsonNode)read.invoke(provider,(String)unwrap.invoke(provider,content));Object preparation=((ObjectReader)field.get(provider)).readValue(body);
  return preparation.getClass().getMethod("resolve",NaturalPromptInput.class).invoke(preparation,input);
 }
}
