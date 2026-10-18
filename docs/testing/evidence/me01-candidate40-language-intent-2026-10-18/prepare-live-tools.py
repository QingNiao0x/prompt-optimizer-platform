"""Reuse the prior guarded local harness; only frozen scope and actual bound answers may change."""
from pathlib import Path
import json

PUBLIC = Path(__file__).resolve().parent
ROOT = PUBLIC.parents[3]
OLD = ROOT / 'docs/testing/evidence/me04-candidate38-coverage-plan-2026-10-18'


def save(name, text):
    with (PUBLIC / name).open('x', encoding='utf-8', newline='\n') as output:
        output.write(text)


def main():
    proposal = json.loads((PUBLIC / 'live-proposal.json').read_text(encoding='utf-8'))
    cases = [{'id': c['caseId'], 'rawPrompt': c['rawPrompt'], 'promptFormat': c['mode'],
              'includeExamples': c['examplesEnabled'], 'planConfirmed': True,
              'enhancementAllowed': c['enhanceAfterActualConfirmation'] if 'enhanceAfterActualConfirmation' in c else False}
             for c in proposal['cases']]
    save('request-cases.json', json.dumps(cases, ensure_ascii=False, indent=2) + '\n')
    server = (OLD / 'Candidate38EvaluationServer.java').read_text(encoding='utf-8')
    server = server.replace('Candidate38EvaluationServer', 'Candidate40EvaluationServer').replace('candidate38', 'candidate40')
    for before, after in [('!= 412', '!= 415'), ('!= 342', '!= 345'), ('maximumNewCalls").asInt() != 3', 'maximumNewCalls").asInt() != 13'),
                          ('"PLATFORM_GENERATION").asInt() != 1', '"PLATFORM_GENERATION").asInt() != 3'),
                          ('"EXECUTION").asInt() != 1', '"EXECUTION").asInt() != 2'),
                          ('"REVIEW").asInt() != 0', '"REVIEW").asInt() != 2'),
                          ('"PLANNING").asInt() != 1', '"PLANNING").asInt() != 6'),
                          ('!= 425', '!= 438'), ('"candidateValidation").asInt() != 345', '"candidateValidation").asInt() != 358')]:
        assert before in server, before
        server = server.replace(before, after)
    start = server.index('    /** 编排边界校验')
    end = server.index('    static String inputPurpose', start)
    server = server[:start] + '''    static final Path LIVE = Path.of("tmp/me01-candidate40-live-20261018").toAbsolutePath();

    /** 只读取固定路径上的实际服务端问题与原生提交，不能由工具另补答案。 */
    static JsonNode actualConfirmation() throws IOException {
        JsonNode plan = JSON.readTree(LIVE.resolve("flow/TS-IMPLICIT/plan.private.json").toFile());
        JsonNode submitted = JSON.readTree(LIVE.resolve("flow/TS-IMPLICIT/actual-confirmation.private.json").toFile());
        if (plan.path("status").asInt() != 200 || !plan.path("body").path("data").path("planId").equals(submitted.path("planId"))
                || !plan.path("body").path("data").path("planningContext").equals(submitted.path("planningContext")))
            throw new IOException("Real bound Plan required");
        JsonNode questions = plan.path("body").path("data").path("questions");
        if (submitted.path("answers").size() != questions.size()) throw new IOException("Incomplete bound answers");
        for (int i = 0; i < questions.size(); i++) {
            JsonNode q = questions.get(i), answer = submitted.path("answers").get(i);
            if (!q.path("id").equals(answer.path("questionId")) || !q.path("question").equals(answer.path("question"))
                    || answer.path("answer").asText().isBlank() || answer.path("answer").asText().length() > 1500)
                throw new IOException("Question/answer binding drift");
        }
        return submitted;
    }

    /** 空资料、无对话、固定模式和实际原生确认全部相同才允许本批唯一增强。 */
    static boolean requestAllowed(com.promptoptimizer.enhancement.dto.OptimizationRequest request, JsonNode cases) throws IOException {
        if (!request.context().files().isEmpty() || !request.context().customDescription().isBlank()
                || !request.conversationHistory().isEmpty() || request.planConfirmation() == null) return false;
        for (JsonNode sample : cases) {
            if (sample.path("enhancementAllowed").asBoolean() && request.rawPrompt().equals(sample.path("rawPrompt").asText())
                    && request.enhancement().promptFormat() != null
                    && request.enhancement().promptFormat().name().equals(sample.path("promptFormat").asText())
                    && Boolean.TRUE.equals(request.enhancement().includeExamples()) == sample.path("includeExamples").asBoolean()
                    && JSON.valueToTree(request.planConfirmation()).equals(actualConfirmation())) return true;
        }
        return false;
    }

    /** 离线夹具与真实输入共用同一校验；实际运行的答案只能来自已绑定提交。 */
    static boolean scopedInputAllowed(JsonNode input, JsonNode cases, java.util.List<com.promptoptimizer.enhancement.dto.PlanAnswer> answers) {
        for (JsonNode msg : input.path("messages")) {
            if (!"user".equals(msg.path("role").asText())) continue;
            String content = msg.path("content").asText(); int begin = content.indexOf('{'); if (begin < 0) continue;
            try {
                JsonNode payload = JSON.readTree(content.substring(begin));
                for (JsonNode sample : cases) {
                    if (!sample.path("rawPrompt").asText().equals(payload.path("rawPrompt").asText())
                            || !payload.path("conversationHistory").isEmpty()) continue;
                    var template = new com.promptoptimizer.template.service.impl.PromptTemplateRegistryImpl().infer(sample.path("rawPrompt").asText());
                    if (payload.has("template")) {
                        if (!sample.path("enhancementAllowed").asBoolean() || !payload.path("planConfirmed").asBoolean()
                                || !payload.path("options").path("promptFormat").equals(sample.path("promptFormat"))
                                || payload.path("options").path("includeExamples").asBoolean()
                                || !payload.path("planAnswers").equals(JSON.valueToTree(answers))) return false;
                        var view = com.promptoptimizer.enhancement.service.impl.EffectiveTaskViewFactory.create(
                                sample.path("rawPrompt").asText(), template, answers, java.util.List.of(), false);
                        var mode = com.promptoptimizer.provider.domain.PromptRewriteStrategy.forTask(view,
                                com.promptoptimizer.enhancement.domain.PromptFormat.COMPACT).mode();
                        return payload.path("taskView").equals(JSON.valueToTree(view.modelInput()))
                                && payload.path("rewriteStrategy").path("mode").asText().equals(mode);
                    }
                    var plan = com.promptoptimizer.enhancement.service.impl.EffectiveTaskViewFactory.create(
                            sample.path("rawPrompt").asText(), com.promptoptimizer.enhancement.domain.TemplateCode.AUTO,
                            java.util.List.of(), java.util.List.of(), false);
                    return payload.has("questionScope") && payload.path("knownDecisions").isEmpty()
                            && payload.path("contextDescription").asText("").isBlank()
                            && payload.path("planningContext").path("factCards").isEmpty()
                            && payload.path("taskView").equals(JSON.valueToTree(plan.modelInput()))
                            && payload.path("taskIntent").equals(JSON.valueToTree(plan.intent()));
                }
            } catch (IOException ignored) { }
        }
        return false;
    }

    /** 协议修复仍只能重发同一原题和实际题答，不授权新任务或外来资料。 */
    static boolean inputAllowed(JsonNode input, JsonNode cases, JsonNode batch) throws IOException {
        var answers = "PLATFORM_GENERATION".equals(inputPurpose(input))
                ? JSON.convertValue(actualConfirmation().path("answers"),
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.List<com.promptoptimizer.enhancement.dto.PlanAnswer>>() {})
                : java.util.List.<com.promptoptimizer.enhancement.dto.PlanAnswer>of();
        return scopedInputAllowed(input, cases, answers);
    }
''' + server[end:]
    endpoint = '''if (!"https".equals(request.getURI().getScheme()) || !"api.deepseek.com".equals(request.getURI().getHost()))'''
    assert endpoint in server
    server = server.replace(endpoint, '''if (!"https".equals(request.getURI().getScheme()) || !"api.deepseek.com".equals(request.getURI().getHost())
                                || !"/chat/completions".equals(request.getURI().getPath()) || request.getURI().getQuery() != null
                                || request.getURI().getUserInfo() != null || request.getURI().getFragment() != null
                                || (request.getURI().getPort() != -1 && request.getURI().getPort() != 443))''')
    save('Candidate40EvaluationServer.java', server)
    reserve = (OLD / 'ReserveEvaluationCall.java').read_text(encoding='utf-8').replace('Candidate38EvaluationServer', 'Candidate40EvaluationServer').replace('candidate38-live', 'candidate40-live')
    reserve = reserve.replace('!args[0].equals("EXECUTION")', '!java.util.List.of("EXECUTION", "REVIEW").contains(args[0])')
    reserve = reserve.replace('候选三十三', '候选四十')
    save('ReserveEvaluationCall.java', reserve)
    for name in ['start-candidate38.ps1', 'start-live-services.ps1', 'account-browser.mjs']:
        content = (OLD / name).read_text(encoding='utf-8').replace('me04-candidate38-coverage-plan-2026-10-18', PUBLIC.name)
        content = content.replace('me04-candidate38-live-20261018', 'me01-candidate40-live-20261018').replace('me04-candidate38-20261018', 'me01-candidate40-20261018')
        content = content.replace('Candidate38', 'Candidate40').replace('candidate38', 'candidate40')
        save(name.replace('candidate38', 'candidate40'), content)
    print(json.dumps({'preparedTools': 6, 'caseCount': len(cases), 'modelCalls': 0, 'reservations': 0}))


if __name__ == '__main__':
    main()
