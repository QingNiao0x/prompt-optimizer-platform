import { readFile, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = dirname(fileURLToPath(import.meta.url));
const captures = process.argv[2] ?? 'captures';
const bundleName = process.argv[3] ?? 'development-bundle.json';
if (!/^[a-z0-9-]+$/.test(captures) || !/^[a-z0-9-]+\.json$/.test(bundleName)) throw Error('Only local capture and bundle names are allowed');
const json = async path => JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''));
const manifest = await json(resolve(root, 'fixtures/manifest.json'));
const cases = [];
const inputs = new Map();
for (const id of ['C01', 'C02', 'Q01', 'V01', 'V02', 'V03']) {
  const path = resolve(root, 'browser', captures, id, 'input.json');
  const bytes = await readFile(path);
  const input = await json(path);
  inputs.set(id, input);
  const codeCase = manifest.cases.find(value => value.id === id);
  cases.push({ id, rawPrompt: input.rawPrompt, inputProvenance: 'Actual workbench directory picker/Worker/IndexedDB or no-project flow; intercepted before model send',
    inputSha256: createHash('sha256').update(bytes).digest('hex'), ...(codeCase ? { deliveryContract: codeCase.contract, allowedWritePaths: codeCase.allowedWritePaths } : {}) });
}
const jobs = [];
for (const version of ['31', '45', '46']) {
  for (const id of version === '46' ? ['C01', 'C02', 'Q01', 'V01', 'V02', 'V03'] : version === '45' ? ['C01', 'Q01'] : ['C01', 'C02', 'Q01']) {
    jobs.push({ id: `${id}_V${version}_GEN`, phase: 'GENERATION', caseId: id, version, approvedEnhancementInput: inputs.get(id) });
  }
}
jobs.push({ id: 'P01_V46_PLAN', phase: 'PLAN', caseId: 'C01', version: '46', approvedEnhancementInput: inputs.get('C01') });
const undecided = structuredClone(inputs.get('C01'));
undecided.rawPrompt = '请在重点记录模块@synthetic-survey-workspace/src/views/records 的新增和导入功能增加调查时间限制：只能填写当日之前的日期，同时又不能超过今日。今天是否允许填写由我确认，不要代选。\n\n' + manifest.cases[0].contract;
cases.push({ id: 'P02', rawPrompt: undecided.rawPrompt, inputProvenance: 'API DTO derived from actual C01 context; not captured as a separate UI request', deliveryContract: manifest.cases[0].contract });
jobs.push({ id: 'P02_V46_PLAN', phase: 'PLAN', caseId: 'P02', version: '46', approvedEnhancementInput: undecided });
await writeFile(resolve(root, bundleName), JSON.stringify({ syntheticOnly: true, phaseLimits: { GENERATION: 44, PLAN: 8, EXECUTION: 0, REVIEW: 0 },
  cases, jobs }, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify({ cases: cases.length, jobs: jobs.length, maximumCalls: 52, actualCalls: 0 }));
