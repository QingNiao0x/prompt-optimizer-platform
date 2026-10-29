import assert from 'node:assert/strict';
import { readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
const here = dirname(fileURLToPath(import.meta.url)), evidence = resolve(here, '..');
const json = async path => JSON.parse((await readFile(resolve(evidence, path), 'utf8')).replace(/^\uFEFF/, ''));
const previous = await json('registry56-regression-preparation/bundle.json');
const code = await json('registry51-works-preparation/code-planned-bundle.json');
const jobs = [], cases = [];
for (const id of ['U02', 'U03', 'C01', 'C02', 'V03', 'Q01']) {
  const original = previous.jobs.find(value => value.caseId === id);
  const sample = structuredClone(previous.cases.find(value => value.id === id));
  const input = structuredClone(original.approvedEnhancementInput);
  if (id.startsWith('C')) {
    const fixture = code.cases.find(value => value.caseId === id);
    assert.equal(input.rawPrompt, fixture.rawPrompt);
    // 旧验收遗漏today时存在README/执行契约歧义；新夹具明确共同契约，增强与三臂执行同时看见。
    input.context.files = fixture.executorMaterials.map(file => ({ ...file,
      content: file.path === 'README.md' ? file.content + '\n当前日期契约：today可选；传入时使用它作日历日期基准，未传入时仍应使用系统本地的当日日历日期限制未来日期。新增、导入与共用校验须遵守同一基准。\n' : file.content,
      language: file.path.endsWith('.js') ? 'javascript' : file.path.endsWith('.json') ? 'json' : 'markdown',
    }));
    sample.fixtureRevision = 'R2_EXPLICIT_OPTIONAL_TODAY_SHARED_INFORMATION';
    sample.executorMaterials = input.context.files.map(({path,content}) => ({path,content}));
    sample.historicalOutcomeUnchanged = true;
  }
  cases.push(sample);
  jobs.push({ id: `${id}_V58_GEN`, caseId: id, phase: 'GENERATION', version: '58', approvedEnhancementInput: input });
  if (id.startsWith('C')) jobs.push({ id: `${id}_V31_GEN`, caseId: id, phase: 'GENERATION', version: '31', approvedEnhancementInput: input });
}
const planResult = await json('registry58-boundary/results/P00_V58_PLAN.json');
assert.equal(planResult.status, 200); assert.deepEqual(planResult.body.data.questions, []);
assert.ok(Date.parse(planResult.body.data.expiresAt) > Date.now());
const simple = (await json('registry58-boundary/jobs.json')).find(value => value.id === 'V01_V58_GEN').approvedEnhancementInput;
const completion = structuredClone(simple);
completion.planConfirmation = { planId: planResult.body.data.planId, planningContext: null, answers: [] };
cases.push({ id: 'P00', rawPrompt: completion.rawPrompt, actualZeroQuestionPlanId: planResult.body.data.planId });
jobs.unshift({ id: 'P00_V58_GEN', caseId: 'P00', phase: 'GENERATION', version: '58', approvedEnhancementInput: completion });
await writeFile(resolve(here, 'bundle.json'), JSON.stringify({ syntheticOnly: true, cases, jobs,
  phaseLimits: { GENERATION: 18, PLAN: 0, EXECUTION: 0, REVIEW: 0 },
  qualityRetriesAllowed: false, readOnlyOldPlanResult: 'registry58-boundary/results/P00_V58_PLAN.json',
}, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify({ prepared: true, jobs: jobs.length, modelCalls: 0 }));
