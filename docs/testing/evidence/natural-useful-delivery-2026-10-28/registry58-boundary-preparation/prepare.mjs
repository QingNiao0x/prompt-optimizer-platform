import { readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
const here = dirname(fileURLToPath(import.meta.url));
const base = JSON.parse(await readFile(resolve(here, '../registry57-boundary-preparation/bundle.json'), 'utf8'));
const sample = base.cases.find(value => value.id === 'V04');
const input = base.jobs.find(value => value.caseId === 'V04').approvedEnhancementInput;
const strict = structuredClone(input);
strict.rawPrompt = '只允许阅读 src/views/records/create.js 和 src/views/records/import.js 这两个文件，禁止读取任何其他文件，包括它们引用的外部依赖。仅根据这两个文件理解新增与导入的调用入口，读完等待下一步，不修改代码，不交付说明文档。';
// 对照变体携带相同可见材料，但可见性不能撤销用户明确的读取边界。
const sufficient = base.cases.find(value => value.id === 'P00').rawPrompt;
const empty = JSON.parse(await readFile(resolve(here, '../registry57-regression-preparation/bundle.json'), 'utf8')).jobs.find(value => value.caseId === 'Q01').approvedEnhancementInput;
const bundle = { syntheticOnly: true,
  cases: [sample, { id: 'V05', rawPrompt: strict.rawPrompt, variant: '明确限定可读文件，保留禁止读取依赖', unseen: false },
    { id: 'V01', rawPrompt: sufficient, variant: '充分需求允许保真持平', unseen: false },
    { id: 'P00', rawPrompt: sufficient, variant: '真实零问题Plan', unseen: false }],
  jobs: [
    { id: 'V04_V58_GEN', caseId: 'V04', phase: 'GENERATION', version: '58', approvedEnhancementInput: input },
    { id: 'V05_V58_GEN', caseId: 'V05', phase: 'GENERATION', version: '58', approvedEnhancementInput: strict },
    { id: 'V01_V58_GEN', caseId: 'V01', phase: 'GENERATION', version: '58', approvedEnhancementInput: { ...empty, rawPrompt: sufficient } },
    { id: 'P00_V58_PLAN', caseId: 'P00', phase: 'PLAN', version: '58', approvedEnhancementInput: { rawPrompt: sufficient, modelId: 'deepseek:deepseek-flash', contextDescription: '', conversationHistory: [] } },
  ], phaseLimits: { GENERATION: 6, PLAN: 4, EXECUTION: 0, REVIEW: 0 },
};
await writeFile(resolve(here, 'bundle.json'), JSON.stringify(bundle, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify({ prepared: true, modelCalls: 0, cases: bundle.cases.map(value => value.id) }));
