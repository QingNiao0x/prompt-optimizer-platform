import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 离线检查真实传输层使用的预留函数；只写隔离计数夹具，不启动Spring、访问数据库或发送网络请求。 */
public final class InfrastructureReservationSelfTest {
    public static void main(String[] args) throws Exception {
        Path fixture = Path.of(args[0]).toAbsolutePath();
        String scenario = args[1];
        Path runtime = fixture.resolve("runtime").resolve(scenario);
        Path stage = fixture.resolve("evidence").resolve(scenario);
        Files.createDirectories(runtime.resolve("traces"));
        Files.createDirectories(stage);
        var json = new ObjectMapper();
        Files.writeString(stage.getParent().resolve("task-protocol.json"), "{\"status\":\"FROZEN\",\"selectedMaximumActualCalls\":48,\"previousActualCalls\":0}");
        Files.writeString(stage.resolve("protocol.json"), "{\"status\":\"FROZEN\",\"taskMaximumActualCalls\":48,\"maximumNewActualCalls\":48,\"phaseLimits\":{\"GENERATION\":1}}");
        System.setProperty("natural.eval.root", runtime.toString());
        System.setProperty("natural.eval.evidence", stage.toString());
        var job = json.readTree("{\"id\":\"OFFLINE_SIMULATION\",\"phase\":\"GENERATION\"}");
        var protocol = json.readTree(stage.resolve("protocol.json").toFile());
        switch (scenario) {
            case "task-limit" -> {
                Path other = runtime.getParent().resolve("other-stage/traces");
                Files.createDirectories(other);
                for (int i = 0; i < 47; i++) Files.writeString(other.resolve(i + ".json"), "{}");
                NaturalUsefulDeliveryEvaluationServer.reserve(job, protocol);
                if (NaturalUsefulDeliveryEvaluationServer.taskActualCalls() != 48) throw new AssertionError("Cross-stage accounting lost reservations");
                rejects(() -> NaturalUsefulDeliveryEvaluationServer.reserve(job, protocol));
            }
            case "phase-limit" -> {
                NaturalUsefulDeliveryEvaluationServer.reserve(job, protocol);
                if (NaturalUsefulDeliveryEvaluationServer.taskActualCalls() != 1) throw new AssertionError("Empty interrupted reservation not counted");
                rejects(() -> NaturalUsefulDeliveryEvaluationServer.reserve(job, protocol));
            }
            case "closed-stage" -> {
                Files.writeString(stage.resolve("closure.json"), "{\"status\":\"CLOSED\"}");
                rejects(() -> NaturalUsefulDeliveryEvaluationServer.reserve(job, protocol));
            }
            case "closed-task" -> {
                Files.writeString(runtime.getParent().resolve("closure.json"), "{\"status\":\"CLOSED\"}");
                rejects(() -> NaturalUsefulDeliveryEvaluationServer.reserve(job, protocol));
            }
            case "wrong-budget" -> {
                Files.writeString(stage.getParent().resolve("task-protocol.json"), "{\"status\":\"FROZEN\",\"selectedMaximumActualCalls\":501,\"previousActualCalls\":0}");
                rejects(() -> NaturalUsefulDeliveryEvaluationServer.reserve(job, protocol));
            }
            case "capture-only-zero-budget" -> {
                Files.writeString(stage.resolve("protocol.json"), "{\"status\":\"FROZEN\",\"captureOnly\":true,\"taskMaximumActualCalls\":48,\"maximumNewActualCalls\":0,\"allowedJobs\":[],\"phaseLimits\":{\"GENERATION\":0}}");
                var captureProtocol = json.readTree(stage.resolve("protocol.json").toFile());
                NaturalUsefulDeliveryEvaluationServer.requireOpenBatch();
                rejects(() -> NaturalUsefulDeliveryEvaluationServer.reserve(job, captureProtocol));
                if (NaturalUsefulDeliveryEvaluationServer.taskActualCalls() != 0) throw new AssertionError("Capture stage reserved a network call");
            }
            default -> throw new IllegalArgumentException("Unknown offline scenario");
        }
        System.out.println("{\"scenario\":\"" + scenario + "\",\"passed\":true,\"networkCalls\":0}");
    }

    private static void rejects(Checked action) throws Exception {
        try { action.run(); }
        catch (IOException expected) { return; }
        throw new AssertionError("Unsafe reservation accepted");
    }

    @FunctionalInterface private interface Checked { void run() throws Exception; }
}
