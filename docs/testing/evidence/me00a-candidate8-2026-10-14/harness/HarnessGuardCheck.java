import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;

/** 不访问网络、不预留调用槽位，核对真实回放与输入／批准保护。 */
public class HarnessGuardCheck {
    /** 使用既有真实原件和当前协议检查兼容性，非法输入须在真实调用前拒绝。 */
    public static void main(String[] args) throws Exception {
        MeEvaluationServer.version = "candidate8";
        var json = MeEvaluationServer.JSON;
        JsonNode protocol = json.readTree(MeEvaluationServer.ROOT.resolve("protocol.json").toFile());
        JsonNode batch = MeEvaluationServer.approvedBatch(protocol);
        JsonNode cases = MeEvaluationServer.frozenCases(batch);
        int checks = 0;
        for (String number : new String[]{"177", "178", "182"}) {
            JsonNode input = json.readTree(MeEvaluationServer.ROOT.resolve("traces/" + number + ".json").toFile()).path("request");
            require(MeEvaluationServer.inputAllowed(input, cases, batch), "Existing actual input rejected"); checks++;
        }
        require(!MeEvaluationServer.inputAllowed(json.readTree("{\"messages\":[{\"content\":\"{\\\"rawPrompt\\\":\\\"unrelated task\\\"}\"}]}"), cases, batch), "Unrelated task allowed"); checks++;
        var pending = protocol.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) pending.path("activeBatch")).put("authorizationStatus", "PENDING");
        reject(() -> MeEvaluationServer.approvedBatch(pending)); checks++;
        var wrongVersion = protocol.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) wrongVersion.path("activeBatch")).put("version", "candidate7");
        reject(() -> MeEvaluationServer.approvedBatch(wrongVersion)); checks++;
        var wrongPath = batch.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) wrongPath).put("casesFile", "../account.json");
        reject(() -> MeEvaluationServer.frozenCases(wrongPath)); checks++;
        var wrongHash = batch.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) wrongHash).put("casesSha256", "0".repeat(64));
        reject(() -> MeEvaluationServer.frozenCases(wrongHash)); checks++;
        var examples = batch.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) examples).put("casesFile", "candidate8-example-cases.json")
                .put("casesSha256", "0b6560e0cbac11f9dff77ff8bc07086feed5c5edccd3c1f0ef79b28aa614007c");
        ((com.fasterxml.jackson.databind.node.ObjectNode) examples).putArray("cases").add("EX01");
        JsonNode exampleCases = MeEvaluationServer.frozenCases(examples);
        String raw = exampleCases.get(0).path("rawPrompt").asText();
        var input = json.createObjectNode();
        input.putArray("messages").addObject().put("content", "input:\n" + json.createObjectNode().put("rawPrompt", raw));
        require(MeEvaluationServer.inputAllowed(input, exampleCases, examples), "Approved example rejected"); checks++;
        input.path("messages").get(0).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) input.path("messages").get(0)).put("content", "input:\n" + json.createObjectNode().put("rawPrompt", exampleCases.get(1).path("rawPrompt").asText()));
        require(!MeEvaluationServer.inputAllowed(input, exampleCases, examples), "Unselected example allowed"); checks++;
        java.nio.file.Files.writeString(Path.of(args[0]), "{\"checks\":" + checks + ",\"failures\":0,\"actualModelCalls\":0}\n");
    }
    private static void require(boolean ok, String message) { if (!ok) throw new IllegalStateException(message); }
    private static void reject(CheckedAction action) throws Exception {
        try { action.run(); } catch (java.io.IOException expected) { return; }
        throw new IllegalStateException("Unsafe request accepted");
    }
    @FunctionalInterface private interface CheckedAction { void run() throws Exception; }
}
