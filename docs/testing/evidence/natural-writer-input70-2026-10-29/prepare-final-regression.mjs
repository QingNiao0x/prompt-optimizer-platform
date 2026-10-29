/** 在采用前回查已知资源分配失败；不改70代码或写作指导，不把旧题重新叫做留出。 */
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
const evidence = dirname(fileURLToPath(import.meta.url)), repo = resolve(evidence, '../../../..');
const oldRoot = resolve(evidence, '../natural-useful-delivery-2026-10-28');
const json = async path => JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''));
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const save = (path, value) => writeFile(path, JSON.stringify(value, null, 2) + '\n', { flag: 'wx' });
const task = await json(resolve(evidence, 'authorization.json'));
assert.equal(task.authorizedAdditionalMaximum, 48);
let actual = 0;
for (const stage of ['regression', 'new-31', 'new-69', 'new-70', 'new-70-allocated', 'works']) {
  const closure = await json(resolve(evidence, stage, 'closure.json'));
  assert.equal(closure.status, 'CLOSED'); actual += closure.actualCalls;
}
assert.equal(actual, 35);
const sample = (await json(resolve(oldRoot, 'sealed-registry52-holdout/cases.json'))).cases.find(item => item.id === 'R52-HO-A');
const originalJob = (await json(resolve(oldRoot, 'registry68-holdout-gen/jobs.json')))
  .find(item => item.caseId === sample.id && item.version === '68');
assert.equal(originalJob.approvedEnhancementInput.rawPrompt, sample.rawPrompt);
const dateInput = (await json(resolve(oldRoot, 'writer70-offline/regression-inputs.json'))).inputs
  .find(item => item.caseId === 'P02_DIRECT').approvedEnhancementInput;
const bundle = { syntheticOnly: true, cases: [sample, { id: 'P02_UI', rawPrompt: dateInput.rawPrompt }], jobs: [
  { ...originalJob, id: 'R52-HO-A_V70_GEN', version: '70' },
  { id: 'P02_UI_V70_GEN', caseId: 'P02_UI', phase: 'GENERATION', version: '70', approvedEnhancementInput: dateInput },
], phaseLimits: { GENERATION: 10, PLAN: 0, EXECUTION: 0, REVIEW: 0 } };
await save(resolve(evidence, 'final-regression-bundle.json'), bundle);
await save(resolve(evidence, 'final-regression-allocation.json'), { recordedAt: new Date().toISOString(),
  actualCallsBefore: 35, authorizedMaximum: 48, maximumFurtherGeneration: 10, maximumFurtherWorks: 2,
  maximumAfter: 47, notRequiredToConsumeMaximum: true,
  reason: '已闭阶段释放的未用预留用于相关旧失败的首次70回归及原定真实工作台验证；不重复挑选正文或作品。',
  previouslyApprovedSyntheticOnly: true, newPrivateMaterial: false, codeAndGuidanceUnchanged: true,
  oldGalleryCaseIsKnownRegression: true, galleryGenerationMaximumIntended: 4, workbenchMaximumIntended: 6,
  workReviewMustCheck: ['14人使用12张空白卡片不得承诺每人一张无替代', '配对或轮换必须实际覆盖活动承诺的人数', '大厅活动最多10分钟且不占通道', '不得把练习改成资格认证或虚构展品事实'],
});
const browserRoot = resolve(evidence, 'browser'); await mkdir(browserRoot, { recursive: true });
await save(resolve(browserRoot, 'cases.json'), { cases: [{ id: 'P02_UI', useProject: true }] });
const source = await readFile(resolve(oldRoot, 'browser/live-browser.mjs'), 'utf8');
let browser = source.replaceAll('tmp/natural-useful-delivery-2026-10-28', 'tmp/natural-writer-input70-2026-10-29')
  .replace("from '../fixtures/project.mjs'", "from '../../natural-useful-delivery-2026-10-28/fixtures/project.mjs'")
  .replaceAll('5184', '5194').replace('timeout: 240000', 'timeout: 1140000').replace('timeout: 250000', 'timeout: 1140000')
  .replace("['45', '46'].includes(job.version)", "['45', '46', '70'].includes(job.version)")
  .replace("import assert from 'node:assert/strict';", "import assert from 'node:assert/strict';\nimport { selectFlash } from '../../natural-useful-delivery-2026-10-28/registry51-normal-preparation/browser-support.mjs';")
  .replace("await page.goto(`${frontend}/workbench`);", "await page.goto(`${frontend}/workbench`);\n  await selectFlash(page, context, expect);")
  .replace("const copied = (await page.evaluate", "assert.ok(!generated.optimizedPrompt.includes('UNRELATED_SOURCE_'));\n  const copied = (await page.evaluate")
  .replace("indexedFiles: useProject ? fixture.files.length : 0,", "indexedFiles: useProject ? fixture.files.length : 0,\n    selectedPaths: input.context.files.map(file => file.path),");
assert.ok(browser.includes("selectFlash(page, context, expect)"));
await writeFile(resolve(browserRoot, 'live-browser.mjs'), browser, { flag: 'wx' });
await save(resolve(browserRoot, 'adaptation.json'), { source: 'natural-useful-delivery-2026-10-28/browser/live-browser.mjs',
  sourceSha256: sha(source), targetSha256: sha(browser), sourceProjectFiles: 79,
  actualWorkerIndexAndModelAndClipboardAndHistory: true, actualModelCalls: 0,
  differences: ['本轮私有路径和独占5194端口', '显式点击公开Flash模型', '沿用70冻结截止时间', '70正文生成及无关源码断言'],
});
console.log(JSON.stringify({ prepared: true, nextModelCallsMade: 0, maximumTotalAfter: 47, candidateUnchanged: true }));
