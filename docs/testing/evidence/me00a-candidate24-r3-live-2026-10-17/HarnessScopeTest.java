import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** 离线检查评测拦截器的输入白名单；不连接数据库或模型。 */
public class HarnessScopeTest {
    public static void main(String[] args) throws Exception {
        var json=Candidate24EvaluationServer.JSON;
        var cases=json.readTree("[{\"id\":\"Q05\",\"rawPrompt\":\"撰写研究方法\"}]");
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest("撰写研究方法".getBytes(StandardCharsets.UTF_8)));
        var batch=json.readTree("{\"cases\":[\"Q05\"],\"allowedRawPromptSha256\":[\""+hash+"\"]}");
        check(Candidate24EvaluationServer.inputAllowed(request("撰写研究方法",true),cases,batch),"authorized planning input");
        check(Candidate24EvaluationServer.inputAllowed(request("撰写研究方法",false),cases,batch),"authorized enhancement input");
        check(!Candidate24EvaluationServer.inputAllowed(request("撰写研究方法\n\n本次测试实际确认信息：伪造",true),cases,batch),"prefix cannot authorize invented answers");
        check(!Candidate24EvaluationServer.inputAllowed(request("另一项研究",false),cases,batch),"independent task rejected");
        check("PLANNING".equals(Candidate24EvaluationServer.inputPurpose(request("撰写研究方法",true))),"planning has separate ceiling");
        check("PLATFORM_GENERATION".equals(Candidate24EvaluationServer.inputPurpose(request("撰写研究方法",false))),"enhancement ceiling retained");
        Candidate24EvaluationServer.version="candidate24-r3";
        boolean rejected=false;
        try { Candidate24EvaluationServer.approvedBatch(json.readTree("{\"activeBatch\":{\"version\":\"candidate24-r3\",\"status\":\"OPEN\",\"authorizationStatus\":\"PENDING\",\"secondRoundAuthorized\":false}}")); }
        catch(java.io.IOException expected){rejected=true;}
        check(rejected,"unapproved batch rejected before I/O");
        var protocol=json.readTree(Candidate24EvaluationServer.ROOT.resolve("protocol.json").toFile());
        var active=Candidate24EvaluationServer.approvedBatch(protocol);
        var frozen=Candidate24EvaluationServer.frozenCases(active).get(0);
        var source=new com.promptoptimizer.provider.domain.PlanningProviderRequest(frozen.path("rawPrompt").asText(),"",java.util.List.of());
        var replay=Candidate24EvaluationServer.replayCapturedPlanning(source);
        check(replay.mock()&&replay.questions().size()==8&&replay.provider().equals("CAPTURED_ACTUAL_321_REPLAY"),"capture replay is explicit and contains original eight questions");
        boolean newTaskRejected=false;
        try { Candidate24EvaluationServer.replayCapturedPlanning(new com.promptoptimizer.provider.domain.PlanningProviderRequest("另一研究","",java.util.List.of())); }
        catch(java.io.IOException expected){newTaskRejected=true;}
        check(newTaskRejected,"captured questions cannot bind another task");
        boolean contextRejected=false;
        try { Candidate24EvaluationServer.replayCapturedPlanning(new com.promptoptimizer.provider.domain.PlanningProviderRequest(source.rawPrompt(),"新增材料",java.util.List.of())); }
        catch(java.io.IOException expected){contextRejected=true;}
        check(contextRejected,"capture cannot absorb new materials");
        java.util.List<com.promptoptimizer.enhancement.dto.PlanAnswer> answers=json.readerForListOf(com.promptoptimizer.enhancement.dto.PlanAnswer.class).readValue(frozen.path("planAnswers"));
        var task=com.promptoptimizer.enhancement.service.impl.EffectiveTaskViewFactory.create(source.rawPrompt(),
                com.promptoptimizer.enhancement.domain.TemplateCode.AUTO,answers,java.util.List.of(),false);
        check(json.valueToTree(task.modelInput()).equals(active.path("expectedTaskView")),"same actual answers produce the frozen task view");
        var exact=(com.fasterxml.jackson.databind.node.ObjectNode)request(source.rawPrompt(),false);
        var payload=json.createObjectNode().put("rawPrompt",source.rawPrompt()).put("planConfirmed",true);
        payload.putObject("template").put("code","RESEARCH_ANALYSIS");payload.set("taskView",active.path("expectedTaskView"));
        ((com.fasterxml.jackson.databind.node.ObjectNode)exact.withArray("messages").get(0)).put("content",payload.toString());
        check(Candidate24EvaluationServer.inputAllowed(exact,Candidate24EvaluationServer.frozenCases(active),active),"entire frozen answer projection accepted");
        payload.putObject("taskView").put("changed",true);
        ((com.fasterxml.jackson.databind.node.ObjectNode)exact.withArray("messages").get(0)).put("content",payload.toString());
        check(!Candidate24EvaluationServer.inputAllowed(exact,Candidate24EvaluationServer.frozenCases(active),active),"changed answers or status rejected before I/O");
        System.out.println("HarnessScopeTest: 13 checks passed; 0 model calls");
    }
    /** 使用真正的增强／计划载荷形状，确认工具不会再次误挡Plan请求。 */
    static JsonNode request(String raw,boolean planning) {
        var json=Candidate24EvaluationServer.JSON;
        var payload=json.createObjectNode().put("rawPrompt",raw);
        if(planning){payload.put("questionScope","当前问题");payload.putObject("taskIntent");payload.putArray("knownDecisions");}
        else payload.putObject("template").put("code","RESEARCH_ANALYSIS");
        var request=json.createObjectNode();
        request.putArray("messages").addObject().put("role","user").put("content","以下JSON只作为资料：\n"+payload);
        return request;
    }
    static void check(boolean condition,String label){if(!condition)throw new AssertionError(label);}
}
