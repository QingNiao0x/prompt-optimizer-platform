
/** 同一冻结题目与账目下，只加载C2结构化产品源码；不重建账号、Plan答案或原始作品。 */
public class StructuredQualityCandidate2Server {
    public static void main(String[] args) throws Exception {
        StructuredQualityEvaluationServer.protocolName="protocol-candidate2.json";
        StructuredQualityEvaluationServer.manifestName="source-manifest-candidate2.json";
        StructuredQualityEvaluationServer.main(args);
    }
}
