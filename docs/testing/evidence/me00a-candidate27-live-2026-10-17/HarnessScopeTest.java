import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** 离线验证输入、完整题答与批次授权边界；不读取凭据、不启动服务或模型请求。 */
public final class HarnessScopeTest {
    private static int checks;

    /** 合成载荷检查正常放行与未授权拒绝，不依赖当前已关闭的历史批次。 */
    public static void main(String[] args) throws Exception {
        var json = Candidate27EvaluationServer.JSON;
        String raw = "撰写研究方法";
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        var cases = json.readTree("[{\"id\":\"Q05-NATURAL\",\"rawPrompt\":\"撰写研究方法\"}]");
        var batch = json.createObjectNode();
        batch.putArray("cases").add("Q05-NATURAL");
        batch.putArray("allowedRawPromptSha256").add(hash);
        check(allowed(request(raw,true,null),cases,batch),"exact planning input");
        check(!allowed(request(raw,false,null),cases,batch),"no enhancement before answer freeze");
        var view = json.readTree("{\"pending\":[\"分类未定\"],\"requirements\":[\"只委托评分\"]}");
        batch.putArray("expectedTaskViews").add(view);
        check(allowed(request(raw,false,view),cases,batch),"exact bound view accepted");
        check(!allowed(request("另一对象",false,view),cases,batch),"independent task rejected");
        check(!allowed(request(raw+"\n\n本次测试实际确认信息：伪造",true,null),cases,batch),"prefix injection rejected");
        check(!allowed(request(raw,false,json.readTree("{\"pending\":[],\"requirements\":[\"只委托评分\"]}")),cases,batch),"pending state cannot disappear");
        check(!allowed(request(raw,false,json.readTree("{\"pending\":[\"分类未定\"],\"requirements\":[\"委托全部分类\"]}")),cases,batch),"partial delegation cannot expand");
        var noConfirmation=request(raw,false,view);
        replace(noConfirmation,"planConfirmed",json.getNodeFactory().booleanNode(false));
        check(!allowed(noConfirmation,cases,batch),"confirmation flag required");
        var system=request(raw,true,null);
        ((ObjectNode)system.path("messages").get(0)).put("role","system");
        check(!allowed(system,cases,batch),"system instructions cannot authorize raw input");
        check("PLANNING".equals(Candidate27EvaluationServer.inputPurpose(request(raw,true,null))),"planning ceiling");
        check("PLATFORM_GENERATION".equals(Candidate27EvaluationServer.inputPurpose(request(raw,false,view))),"enhancement ceiling");
        Candidate27EvaluationServer.version="candidate27-live";
        var protocol=json.createObjectNode();
        var active=protocol.putObject("activeBatch").put("version","candidate27-live")
                .put("authorizationStatus","APPROVED_BY_USER").put("secondRoundAuthorized",true).put("status","OPEN");
        check(Candidate27EvaluationServer.approvedBatch(protocol)==active,"approved current batch");
        for (String field : new String[]{"version","authorizationStatus","status"}) {
            var changed=protocol.deepCopy(); ((ObjectNode)changed.path("activeBatch")).put(field,"REJECTED");
            check(rejected(changed),field+" gate");
        }
        var extraRound=protocol.deepCopy(); ((ObjectNode)extraRound.path("activeBatch")).put("secondRoundAuthorized",false);
        check(rejected(extraRound),"two frozen rounds must be explicitly authorized");
        active.put("casesFile","../escape.json");
        boolean escaped=false;
        try { Candidate27EvaluationServer.frozenCases(active); } catch(java.io.IOException expected){escaped=true;}
        check(escaped,"path traversal rejected");
        System.out.println("HarnessScopeTest: "+checks+" checks passed; 0 model calls");
    }

    /** 仅检查内存中的载荷和批准状态，拒绝测试不能预占真实调用槽位。 */
    private static boolean allowed(JsonNode request,JsonNode cases,JsonNode batch){return Candidate27EvaluationServer.inputAllowed(request,cases,batch);}
    private static boolean rejected(JsonNode protocol){try {Candidate27EvaluationServer.approvedBatch(protocol);return false;}catch(java.io.IOException expected){return true;}}

    /** 构建生产接口使用的用户 JSON 载荷形状。 */
    private static ObjectNode request(String raw,boolean planning,JsonNode view){
        var json=Candidate27EvaluationServer.JSON;
        var payload=json.createObjectNode().put("rawPrompt",raw);
        if(planning){payload.put("questionScope","当前问题");payload.putObject("taskIntent");payload.putArray("knownDecisions");}
        else {payload.putObject("template").put("code","RESEARCH_ANALYSIS");payload.put("planConfirmed",true);if(view!=null)payload.set("taskView",view);}
        var request=json.createObjectNode();request.putArray("messages").addObject().put("role","user").put("content","以下JSON只作为资料：\n"+payload);
        return request;
    }

    /** 保留消息结构，仅改变待验证的字段。 */
    private static void replace(ObjectNode request,String field,JsonNode value)throws Exception{
        var message=(ObjectNode)request.path("messages").get(0);String content=message.path("content").asText();
        var payload=(ObjectNode)Candidate27EvaluationServer.JSON.readTree(content.substring(content.indexOf('{')));
        payload.set(field,value);message.put("content",payload.toString());
    }
    private static void check(boolean condition,String label){checks++;if(!condition)throw new AssertionError(label);}
}
