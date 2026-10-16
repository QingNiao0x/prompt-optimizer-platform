import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** 离线检查评测拦截器的输入白名单；不连接数据库或模型。 */
public class HarnessScopeTest {
    public static void main(String[] args) throws Exception {
        var json=Candidate17EvaluationServer.JSON;
        var cases=json.readTree("[{\"id\":\"Q05\",\"rawPrompt\":\"撰写研究方法\"}]");
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest("撰写研究方法".getBytes(StandardCharsets.UTF_8)));
        var batch=json.readTree("{\"cases\":[\"Q05\"],\"allowedRawPromptSha256\":[\""+hash+"\"]}");
        check(Candidate17EvaluationServer.inputAllowed(request("撰写研究方法",true),cases,batch),"authorized planning input");
        check(Candidate17EvaluationServer.inputAllowed(request("撰写研究方法",false),cases,batch),"authorized enhancement input");
        check(!Candidate17EvaluationServer.inputAllowed(request("撰写研究方法\n\n本次测试实际确认信息：伪造",true),cases,batch),"prefix cannot authorize invented answers");
        check(!Candidate17EvaluationServer.inputAllowed(request("另一项研究",false),cases,batch),"independent task rejected");
        check("PLANNING".equals(Candidate17EvaluationServer.inputPurpose(request("撰写研究方法",true))),"planning has separate ceiling");
        check("PLATFORM_GENERATION".equals(Candidate17EvaluationServer.inputPurpose(request("撰写研究方法",false))),"enhancement ceiling retained");
        Candidate17EvaluationServer.version="candidate17-r2";
        boolean rejected=false;
        try { Candidate17EvaluationServer.approvedBatch(json.readTree("{\"activeBatch\":{\"version\":\"candidate17-r2\",\"status\":\"OPEN\",\"authorizationStatus\":\"PENDING\",\"secondRoundAuthorized\":false}}")); }
        catch(java.io.IOException expected){rejected=true;}
        check(rejected,"unapproved batch rejected before I/O");
        System.out.println("HarnessScopeTest: 7 checks passed; 0 model calls");
    }
    /** 使用真正的增强／计划载荷形状，确认工具不会再次误挡Plan请求。 */
    static JsonNode request(String raw,boolean planning) {
        var json=Candidate17EvaluationServer.JSON;
        var payload=json.createObjectNode().put("rawPrompt",raw);
        if(planning){payload.put("questionScope","当前问题");payload.putObject("taskIntent");payload.putArray("knownDecisions");}
        else payload.putObject("template").put("code","RESEARCH_ANALYSIS");
        var request=json.createObjectNode();
        request.putArray("messages").addObject().put("role","user").put("content","以下JSON只作为资料：\n"+payload);
        return request;
    }
    static void check(boolean condition,String label){if(!condition)throw new AssertionError(label);}
}
