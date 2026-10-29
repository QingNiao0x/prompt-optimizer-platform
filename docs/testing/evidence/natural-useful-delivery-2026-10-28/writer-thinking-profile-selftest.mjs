import assert from 'node:assert/strict';
import { readFile, writeFile, mkdir, copyFile, mkdtemp } from 'node:fs/promises';
import { dirname, resolve, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';

const evidence = dirname(fileURLToPath(import.meta.url));
const repo = resolve(evidence, '../../../..');
const base = await mkdtemp(resolve(repo, 'tmp/writer-thinking-infrastructure-check-'));
const results = [];
async function fixture(name, mutate = () => {}) {
  const root = resolve(base, name);
  const target = resolve(root, 'docs/testing/evidence/fixture');
  await mkdir(resolve(target, 'source'), { recursive: true });
  for (const name of ['freeze-live.mjs', 'writer-thinking-profile.mjs', 'official-model-selection-profile.mjs']) await copyFile(resolve(evidence, name), resolve(target, name));
  const bundle = { syntheticOnly: true, experimentProfile: 'WRITER_THINKING_ABLATION_V1',
    parameters: { generationMaxTokens: 8192, executionMaxTokens: 32768, temperature: 0.2, stream: false },
    phaseLimits: { GENERATION: 0, PLAN: 0, EXECUTION: 6, REVIEW: 0 }, cases: [], jobs: [] };
  const requests = [];
  for (const id of ['T01', 'T02', 'T03']) {
    const rawPrompt = '离线合成需求' + id;
    const request = { model: 'deepseek-flash', temperature: 0.2, max_tokens: 8192, thinking: { type: 'disabled' },
      stream: false, response_format: { type: 'json_object' }, messages: [
        { role: 'system', content: 'Return the complete required JSON object.' },
        { role: 'user', content: 'Task JSON:\n' + JSON.stringify({ strategyVersion: '48', rawPrompt, taskScope: { goal: rawPrompt } }) }
      ] };
    const bytes = Buffer.from(JSON.stringify(request));
    const source = resolve(target, 'source/' + id + '.json');
    await writeFile(source, bytes);
    const sample = { id, rawPrompt, executorMaterials: [], writerRequestSource: {
      path: relative(root, source), sha256: createHash('sha256').update(bytes).digest('hex') } };
    bundle.cases.push(sample);
    requests.push({ path: source, request, sample });
    for (const arm of ['OFF', 'ON']) {
      const payload = structuredClone(request);
      payload.max_tokens = 32768;
      if (arm === 'ON') { payload.thinking = { type: 'enabled' }; payload.reasoning_effort = 'high'; }
      bundle.jobs.push({ id: id + '_' + arm, caseId: id, phase: 'EXECUTION', version: '48',
        purpose: 'WRITER_THINKING_ABLATION', thinkingArm: arm, approvedModelRequest: payload });
    }
  }
  await mutate({ root, target, bundle, requests });
  await writeFile(resolve(root, 'bundle.json'), JSON.stringify(bundle));
  return { root, target, bundle, requests };
}
function freeze(value, stage = 'thinking') {
  return spawnSync(process.execPath, [resolve(value.target, 'freeze-live.mjs'), stage, 'bundle.json'], { cwd: value.root, encoding: 'utf8' });
}
const valid = await fixture('valid');
const accepted = freeze(valid);
assert.equal(accepted.status, 0, accepted.stderr);
const protocol = JSON.parse(await readFile(resolve(valid.target, 'thinking/protocol.json'), 'utf8'));
assert.equal(protocol.maximumNewActualCalls, 6);
assert.equal(protocol.writerThinkingProfile.writerMaxTokens, 32768);
assert.equal(protocol.writerThinkingProfile.readTimeoutSeconds, 300);
assert.equal(protocol.writerThinkingProfile.runnerTimeoutMs, 360000);
assert.equal(protocol.writerThinkingProfile.actualWorkQualityEvidence, false);
for (const source of protocol.writerThinkingProfile.writerSources) {
  const snapshot = await readFile(resolve(valid.target, 'thinking', source.snapshot));
  assert.equal(createHash('sha256').update(snapshot).digest('hex'), source.sha256);
}
results.push({ name: 'exact-three-pairs-first-writer-snapshots-and-six-call-limit', passed: true });
const taskHash = createHash('sha256').update(await readFile(resolve(valid.target, 'task-protocol.json'))).digest('hex');
assert.notEqual(freeze(valid, 'thinking-again').status, 0);
assert.equal(createHash('sha256').update(await readFile(resolve(valid.target, 'task-protocol.json'))).digest('hex'), taskHash);
results.push({ name: 'single-frozen-profile-cannot-repeat-and-task-protocol-is-unchanged', passed: true });
for (const [name, mutate] of [
  ['unknown-profile', ({ bundle }) => { bundle.experimentProfile = 'UNKNOWN'; }],
  ['unpaired-input', ({ bundle }) => { bundle.jobs.pop(); }],
  ['extra-call-budget', ({ bundle }) => { bundle.phaseLimits.EXECUTION = 7; }],
  ['generation-route-forbidden', ({ bundle }) => { bundle.jobs[0].phase = 'GENERATION'; }],
  ['wrong-purpose', ({ bundle }) => { bundle.jobs[0].purpose = 'DOWNSTREAM_WORK'; }],
  ['wrong-version', ({ bundle }) => { bundle.jobs[0].version = '47'; }],
  ['duplicate-arm', ({ bundle }) => { bundle.jobs[1].thinkingArm = 'OFF'; }],
  ['off-effort-override', ({ bundle }) => { bundle.jobs[0].approvedModelRequest.reasoning_effort = 'none'; }],
  ['on-low-effort', ({ bundle }) => { bundle.jobs[1].approvedModelRequest.reasoning_effort = 'low'; }],
  ['on-missing-effort', ({ bundle }) => { delete bundle.jobs[1].approvedModelRequest.reasoning_effort; }],
  ['changed-output-cap', ({ bundle }) => { bundle.jobs[1].approvedModelRequest.max_tokens = 8192; }],
  ['changed-system', ({ bundle }) => { bundle.jobs[1].approvedModelRequest.messages[0].content += ' changed'; }],
  ['changed-user', ({ bundle }) => { bundle.jobs[1].approvedModelRequest.messages[1].content += ' changed'; }],
  ['additional-sampling-control', ({ bundle }) => { bundle.jobs[1].approvedModelRequest.top_p = 0.95; }],
  ['json-mode-removed', ({ bundle }) => { delete bundle.jobs[1].approvedModelRequest.response_format; }],
  ['source-sha-mismatch', ({ bundle }) => { bundle.cases[0].writerRequestSource.sha256 = '0'.repeat(64); }],
  ['source-outside-evidence', ({ bundle }) => { bundle.cases[0].writerRequestSource.path = 'outside.json'; }],
  ['execution-only-material', ({ bundle }) => { bundle.cases[0].executorMaterials.push({ content: 'withheld data' }); }],
  ['source-without-scope', async ({ requests }) => {
    const source = requests[0]; const input = JSON.parse(source.request.messages[1].content.slice(source.request.messages[1].content.indexOf('{')));
    delete input.taskScope; source.request.messages[1].content = JSON.stringify(input);
    const bytes = Buffer.from(JSON.stringify(source.request)); await writeFile(source.path, bytes);
    source.sample.writerRequestSource.sha256 = createHash('sha256').update(bytes).digest('hex');
  }]
]) {
  const value = await fixture(name, mutate);
  const rejected = freeze(value);
  assert.notEqual(rejected.status, 0, name);
  results.push({ name, passed: true });
}
console.log(JSON.stringify({ passed: results.length, failed: 0, modelCalls: 0, fixtureDirectory: base, results }));
