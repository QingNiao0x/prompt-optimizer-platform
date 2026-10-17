import java.nio.file.Path;

/** 外部作品与评审复用同一批次的逐调用预占，失败同样消耗预算。 */
public final class ReserveEvaluationCall {
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !(args[0].equals("EXECUTION") || args[0].equals("REVIEW"))) {
            throw new IllegalArgumentException("Frozen external purpose required");
        }
        Candidate31EvaluationServer.version = "candidate31-live";
        Path trace = Candidate31EvaluationServer.reserve(args[0]);
        System.out.println(trace);
    }
}
