import java.nio.file.Path;

/** 外部作品与独立评审复用候选四十的逐调用预占，失败同样消耗正式预算。 */
public final class ReserveEvaluationCall {
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !java.util.List.of("EXECUTION", "REVIEW").contains(args[0])) {
            throw new IllegalArgumentException("Frozen external purpose required");
        }
        Candidate42EvaluationServer.version = "candidate42-source-ownership";
        Path trace = Candidate42EvaluationServer.reserve(args[0]);
        System.out.println(trace);
    }
}
