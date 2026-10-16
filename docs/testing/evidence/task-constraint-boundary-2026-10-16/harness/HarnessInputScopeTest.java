import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.template.service.impl.PromptTemplateRegistryImpl;
import java.nio.file.*;
import java.util.*;

/** 离线核对当前模板选择与评测拦截器，不调用模型或修改产品。 */
public class HarnessInputScopeTest {
    public static void main(String[] args) throws Exception {
        var json = new ObjectMapper();
        var root = Path.of("tmp/me00a-20261012");
        var batch = json.readTree(root.resolve("protocol.json").toFile()).path("activeBatch");
        var cases = json.readTree(root.resolve(batch.path("casesFile").asText()).toFile());
        String raw = cases.get(0).path("rawPrompt").asText();
        String template = new PromptTemplateRegistryImpl().resolve(TemplateCode.AUTO, raw).code().name();
        String payload = json.writeValueAsString(Map.of("rawPrompt", raw, "template", Map.of("code", template)));
        var input = json.valueToTree(Map.of("messages", List.of(Map.of("role", "user", "content", payload))));
        boolean accepted = MeEvaluationServer.inputAllowed(input, cases, batch);
        var changed = json.valueToTree(Map.of("messages", List.of(Map.of("role", "user", "content",
                json.writeValueAsString(Map.of("rawPrompt", raw + "新增其他任务", "template", Map.of("code", template)))))));
        boolean changedAccepted = MeEvaluationServer.inputAllowed(changed, cases, batch);
        var result = Map.of("actualTemplate", template, "frozenRequestsInputAccepted", accepted,
                "changedInputAccepted", changedAccepted, "newModelCalls", 0);
        Files.writeString(Path.of("tmp/me00a-candidate16-20261016/harness-input-scope-" + args[0] + ".json"),
                json.writerWithDefaultPrettyPrinter().writeValueAsString(result), StandardOpenOption.CREATE_NEW);
        if (changedAccepted || accepted != args[0].equals("green")) throw new IllegalStateException("Unexpected scope verdict");
    }
}
