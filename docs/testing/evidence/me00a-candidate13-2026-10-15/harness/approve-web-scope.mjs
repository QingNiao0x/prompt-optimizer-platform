import { readFile, readdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
const root = resolve('tmp/me00a-20261012');
const out = resolve('docs/testing/evidence/me00a-candidate13-2026-10-15');
const load = async path => JSON.parse(await readFile(path, 'utf8'));
const saveNew = (path, value) => writeFile(path, JSON.stringify(value, null, 2), { flag: 'wx' });
const protocol = await load(resolve(root, 'protocol.json'));
if (protocol.activeBatch.version !== 'candidate13' || protocol.activeBatch.status !== 'OPEN'
  || protocol.maximumActualModelCalls !== 263 || protocol.phaseLimits.candidateValidation !== 183)
  throw new Error('Approved live batch drift');
const names = (await readdir(resolve(root, 'traces'))).filter(name => /^\d+\.json$/.test(name));
const traces = await Promise.all(names.map(name => load(resolve(root, 'traces', name))));
const actual = { total: traces.length, baseline: traces.filter(t => t.phase === 'baseline').length,
  candidateValidation: traces.filter(t => t.phase === 'candidateValidation').length };
if (actual.total !== 231 || actual.baseline !== 70 || actual.candidateValidation !== 161
  || traces.filter(t => t.version === 'candidate13').length !== 2)
  throw new Error('Unexpected actual ledger; inspect before changing scope');
await saveNew(resolve(out, 'protocol-before-amendment-11.json'), protocol);
const batch = { ...protocol.activeBatch, group: 'Q05_FIXED_COPIES_TWO_AUTHENTICATED_WEB_VENDORS',
  maximumNewCalls: 14, maximumActualCallsAfterBatch: 243, maximumExecutionCalls: 6,
  executionModels: ['deepseek-web-visible-mode', 'doubao-web-visible-mode'],
  authorizationAmendment: 'protocol-amendment-11.json',
  purposeMaximums: { PLATFORM_GENERATION: 6, EXECUTION: 6, REVIEW: 2 },
  webExecution: { deepseek: { url: 'https://chat.deepseek.com/', status: 'VISIBLE_SETTINGS_FREEZE_REQUIRED' },
    doubao: { url: 'https://www.doubao.com/chat/', status: 'USER_CONFIRMED_LOGIN_VISIBLE_SETTINGS_FREEZE_REQUIRED' } },
  stageFreeze: { ...protocol.activeBatch.stageFreeze, apiExecution: 'NOT_IN_CURRENT_SCOPE',
    reviewers: 'DOUBAO_AND_DEEPSEEK_DISTINCT_VENDORS_NEW_CHATS_SAME_ANONYMOUS_PACK' } };
const amendment = { id: 'protocol-amendment-11', recordedAt: new Date().toISOString(),
  authorization: '用户最新指示：豆包已经登录，请打开deep seek，使用这两个AI进行比对，使用增强前后的提示词给这两个AI进行生成作品并比对。',
  scope: 'Narrow prior authorized execution to Doubao WEB and DeepSeek WEB; original same-information/direct/Plan arms retained.',
  formalLimitsUnchanged: { total: 263, baseline: 80, candidateValidation: 183 }, actualAtAmendment: actual,
  alreadyConsumedInBatch: 2, callsBefore: 229, maximumNewCalls: 14,
  allocation: { PLATFORM_GENERATION: 6, EXECUTION: 6, REVIEW: 2 },
  gptAndKimiExcludedFromCurrentBatch: true, closedBatchAllowanceTransferred: 0,
  webSubmissionAttemptsCountIncludingFailures: true, hiddenWebBackendCallsAndTokens: 'UNKNOWN', batch };
await saveNew(resolve(out, 'protocol-amendment-11.json'), amendment);
protocol.activeBatch = batch;
await writeFile(resolve(root, 'protocol.json'), JSON.stringify(protocol, null, 2));
await saveNew(resolve(out, 'protocol-approved-amendment-11.json'), protocol);
await saveNew(resolve(out, 'pre-io-harness-corrections-addendum.json'), {
  corrects: 'pre-io-harness-corrections.json.actualCallsAfter', originalValue: 0, correctHistoricalValue: 229,
  cause: 'Overescaped filename regex counted no trace files; original erroneous record preserved.',
  evidence: 'Traces 001 through 229 existed before the pre-I/O errors; 230 and 231 belong to later successful platform generation.',
  actualAtAddendum: actual, preIoFailuresAddedModelCalls: 0, attribution: 'EVALUATION_HARNESS',
  budgetSource: 'Canonical numeric trace files, never the erroneous addendum predecessor counter.' });
console.log(JSON.stringify({ actual, maximumNewCalls: 14, executionWorks: 6, reviews: 2, formalLimitsUnchanged: true }));
