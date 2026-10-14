import { readFile, writeFile, readdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { createHash } from 'node:crypto';
import { rawExecutionInput } from '../../../../../scripts/me00a-eval.mjs';
const root = resolve('tmp/me00a-20261012');
const output = resolve('docs/testing/evidence/me00a-candidate12-2026-10-14');
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const load = async path => JSON.parse(await readFile(path, 'utf8'));
const save = (path, value) => writeFile(path, JSON.stringify(value, null, 2) + '\n', { flag: 'wx' });
const manifest = await load(resolve(output, 'candidate12-source.json'));
for (const file of [...manifest.sourceFiles, ...manifest.classFiles, ...manifest.policyFiles]) {
  if (hash(await readFile(resolve(file.path))) !== file.sha256) throw new Error(`Frozen file changed: ${file.path}`);
}
const protocol = await load(resolve(root, 'protocol.json'));
const traceFiles = (await readdir(resolve(root, 'traces'))).filter(name => /^\d+\.json$/.test(name));
if (traceFiles.length !== 219 || protocol.phaseLimits.candidateValidation !== 149
    || protocol.maximumActualModelCalls !== 229 || protocol.activeBatch.authorizationStatus !== 'CLOSED_AFTER_AUTHORIZED_ONE_ROUND')
  throw new Error('Actual budget differs from explicitly approved numerical change');
const catalogue = await load(resolve('docs/testing/evidence/me00a-candidate11-2026-10-14/q05-cases.json'));
const sample = catalogue.find(item => item.id === 'Q05');
const answers = (await load(resolve('docs/testing/evidence/me00a-candidate9-2026-10-14/q05-flash/actual-answers.json'))).answers;
const originalPlan = await load(resolve('docs/testing/evidence/me00a-candidate9-2026-10-14/q05-flash/plan.json'));
const planInput = await load(resolve('docs/testing/evidence/me00a-candidate9-2026-10-14/q05-flash/input.json'));
if (!sample || answers.length !== 4 || originalPlan.body.data.questions.length !== 4 || sample.context.files.length !== 0
    || planInput.rawPrompt !== sample.rawPrompt) throw new Error('Frozen Q05 parity fixture mismatch');
const writeFixture = async (name, value) => { const bytes = JSON.stringify(value, null, 2); await writeFile(resolve(root, name), bytes, { flag: 'wx' }); return hash(bytes); };
const batch = {
  version: 'candidate12', group: 'Q05_FIXED_INFORMATION_PLATFORM_REPAIR_AND_CROSS_DOWNSTREAM', mode: 'same-information-replay',
  cases: ['Q05'], model: 'deepseek-flash', executionModels: ['deepseek-flash', 'deepseek-v4-pro'],
  callsBefore: 219, maximumNewCalls: 14, maximumActualCallsAfterBatch: 233,
  maximumGenerationCalls: 6, maximumExecutionCalls: 6, maximumReviewCalls: 2, maximumPlanningCalls: 0,
  authorizationStatus: 'APPROVED_BY_USER', authorizationAmendment: 'protocol-amendment-09.json', secondRoundAuthorized: false,
  casesFile: 'candidate12-q05-cases.json', casesSha256: await writeFixture('candidate12-q05-cases.json', [sample]),
  answersFile: 'candidate12-actual-answers.json', answersSha256: await writeFixture('candidate12-actual-answers.json', { answers }),
  questionsFile: 'candidate12-plan-questions.json', questionsSha256: await writeFixture('candidate12-plan-questions.json', originalPlan.body.data.questions),
  planInputFile: 'candidate12-plan-input.json', planInputSha256: await writeFixture('candidate12-plan-input.json', planInput),
  allowedRawPromptSha256: [hash(sample.rawPrompt.trim()), hash(rawExecutionInput(sample, answers).trim())],
  accountCreationAllowed: false, noPhaseBorrowing: true, noClosedBatchAllowanceTransfer: true
};
await save(resolve(output, 'protocol-before-amendment-09.json'), protocol);
const amendment = { id: 'protocol-amendment-09', authorizedAt: new Date().toISOString(),
  authorization: '用户明确回复：将数值上限增加14次，执行已冻结范围',
  previousLimits: { total: 229, baseline: 80, candidateValidation: 149 },
  approvedLimits: { total: 243, baseline: 80, candidateValidation: 163 }, batch,
  routineEvaluationAuthorizationAlreadyGranted: true, everyFailedAttemptCounts: true };
await save(resolve(output, 'protocol-amendment-09.json'), amendment);
protocol.maximumActualModelCalls = 243; protocol.phaseLimits.candidateValidation = 163; protocol.activeBatch = batch;
await writeFile(resolve(root, 'protocol.json'), JSON.stringify(protocol, null, 2));
await save(resolve(output, 'protocol-approved.json'), protocol);
await save(resolve(output, 'live-harness-freeze.json'), { files: await Promise.all([
  'MeEvaluationServer.java', 'start-candidate12.ps1', 'approve-batch.mjs', 'real-revalidation.mjs', 'account-browser.mjs'
].map(async name => ({ path: `harness/${name}`, sha256: hash(await readFile(resolve(output, 'harness', name))) }))),
  productionSourceUnchangedFromCandidate12: true, numericalAuthorizationRecorded: true });
console.log('APPROVED candidate12 max14 total243 candidate163 baseline80; source unchanged');
