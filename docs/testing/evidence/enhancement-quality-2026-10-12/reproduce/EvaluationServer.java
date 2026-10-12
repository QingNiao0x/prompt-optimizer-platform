import com.fasterxml.jackson.databind.*;
import com.promptoptimizer.PromptOptimizerApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Only the isolated loopback evaluation process captures authorized synthetic model traffic. */
public class EvaluationServer {
    static final Path ROOT = Path.of("tmp/enhancement-quality-20261012").toAbsolutePath();
    static final ObjectMapper JSON = new ObjectMapper();
    static String version;
    public static void main(String[] args) throws Exception {
        version = args[0];
        if (!version.matches("[a-z0-9-]+")) throw new IllegalArgumentException("Invalid version");
        Files.createDirectories(ROOT.resolve("traces"));
        new SpringApplication(PromptOptimizerApplication.class, TraceConfiguration.class).run(
            "--server.address=127.0.0.1", "--server.port=9001", "--spring.flyway.enabled=false",
            "--spring.session.redis.namespace=prompt-optimizer:quality-20261012",
            "--spring.main.banner-mode=off");
    }
    @Configuration(proxyBeanMethods=false)
    static class TraceConfiguration {
        @Bean static BeanPostProcessor evaluationTrace() {
            return new BeanPostProcessor() {
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!name.equals("openAiCompatibleRestClient")) return bean;
                    return ((RestClient)bean).mutate().requestInterceptor((request,body,execution) -> {
                        if (!"https".equals(request.getURI().getScheme()) || !"api.deepseek.com".equals(request.getURI().getHost()))
                            throw new IOException("Only authorized official DeepSeek endpoint allowed");
                        JsonNode input = JSON.readTree(body);
                        String text = input.path("messages").toString();
                        boolean authorized = false;
                        for (JsonNode c : JSON.readTree(ROOT.resolve("cases.json").toFile())) {
                            if (text.contains(JSON.writeValueAsString(c.path("rawPrompt").asText()).substring(1, JSON.writeValueAsString(c.path("rawPrompt").asText()).length()-1))) authorized = true;
                        }
                        // Payload JSON is escaped once more inside a message; compare parsed rawPrompt instead.
                        for (JsonNode msg : input.path("messages")) {
                            String content = msg.path("content").asText(); int start = content.indexOf('{');
                            if (start >= 0) try {
                                String raw = JSON.readTree(content.substring(start)).path("rawPrompt").asText();
                                for (JsonNode c : JSON.readTree(ROOT.resolve("cases.json").toFile()))
                                    if (c.path("rawPrompt").asText().strip().equals(raw.strip())) authorized = true;
                            } catch (Exception ignored) { }
                        }
                        if (!authorized) throw new IOException("Input outside frozen synthetic scope");
                        Path trace = reserve("enhancement");
                        var record = JSON.createObjectNode();
                        record.put("version",version); record.put("requestId",org.slf4j.MDC.get("requestId"));
                        record.set("request", input);
                        Files.writeString(trace, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(record));
                        ClientHttpResponse response = execution.execute(request,body);
                        byte[] bytes = response.getBody().readAllBytes();
                        JsonNode output = JSON.readTree(bytes);
                        // Reasoning and authentication headers are never included in evidence.
                        for (JsonNode choice : output.path("choices")) {
                            if (choice.path("message") instanceof com.fasterxml.jackson.databind.node.ObjectNode message) message.remove("reasoning_content");
                        }
                        record.put("httpStatus", response.getStatusCode().value()); record.set("response",output);
                        Files.writeString(trace, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(record));
                        return new ClientHttpResponse() {
                            public HttpStatusCode getStatusCode() throws IOException { return response.getStatusCode(); }
                            public String getStatusText() throws IOException { return response.getStatusText(); }
                            public HttpHeaders getHeaders() { return response.getHeaders(); }
                            public InputStream getBody() { return new ByteArrayInputStream(bytes); }
                            public void close() { response.close(); }
                        };
                    }).build();
                }
            };
        }
    }
    static synchronized Path reserve(String operation) throws IOException {
        for (int i=1;i<=310;i++) {
            Path path=ROOT.resolve("traces").resolve(String.format("%03d-%s.json",i,operation));
            if (Files.exists(ROOT.resolve("traces").resolve(String.format("%03d-downstream.json",i)))) continue;
            try { Files.createFile(path); return path; } catch (FileAlreadyExistsException e) { }
        }
        throw new IOException("Evaluation actual request budget exhausted");
    }
}
