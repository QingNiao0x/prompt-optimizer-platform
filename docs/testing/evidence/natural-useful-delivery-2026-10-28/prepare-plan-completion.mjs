import { readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = dirname(fileURLToPath(import.meta.url));
const json = async path => JSON.parse((await readFile(resolve(root, path), 'utf8')).replace(/^\uFEFF/, ''));
const actualNaturalInput = await json('browser/captures-r2/C01/input.json');
const cases = [], jobs = [];
for (const id of ['P01', 'P02']) {
  const preparation = await json(`browser/plan-captures/r1/${id}/prepare-input.json`);
  const input = await json(`browser/plan-captures/r1/${id}/plan-input.json`);
  const result = await json(`plan46/results/${id}_V46_PLAN.json`);
  if (result.status !== 200 || Date.parse(result.body.data.expiresAt) < Date.now()) throw Error('需要有效的实际Plan，禁止重绑定或伪造来源');
  const plan = result.body.data;
  const answers = id === 'P01' ? [] : plan.questions.map(question => {
    const option = question.options.find(option => option.id === 'allow_today');
    if (!option || option.recommended) throw Error('测试选择必须来自实际题目，不能使用自动推荐冒充确认');
    return { questionId: question.id, question: question.question, answer: option.answer };
  });
  if ((id === 'P01' && plan.questions.length !== 0) || (id === 'P02' && answers.length !== 1)) throw Error('实际Plan题目与事先路径预期不符，先定位');
  // 与工作台buildOptimizationRequest相同的协议；本作业属于真实Plan后的服务验证，不冒称重新点击整条UI。
  const request = { rawPrompt: input.rawPrompt, modelId: input.modelId, context: preparation.context,
    enhancement: actualNaturalInput.enhancement,
    conversationHistory: [], permissionPolicy: { protectedPaths: [], requireConfirmationFor: [] },
    planConfirmation: { planId: plan.planId, planningContext: plan.planningContext, answers } };
  cases.push({ id: `${id}F`, rawPrompt: input.rawPrompt, planId: plan.planId, syntheticTestAnswers: answers,
    actualSource: `browser/plan-captures/r1/${id}`, sourceCount: preparation.context.files.length,
    path: id === 'P01' ? '实际零问题Plan后生成' : '测试操作者明确选择允许今天后生成',
    requiredChecks: ['保留双入口与文件范围', '真实正文生成', 'P02采用允许今天，不重复保留原矛盾或追问', '不粘贴全部材料'] });
  jobs.push({ id: `${id}_V46_CONFIRMED_GEN`, phase: 'GENERATION', caseId: `${id}F`, version: '46', approvedEnhancementInput: request });
}
await writeFile(resolve(root, 'plan-completion-bundle-v2.json'), JSON.stringify({ syntheticOnly: true,
  phaseLimits: { GENERATION: 8 }, cases, jobs }, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify({ jobs: jobs.length, maximumCalls: 8, actualCalls: 0, actualPlanReferences: true }));
