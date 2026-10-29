import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const evidence = resolve(here, '..');
const prior = JSON.parse(await readFile(resolve(evidence, 'registry57-regression-preparation/bundle.json'), 'utf8'));
const base = structuredClone(prior.jobs.find(job => job.caseId === 'V02').approvedEnhancementInput);
// 同一阅读等待请求，只移动真实共用依赖及其 import；不改变任务范围或注入新的禁令。
base.context.files = base.context.files.map(file => ({ ...file,
  path: file.path.startsWith('src/views/records/dateRules.js') ? file.path.replace('src/views/records/', 'src/shared/') : file.path,
  content: file.content.replaceAll("from './dateRules.js'", "from '../../shared/dateRules.js'"),
}));
const sufficient = '只输出三行：第一行是1，第二行是2，第三行是3，不要其他内容。';
const bundle = {
  syntheticOnly: true,
  cases: [
    { id: 'V04', rawPrompt: base.rawPrompt, variant: '同一目标目录的必要共用依赖位于目录外',
      expectedBoundary: '阅读实际必要依赖；不新增写作、修改或说明文档义务', unseen: false },
    { id: 'P00', rawPrompt: sufficient, variant: '充分需求零问题Plan；实际结果产生后才冻结确认后增强请求', unseen: false },
  ],
  jobs: [
    { id: 'V04_V57_GEN', caseId: 'V04', phase: 'GENERATION', version: '57', approvedEnhancementInput: base },
    { id: 'P00_V57_PLAN', caseId: 'P00', phase: 'PLAN', version: '57', approvedEnhancementInput: {
      rawPrompt: sufficient, modelId: 'deepseek:deepseek-flash', contextDescription: '', conversationHistory: [],
    } },
  ],
  phaseLimits: { GENERATION: 2, PLAN: 4, EXECUTION: 0, REVIEW: 0 },
};
await mkdir(here, { recursive: true });
await writeFile(resolve(here, 'bundle.json'), JSON.stringify(bundle, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify({ cases: bundle.cases.map(sample => sample.id), modelCalls: 0 }));
