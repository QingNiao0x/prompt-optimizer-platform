"""复用已留存的评测服务器，生成私有运行副本；不修改产品、预算或旧原件。"""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
HERE = Path(__file__).resolve().parent
OLD = ROOT / 'docs/testing/evidence/me01-candidate41r2-responsibility-projection-2026-10-18'
OUT = ROOT / 'tmp/me01-plan-core-acceptance-20261019/harness'
OUT.mkdir(parents=True, exist_ok=True)


def replace_once(text, before, after):
    """原件漂移时停止，避免静默生成缺少保护的运行工具。"""
    if text.count(before) != 1:
        raise ValueError(f'Expected one source match: {before[:90]}')
    return text.replace(before, after)


server = (OLD / 'Candidate41EvaluationServer.java').read_text(encoding='utf-8-sig')
server = server.replace('Candidate41EvaluationServer', 'PlanCoreAcceptanceServer')
server = server.replace('tmp/me01-candidate41r2-live-20261018', 'tmp/me01-plan-core-acceptance-20261019')
server = server.replace('candidate41r2-live', 'candidate41r2-core-acceptance')
server = server.replace('prompt-optimizer:candidate41r2', 'prompt-optimizer:plan-core-acceptance-20261019')
for before, after in [('== 6', '== 18'), ('"PLANNING").asInt() == 1', '"PLANNING").asInt() == 9'),
                      ('"PLATFORM_GENERATION").asInt() == 1', '"PLATFORM_GENERATION").asInt() == 9'),
                      ('"EXECUTION").asInt() == 2', '"EXECUTION").asInt() == 0'),
                      ('"REVIEW").asInt() == 2', '"REVIEW").asInt() == 0'),
                      ('== 444', '== 462'), ('== 364', '== 382'), ('!= 428', '!= 434'), ('!= 358', '!= 364')]:
    server = replace_once(server, before, after)
server = replace_once(server, 'static JsonNode actualConfirmation() throws IOException {', '''static JsonNode actualConfirmation(String caseId) throws IOException {
        // 只有本批三题可读取各自的原生提交；禁止路径注入和跨题借用确认。
        if (!java.util.Set.of("LOG-SUMMARY", "RESERVED-REFUND", "HOURS-CONFLICT").contains(caseId))
            throw new IOException("Unknown case");''')
server = server.replace('"flow/TS-IMPLICIT/', '"flow/" + caseId + "/')
server = replace_once(server, 'JSON.convertValue(actualConfirmation(),',
                      'JSON.convertValue(actualConfirmation(sample.path("id").asText()),')
server = replace_once(server, 'actualConfirmation().path("answers")',
                      'actualConfirmation(inputCaseId(input, cases)).path("answers")')
server = replace_once(server, 'actualConfirmation();',
                      'actualConfirmation(inputCaseId(input, frozenCases(batch)));')
server = replace_once(server, 'if (!java.util.Set.of("AUTO", "FEATURE_DEVELOPMENT").contains(requestedTemplate)) return false;', '''String inferred = new com.promptoptimizer.template.service.impl.PromptTemplateRegistryImpl()
                                .infer(sample.path("rawPrompt").asText()).name();
                        if (!requestedTemplate.equals("AUTO") && !requestedTemplate.equals(inferred)) return false;''')
server = replace_once(server, '    static String inputPurpose(JsonNode input) throws IOException {', '''    /** 从冻结原题定位提交目录，不能用其他题的回答填充本题。 */
    static String inputCaseId(JsonNode input, JsonNode cases) throws IOException {
        for (JsonNode message : input.path("messages")) {
            if (!"user".equals(message.path("role").asText())) continue;
            String text = message.path("content").asText();
            int start = text.indexOf('{');
            if (start < 0) continue;
            JsonNode payload = JSON.readTree(text.substring(start));
            for (JsonNode sample : cases)
                if (sample.path("rawPrompt").equals(payload.path("rawPrompt"))) return sample.path("id").asText();
        }
        throw new IOException("No frozen task in input");
    }

    static String inputPurpose(JsonNode input) throws IOException {''')
server = replace_once(server, 'record.put("purpose", purpose);',
                      'record.put("purpose", purpose); record.put("caseId", inputCaseId(input, frozenCases(batch)));')
server = server.replace('本批唯一增强', '本批对应增强').replace('外来资料', '冻结范围外的资料')
(OUT / 'PlanCoreAcceptanceServer.java').write_text(server, encoding='utf-8')

probe = (OLD / 'Candidate41ScopePreflight.java').read_text(encoding='utf-8-sig')
probe = probe.replace('Candidate41ScopePreflight', 'PlanCoreScopePreflight').replace('Candidate41EvaluationServer', 'PlanCoreAcceptanceServer')
probe = probe.replace('candidate41r2-live', 'candidate41r2-core-acceptance')
probe = probe.replace('"callsBefore", 428', '"callsBefore", 434').replace('"candidateCallsBefore", 358', '"candidateCallsBefore", 364')
probe = probe.replace('"maximumNewCalls", 6', '"maximumNewCalls", 18')
probe = probe.replace('"PLANNING", 1', '"PLANNING", 9').replace('"PLATFORM_GENERATION", 1', '"PLATFORM_GENERATION", 9')
probe = probe.replace('"EXECUTION", 2', '"EXECUTION", 0').replace('"REVIEW", 2', '"REVIEW", 0')
probe = probe.replace('"maximumActualModelCalls", 444', '"maximumActualModelCalls", 462').replace('"maximumActualModelCalls", 445', '"maximumActualModelCalls", 463')
probe = probe.replace('"candidateValidation", 364', '"candidateValidation", 382').replace('"candidateValidation", 365', '"candidateValidation", 383')
probe = replace_once(probe, 'expected.addObject().put("caseId", sample.path("id").asText()).set(',
                     'expected.addObject().put("caseId", sample.path("id").asText()).put("inferredTemplateCode", new com.promptoptimizer.template.service.impl.PromptTemplateRegistryImpl().infer(sample.path("rawPrompt").asText()).name()).set(')
# 仅用于离线检查题答绑定，绝不成为真实请求的答案。
probe = probe.replace('文本按什么粒度去重？', '离线绑定探针：是否保留逐行统计？').replace('按整行完全相同去重，区分大小写，保留第一次出现的顺序', '离线探针回答：统计口径保持原文；不得发送给模型')
probe = probe.replace('cases.get(1).path("rawPrompt").asText()', '"unapproved task"').replace('diagnostic-only task cannot enhance', 'foreign task cannot enhance')
probe = replace_once(probe, '        var sample = cases.get(0);', '''        check(PlanCoreAcceptanceServer.LIVE.equals(Path.of("tmp/me01-plan-core-acceptance-20261019").toAbsolutePath()), "bound submission directory");
        // 新题含非代码场景，同时核对零问题与实际确认两条序列化路径；不发送合成探针答案。
        for (var scoped : cases) {
            String raw = scoped.path("rawPrompt").asText();
            var inferred = new com.promptoptimizer.template.service.impl.PromptTemplateRegistryImpl().infer(raw);
            for (var selected : List.of(TemplateCode.AUTO, inferred)) {
                for (var scopedAnswers : List.of(List.<PlanAnswer>of(), List.of(new PlanAnswer(
                        "offline-bound", "离线范围探针？", "离线检查，不得发送")))) {
                    var scopedView = EffectiveTaskViewFactory.create(raw, selected, scopedAnswers, List.of(), false);
                    var scopedPayload = JSON.createObjectNode().put("rawPrompt", raw).put("planConfirmed", true);
                    scopedPayload.putObject("template"); scopedPayload.putArray("conversationHistory");
                    scopedPayload.putObject("options").put("templateCode", selected.name()).put("promptFormat", "COMPACT").put("includeExamples", false);
                    scopedPayload.set("taskView", JSON.valueToTree(scopedView.modelInput()));
                    scopedPayload.set("requirementReferences", PlanCoreAcceptanceServer.expectedReferences(scopedView));
                    scopedPayload.putObject("rewriteStrategy").put("mode", PromptRewriteStrategy.forTask(scopedView, PromptFormat.COMPACT).mode());
                    check(allowed(scopedPayload, cases, scopedAnswers), "each task exact bound/unbound view");
                    var invalidTemplate = scopedPayload.deepCopy();
                    ((ObjectNode) invalidTemplate.path("options")).put("templateCode", "UNAPPROVED");
                    check(!allowed(invalidTemplate, cases, scopedAnswers), "template whitelist");
                }
            }
        }
        var sample = cases.get(0);''')
(OUT / 'PlanCoreScopePreflight.java').write_text(probe, encoding='utf-8')
hashes = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in OUT.glob('*.java')}
(HERE / 'generated-tool-hashes.json').write_text(json.dumps(hashes, indent=2) + '\n', encoding='utf-8')
print(json.dumps({'prepared': list(hashes), 'modelCalls': 0, 'budgetModified': False}))
