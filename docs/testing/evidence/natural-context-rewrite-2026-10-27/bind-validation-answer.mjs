import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const evidence = dirname(fileURLToPath(import.meta.url));
const json = async (path) => JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''));
export const confirmedAnswer = '允许填写今天，调查时间不得晚于今天。';

/** 绑定真实服务端会话和真实题号；答案来自本轮明确选择，绝不读取或接受推荐默认值。 */
export function createConfirmedValidationBundle(planResult, actualQuestionId, capturedInput) {
  assert.equal(planResult.id, 'P02_V45_PLAN', '只能绑定本轮已冻结的真实边界冲突Plan');
  assert.equal(planResult.phase, 'PLAN');
  assert.equal(planResult.version, '45');
  assert.equal(planResult.status, 200, 'Plan未成功不能伪造回答后生成');
  const plan = planResult.body?.data;
  assert.equal(plan?.provider?.mock, false, 'Mock不能作为真实Plan会话');
  assert.match(plan?.planId ?? '', /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i);
  assert.equal(plan.questions?.length, 1, 'Plan应只问一个重要边界；不能静默删除多余问题以伪造通过');
  const question = plan.questions.find((item) => item.id === actualQuestionId);
  assert.ok(question && typeof question.question === 'string' && question.question.trim(), '必须显式提供实际返回的题号');
  const sourceCase = planResult.approvedRawPrompt;
  assert.equal(typeof sourceCase, 'string', '调用方必须从原冻结P02作业核对原文，不能从模型概括倒填');
  const input = {
    ...structuredClone(capturedInput), rawPrompt: sourceCase,
    // P02最初Plan没有文件上下文，回答后继续同信息；不把C01材料倒灌到这个对照。
    context: { customDescription: '', files: [] }, conversationHistory: [],
    planConfirmation: { planId: plan.planId, planningContext: plan.planningContext ?? null,
      answers: [{ questionId: question.id, question: question.question, answer: confirmedAnswer }] },
  };
  return { syntheticOnly: true,
    provenance: '回答后生成使用真实Plan响应的会话与题号。明确回答来自本轮用户授权选择包含今天，不取推荐项。输入为脚本构造合法DTO，不冒称UI捕获。',
    decision: { selectedBy: '本轮明确选择', answer: confirmedAnswer, recommendationAccepted: false,
      actualQuestionId: question.id, actualQuestion: question.question, actualPlanId: plan.planId },
    cases: [{ id: 'P03', family: 'plan-confirmed-generation', rawPrompt: sourceCase, contextDescription: '', files: [],
      executorMaterials: [], origin: '真实P02 Plan回答后生成，不是新独立留出题。',
      acceptance: [
        '今天是否允许按真实回答确定为允许，保留不得晚于今天的边界。',
        '保留新增和导入两个入口，不再要求用户重复确认已经回答的边界。',
        '不声称用户没有资料或代码不存在；没有材料只限制依赖源码的断言。',
        '45记录实际正文生成；所有真实题答有出处且在最终正文得到保留。',
      ] }],
    jobs: [{ id: 'P03_V45_PLAN_GEN', phase: 'GENERATION', caseId: 'P03', version: '45',
      inputOrigin: 'ACTUAL_PLAN_ID_AND_QUESTION_WITH_EXPLICIT_ANSWER', approvedEnhancementInput: input }],
  };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const resultPath = process.argv[2];
  const questionId = process.argv[3];
  if (!resultPath || !questionId) throw new Error('用法：node bind-validation-answer.mjs <真实P02结果JSON> <实际题号> [输出bundle路径]；零模型调用。');
  const planResult = await json(resolve(resultPath));
  const jobs = await json(resolve(evidence, 'validation/jobs.json'));
  const originalJob = jobs.find((item) => item.id === 'P02_V45_PLAN');
  assert.equal(originalJob?.phase, 'PLAN');
  assert.equal(originalJob?.version, '45');
  const result = { ...planResult, approvedRawPrompt: originalJob.approvedEnhancementInput.rawPrompt };
  const bundle = createConfirmedValidationBundle(result, questionId, await json(resolve(evidence, 'browser-capture/input.json')));
  const bytes = await readFile(resolve(resultPath));
  bundle.sourcePlanResult = { path: resolve(resultPath), sha256: createHash('sha256').update(bytes).digest('hex'),
    originalJob: originalJob.id, originalRawPrompt: result.approvedRawPrompt };
  const output = process.argv[4] ? resolve(process.argv[4]) : resolve(evidence, 'validation-confirmed-bundle.json');
  await writeFile(output, `${JSON.stringify(bundle, null, 2)}\n`, { flag: 'wx' });
  console.log(JSON.stringify({ output, jobs: 1, modelCalls: 0, planId: bundle.decision.actualPlanId,
    questionId, requiresNewStageFreeze: 'validation-confirmed' }));
}
