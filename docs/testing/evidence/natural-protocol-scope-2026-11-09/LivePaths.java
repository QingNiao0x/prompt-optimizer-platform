import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.provider.domain.*;
import com.promptoptimizer.provider.infrastructure.openai.*;
import com.promptoptimizer.provider.service.PlatformModelCatalog;
import java.net.URI;
import java.nio.file.*;
import org.springframework.web.client.RestClient;

/** 固定合成输入通过实际Provider执行，调用次数由同目录转发器先行落盘。 */
public class LivePaths {
    public static void main(String[] args) throws Exception {
        var json = new ObjectMapper().findAndRegisterModules()
                .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        var directory = Path.of(args[0]);
        var properties = new OpenAiCompatibleProperties();
        properties.setEndpoint(URI.create("http://127.0.0.1:9079/chat/completions"));
        properties.setApiKey("fixture");
        properties.setModel("deepseek-flash");
        properties.setMaxTokens(10000);
        var provider = new OpenAiCompatibleNaturalPromptProvider(RestClient.create(), json, properties, (PlatformModelCatalog)null);
        for (var sample : json.readTree(directory.resolve("cases.json").toFile())) {
            String id = sample.path("id").asText();
            var input = json.treeToValue(sample.path("input"), NaturalPromptInput.class);
            ObjectNode result = json.createObjectNode().put("id", id);
            try {
                var draft = provider.enhance(input, value -> value);
                result.put("success", true).put("body", draft.optimizedPrompt());
                result.set("stages", json.valueToTree(draft.completedStages()));
            } catch (Exception failure) {
                result.put("success", false).put("failureClass", failure.getClass().getSimpleName());
                if (failure instanceof ProviderResponseValidationException invalid) {
                    result.put("reason", invalid.getReason().name()).put("field", invalid.getField());
                }
            }
            json.writerWithDefaultPrettyPrinter().writeValue(directory.resolve(id + ".json").toFile(), result);
            System.out.println(id + " success=" + result.path("success"));
        }
    }
}
