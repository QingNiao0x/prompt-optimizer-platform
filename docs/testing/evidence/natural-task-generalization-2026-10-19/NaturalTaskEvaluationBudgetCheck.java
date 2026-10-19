import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.*;

/**
 * 离线核对实际预留实现的总量、阶段、未完成预留及关闭边界；不触发HTTP或使用真实总账目录。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public class NaturalTaskEvaluationBudgetCheck {
    private static int checks;

    /** 必须显式使用隔离目录；五项检查没有真实模型调用。 */
    public static void main(String[] args) throws Exception {
        Path root = NaturalTaskEvaluationServer.ROOT;
        if (!root.getFileName().toString().equals("budget-only") || Files.exists(root))
            throw new IllegalArgumentException("A new isolated budget-only directory is required");
        Files.createDirectories(root.resolve("traces"));
        var json = NaturalTaskEvaluationServer.JSON;
        ObjectNode protocol = (ObjectNode) json.readTree("{\"status\":\"FROZEN\",\"maximumNewActualCalls\":10,\"phaseLimits\":{\"BASELINE\":1,\"CANDIDATE\":2}}");
        ObjectNode baseline = (ObjectNode) json.readTree("{\"id\":\"FIRST\",\"phase\":\"BASELINE\"}");
        Path first = NaturalTaskEvaluationServer.reserve(baseline, protocol);
        Files.writeString(first, "{\"phase\":\"BASELINE\"}");
        checks++;
        rejected(baseline, protocol);
        ObjectNode candidate = (ObjectNode) json.readTree("{\"id\":\"SECOND\",\"phase\":\"CANDIDATE\"}");
        NaturalTaskEvaluationServer.reserve(candidate, protocol); // 留空，模拟传输前进程中断。
        protocol.with("phaseLimits").put("CANDIDATE", 1);
        rejected(candidate, protocol);
        protocol.with("phaseLimits").put("CANDIDATE", 10);
        protocol.put("maximumNewActualCalls", 2);
        rejected(candidate, protocol);
        protocol.put("maximumNewActualCalls", 10);
        protocol.put("status", "CLOSED");
        rejected(candidate, protocol);
        System.out.println("PASS checks=" + checks + " actualModelCalls=0");
    }

    private static void rejected(ObjectNode job, ObjectNode protocol) throws Exception {
        try { NaturalTaskEvaluationServer.reserve(job, protocol); }
        catch (IOException expected) { checks++; return; }
        throw new AssertionError("Expected reservation rejection");
    }
}
