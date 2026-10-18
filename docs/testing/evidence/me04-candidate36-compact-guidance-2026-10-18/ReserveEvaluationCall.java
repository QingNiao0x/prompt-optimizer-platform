import java.nio.file.Path;

/** 外部作品与独立评审复用候选三十三的逐调用预占，失败同样消耗正式预算。 */
public final class ReserveEvaluationCall {
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !(args[0].equals("EXECUTION") || args[0].equals("REVIEW"))) {
            throw new IllegalArgumentException("Frozen external purpose required");
        }
        Candidate36EvaluationServer.version = "candidate36-live";
        Path trace = Candidate36EvaluationServer.reserve(args[0]);
        System.out.println(trace);
    }
}
