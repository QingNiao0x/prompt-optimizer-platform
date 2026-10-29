import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.provider.domain.NaturalPromptInput;
import com.promptoptimizer.provider.domain.ProviderException;
import com.promptoptimizer.provider.infrastructure.openai.OpenAiCompatibleNaturalPromptProvider;
import com.promptoptimizer.provider.infrastructure.openai.OpenAiCompatibleProperties;
import com.promptoptimizer.provider.infrastructure.openai.OpenAiCompatibleRouteProperties;
import com.promptoptimizer.provider.service.PlatformModelCatalog;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Capture actual49 Provider request construction using synthetic preparation and a factory that cannot use network. */
public final class Registry49ProductRequestCapture {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    /** These three known development inputs prove construction only; real extraction is left to the frozen live stage. */
    public static void main(String[] args) throws Exception {
        Path repo = Path.of(args[0]);
        if (args.length > 2 && List.of("24","53").contains(args[1]) && args[2].equals("--first-only")) {
            captureRequirementsOnlyFirst(repo,args[1]); return;
        }
        String version = args.length > 1 ? args[1] : "49";
        if (!List.of("49", "50", "51").contains(version)) throw new IllegalArgumentException("Only finite registry49/50/51 offline capture is allowed");
        Path evidence = repo.resolve("docs/testing/evidence/natural-useful-delivery-2026-10-28");
        Path output = evidence.resolve("registry" + version + "-zero-call-capture");
        List<Object> reports = new ArrayList<>();
        for (String id : List.of("Q01", "U02", "U03")) {
            Path target = output.resolve(id); Files.createDirectories(target);
            JsonNode source = JSON.readTree(evidence.resolve("writer-thinking-preparation/requests/" + id + ".json").toFile());
            String oldUser = source.path("messages").path(1).path("content").asText();
            ObjectNode oldInput = (ObjectNode) JSON.readTree(oldUser.substring(oldUser.indexOf('{')));
            oldInput.remove(List.of("taskScope", "taskSources", "inputBoundary"));
            oldInput.put("strategyVersion", version);
            NaturalPromptInput input = JSON.treeToValue(oldInput, NaturalPromptInput.class);
            ObjectNode preparation = JSON.createObjectNode();
            preparation.set("actions", JSON.valueToTree(List.of(Map.of("text", input.rawPrompt(), "timing", "CURRENT", "condition", "",
                    "evidence", List.of(Map.of("sourceId", "rawPrompt", "quote", input.rawPrompt()))))));
            preparation.set("response", JSON.valueToTree(Map.of("basis", "UNSPECIFIED", "description", "", "evidence", List.of())));
            preparation.set("requirements", JSON.valueToTree(List.of(Map.of("text", input.rawPrompt(), "dimensions", List.of("GOAL"),
                    "evidence", List.of(Map.of("sourceId", "rawPrompt", "quote", input.rawPrompt()))))));
            save(target.resolve("preparation-fixture.json"), preparation);
            save(target.resolve("original-input.json"), JSON.valueToTree(input));
            Path runtime = repo.resolve("tmp/registry" + version + "-controlled-http/" + id); Files.createDirectories(runtime.resolve("traces"));
            ObjectNode job = JSON.createObjectNode(); job.put("id", id + "_V" + version + "_GEN").put("phase", "GENERATION").put("version", version);
            ObjectNode protocol = JSON.createObjectNode(); protocol.put("modelId", "deepseek:deepseek-flash");
            List<NaturalRegistry49CallGuard.Audit> audits = new ArrayList<>();
            var route = new OpenAiCompatibleRouteProperties(); route.setProviderName("deepseek"); route.setEndpoint(URI.create("https://api.deepseek.com/chat/completions"));
            route.setApiKey("synthetic-offline-fixture"); route.setModel("deepseek-flash");
            var properties = new OpenAiCompatibleProperties(); properties.setMultiProviderEnabled(true); properties.setDefaultProvider("deepseek");
            properties.setProviders(Map.of("deepseek", route)); properties.setMaxTokens(8192); properties.setTemperature(0.2); properties.setJsonResponseFormatEnabled(true);
            RestClient client = RestClient.builder().requestFactory((uri, method) -> new ClientHttpRequest() {
                private final ByteArrayOutputStream body = new ByteArrayOutputStream();
                private final HttpHeaders headers = new HttpHeaders();
                public HttpMethod getMethod() { return method; }
                public URI getURI() { return uri; }
                public HttpHeaders getHeaders() { return headers; }
                public OutputStream getBody() { return body; }
                public ClientHttpResponse execute() throws IOException {
                    JsonNode request = JSON.readTree(body.toByteArray());
                    int number = audits.size() + 1;
                    if (number > 2) throw new AssertionError("Unexpected retry in a zero-call capture");
                    String user = request.path("messages").path(1).path("content").asText();
                    JsonNode task = JSON.readTree(user.substring(user.indexOf('{')));
                    var audit = NaturalRegistry49CallGuard.verify(JSON, runtime, protocol, job, task); audits.add(audit);
                    save(target.resolve(number == 1 ? "preparation-request.json" : "writer-request.json"), request);
                    if (!request.path("model").asText().equals("deepseek-flash") || request.path("max_tokens").asInt() != 8192
                            || request.path("temperature").asDouble() != 0.2 || !request.path("thinking").path("type").asText().equals("disabled")
                            || !request.path("response_format").path("type").asText().equals("json_object") || request.path("stream").asBoolean())
                        throw new AssertionError("Captured product parameters changed");
                    if (number == 2) {
                        save(target.resolve("normalized-registry.json"), task.path("preparedRequirements"));
                        throw new IOException("CONTROLLED_CAPTURE_COMPLETE_WITHOUT_UPSTREAM_OR_WRITER_RESPONSE");
                    }
                    JsonNode response = JSON.valueToTree(Map.of("model", "deepseek-flash", "choices", List.of(Map.of("finish_reason", "stop",
                            "message", Map.of("content", JSON.writeValueAsString(preparation))))));
                    save(target.resolve("synthetic-preparation-response.json"), response);
                    JSON.writeValue(runtime.resolve("traces/001.json").toFile(), Map.of("jobId", job.path("id").asText(), "httpStatus", 200,
                            "request", request, "response", response, "syntheticOfflineFixture", true, "actualModelCalls", 0));
                    byte[] bytes = JSON.writeValueAsBytes(response);
                    return new ClientHttpResponse() {
                        public HttpStatusCode getStatusCode() { return HttpStatusCode.valueOf(200); }
                        public String getStatusText() { return "Controlled offline fixture"; }
                        public HttpHeaders getHeaders() { HttpHeaders h = new HttpHeaders(); h.add("Content-Type", "application/json"); return h; }
                        public InputStream getBody() { return new ByteArrayInputStream(bytes); }
                        public void close() { }
                    };
                }
            }).build();
            var provider = new OpenAiCompatibleNaturalPromptProvider(client, JSON, properties, (PlatformModelCatalog) null);
            boolean stopped = false;
            try { provider.enhance(input, draft -> { throw new AssertionError("Capture should not manufacture a writer result"); }); }
            catch (ProviderException expected) { stopped = true; }
            if (!stopped || audits.size() != 2 || !audits.get(0).callStage().equals("REQUIREMENT_PREPARATION")
                    || !audits.get(1).callStage().equals("BODY_GENERATION")
                    || !audits.get(0).originalInputSha256().equals(audits.get(1).originalInputSha256()))
                throw new AssertionError("Actual registry" + version + " branch was not captured twice with unchanged full input");
            var report = Map.of("caseId", id, "strategyVersion", version, "status", "ACTUAL_PROVIDER_BRANCH_CAPTURE_WITH_SYNTHETIC_PREPARATION_ONLY", "actualModelCalls", 0,
                    "capturedHttpAttempts", 2, "audits", audits, "registryFromActualProductNormalization", true,
                    "preparationExtractionQualityProven", false, "writerResultManufactured", false,
                    "boundary", "Same closed complete NaturalPromptInput with version" + version + "; no new UI or Controller capture. The first response is deliberately synthetic and incomplete as task analysis. Second request is captured and the network-disabled factory deliberately aborts before any writer response.");
            save(target.resolve("capture-review.json"), JSON.valueToTree(report)); reports.add(report);
        }
        save(output.resolve("summary.json"), JSON.valueToTree(Map.of("status", "CONSTRUCTION_CHECKS_PASS_NOT_EXTRACTION_OR_QUALITY", "modelCalls", 0, "cases", reports)));
        System.out.println(JSON.writeValueAsString(Map.of("cases", 3, "capturedActualProviderRequests", 6, "modelCalls", 0,
                "preparationFixtureOnlyNotRealExtractionEvidence", true, "output", output.toString())));
    }

    /** Capture only real53 first requests from the complete closed52 inputs; no preparation response or registry is fabricated. */
    private static void captureRequirementsOnlyFirst(Path repo,String version) throws Exception {
        Path evidence=repo.resolve("docs/testing/evidence/natural-useful-delivery-2026-10-28"), output=evidence.resolve(version.equals("53")?"registry53-first-layer-preparation/captures":"natural24-singlecall-preparation/captures");
        Files.createDirectories(output); List<Object> reports=new ArrayList<>();
        for(String id:List.of("P02_CONFIRMED","P02_UNKNOWN")) {
            String number=id.equals("P02_CONFIRMED")?"001":"003";
            JsonNode trace=JSON.readTree(evidence.resolve("registry52-plan-completion/traces/"+number+".json").toFile());
            String oldUser=trace.path("request").path("messages").path(1).path("content").asText();
            ObjectNode source=(ObjectNode)JSON.readTree(oldUser.substring(oldUser.indexOf('{'))), data=source.deepCopy();
            data.remove(List.of("taskSources","inputBoundary")); data.put("strategyVersion",version);
            NaturalPromptInput input=JSON.treeToValue(data,NaturalPromptInput.class); int[] attempts={0};
            var route=new OpenAiCompatibleRouteProperties();route.setProviderName("deepseek");route.setEndpoint(URI.create("https://api.deepseek.com/chat/completions"));
            route.setApiKey("synthetic-offline-fixture");route.setModel("deepseek-flash");
            var properties=new OpenAiCompatibleProperties();properties.setMultiProviderEnabled(true);properties.setDefaultProvider("deepseek");properties.setProviders(Map.of("deepseek",route));
            properties.setMaxTokens(8192);properties.setTemperature(.2);properties.setJsonResponseFormatEnabled(true);
            RestClient client=RestClient.builder().requestFactory((uri,method)->new ClientHttpRequest(){
                private final ByteArrayOutputStream body=new ByteArrayOutputStream();private final HttpHeaders headers=new HttpHeaders();
                public HttpMethod getMethod(){return method;}public URI getURI(){return uri;}public HttpHeaders getHeaders(){return headers;}public OutputStream getBody(){return body;}
                public ClientHttpResponse execute()throws IOException{
                    if(++attempts[0]!=1)throw new AssertionError("Unexpected second request in first-only capture");
                    JsonNode request=JSON.readTree(body.toByteArray());String user=request.path("messages").path(1).path("content").asText();
                    ObjectNode task=(ObjectNode)JSON.readTree(user.substring(user.indexOf('{'))),reverted=task.deepCopy();reverted.put("strategyVersion","52");
                    ObjectNode comparable=source.deepCopy();if(version.equals("24"))comparable.remove("inputBoundary");
                    if(!reverted.equals(comparable)||task.has("taskScope")||task.has("preparedRequirements")||task.has("boundQuestionHints"))throw new AssertionError("Original complete input changed beyond finite version and existing derived boundary protocol");
                    try{
                        String expected=version.equals("53")?(String)Class.forName("com.promptoptimizer.provider.domain.NaturalPromptRequirementsOnlyInstructions").getField("PREPARE").get(null)
                                :(String)Class.forName("com.promptoptimizer.provider.domain.NaturalPromptInstructions").getMethod("enhancementInstruction",String.class).invoke(null,"24");
                        if(!request.path("messages").path(0).path("content").asText().equals(expected))throw new AssertionError("Wrong actual finite Provider instruction");
                    }catch(ReflectiveOperationException failure){throw new IOException("Actual53 instruction unavailable");}
                    if(request.path("messages").size()!=2||!request.path("model").asText().equals("deepseek-flash")||request.path("max_tokens").asInt()!=8192
                            ||request.path("temperature").asDouble()!=.2||request.path("stream").asBoolean()||!request.path("thinking").path("type").asText().equals("disabled")
                            ||!request.path("response_format").path("type").asText().equals("json_object"))throw new AssertionError("Unexpected capture parameters");
                    save(output.resolve(id+".request.json"),request);
                    throw new IOException("CONTROLLED53_FIRST_REQUEST_CAPTURE_NO_UPSTREAM_NO_RESPONSE");
                }
            }).build();
            var provider=new OpenAiCompatibleNaturalPromptProvider(client,JSON,properties,(PlatformModelCatalog)null);boolean stopped=false;
            try{provider.enhance(input,draft->{throw new AssertionError("First-only capture cannot manufacture a draft");});}catch(ProviderException expected){stopped=true;}
            if(!stopped||attempts[0]!=1)throw new AssertionError("Actual53 first-only Provider path was not captured");
            reports.add(Map.of("caseId",id,"actualProviderHttpConstructionAttempts",1,"actualModelCalls",0,"originalCoreInputOnlyVersionChanged",true,
                    "syntheticPreparationReturned",false,"registryOrWriterManufactured",false,"actualReaderForLaterReplay",version.equals("53")?"requirementsOnlyPreparationReader":"diagnosticEnhancementReader",
                    "derived52InputBoundaryAbsentInExisting24Protocol",version.equals("24")));
        }
        save(output.resolve("capture-report.json"),JSON.valueToTree(Map.of("status","ACTUAL_FINITE_PROVIDER_FIRST_REQUEST_CAPTURE_WITHOUT_UPSTREAM_OR_RESPONSE","strategyVersion",version,"actualModelCalls",0,"cases",reports)));
        System.out.println(JSON.writeValueAsString(Map.of("capturedActualProviderRequests",2,"actualModelCalls",0,"syntheticPreparationReturned",false)));
    }

    /** Save only approved synthetic bodies and fixed diagnostics; never headers, credentials or hidden reasoning. */
    private static void save(Path path, JsonNode value) throws IOException {
        Files.write(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(value), StandardOpenOption.CREATE_NEW);
    }
}
