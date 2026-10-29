import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdir, readFile, stat, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { isDeepStrictEqual } from 'node:util';

if (process.argv.includes('--help')) {
  console.log('Usage: node engineering-run/check-works.mjs <closed-execution-stage>. Reads JSON responses only and invokes the sealed VM checker; no model calls and no host execution of artifacts.');
  process.exit(0);
}
const stage = process.argv[2];
if (!/^[a-z0-9-]+$/.test(stage ?? '')) throw new Error('A closed execution stage is required');
const here = dirname(fileURLToPath(import.meta.url));
const evidence = resolve(here, '..');
const repo = resolve(evidence, '../../../..');
const stageRoot = resolve(evidence, stage);
const sealed = resolve(evidence, 'sealed-engineering-holdout');
const hash = (value) => createHash('sha256').update(value).digest('hex');
const json = async (file) => JSON.parse((await readFile(file, 'utf8')).replace(/^\uFEFF/, ''));
await stat(resolve(stageRoot, 'closure.json'));
const protocol = await json(resolve(stageRoot, 'protocol.json'));
assert.equal(protocol.parameters.executionMaxTokens, 10000);
assert.equal(protocol.qualityRetriesAllowed, false);
const bundleBytes = await readFile(resolve(here, 'works-bundle.json'));
const bundle = JSON.parse(bundleBytes);
assert.equal(protocol.frozenBundleSha256, hash(bundleBytes));
const stageJobs = await json(resolve(stageRoot, 'jobs.json'));
assert.ok(isDeepStrictEqual(stageJobs, bundle.jobs));
assert.equal(stageJobs.length, 3);
assert.ok(stageJobs.every((job) => job.caseId === 'E01' && job.phase === 'EXECUTION'
  && job.approvedModelRequest.max_tokens === 10000));
const manifestBytes = await readFile(resolve(sealed, 'manifest.json'));
assert.equal(hash(manifestBytes), 'a7f9c19244af011966e6558e2aeb7877e3d8d466b7a6f4f6a2fa3be2be4311af');
const manifest = JSON.parse(manifestBytes);
for (const entry of manifest.files) assert.equal(hash(await readFile(resolve(sealed, entry.path))), entry.sha256);
const executionProjectBytes = await readFile(resolve(here, 'execution-project.json'));
assert.equal(hash(executionProjectBytes), bundle.cases[0].executionProjectSha256);
const executionProject = JSON.parse(executionProjectBytes);
assert.equal(executionProject.files.length, 30);
for (const entry of executionProject.fileHashes) {
  assert.equal(hash(await readFile(resolve(sealed, 'inputs/project', entry.path))), entry.sha256);
}
// Import only the already-hashed trusted checker. Artifact JavaScript is never
// imported by Node, placed on a host module path, or launched through a shell.
const { evaluateArtifact } = await import(pathToFileURL(resolve(sealed, 'answer-cards/checker.mjs')).href);
const directory = resolve(here, 'work-results', stage);
await mkdir(directory, { recursive: true });
const results = [];
for (const job of stageJobs) {
  const responsePath = resolve(stageRoot, 'results', `${job.id}.json`);
  const responseBytes = await readFile(responsePath);
  const response = JSON.parse(responseBytes);
  assert.equal(response.id, job.id);
  assert.equal(response.caseId, 'E01');
  assert.equal(response.phase, 'EXECUTION');
  assert.equal(response.version, job.version);
  const result = { jobId: job.id, caseId: 'E01', arm: job.version,
    sourceResponseSha256: hash(responseBytes), upstreamStatus: response.status,
    modelCallsMadeByChecker: 0, artifactsExecutedOnHost: false,
    sealedCheckCount: 28, checksExecuted: 0, passedChecks: 0, passed: false };
  const payload = response.body?.data;
  const text = payload?.content;
  result.finishReason = payload?.finishReason ?? null;
  result.usage = payload?.usage ?? null;
  if (typeof text === 'string') {
    await writeFile(resolve(directory, `${job.id}-raw-response.txt`), text, { flag: 'wx' });
    result.rawResponseSha256 = hash(text);
  }
  if (response.status !== 200) {
    result.stage = 'upstream';
    result.reason = 'The original upstream failure is retained; no retry or checker execution';
  } else if (result.finishReason !== 'stop') {
    result.stage = 'incomplete';
    result.reason = 'Non-stop finishReason retained; no repair, token adjustment, or code execution';
  } else if (typeof text !== 'string' || !text.trim()) {
    result.stage = 'format';
    result.reason = 'Missing original content';
  } else {
    let artifact;
    try { artifact = JSON.parse(text); }
    catch {
      result.stage = 'format';
      result.reason = 'The original output is not the requested JSON package; fences and syntax are not repaired';
    }
    if (artifact !== undefined) {
      await writeFile(resolve(directory, `${job.id}-artifact.json`), JSON.stringify(artifact, null, 2) + '\n', { flag: 'wx' });
      const evaluation = evaluateArtifact(artifact, { dependencyRoot: resolve(repo, 'apps/web') });
      result.stage = evaluation.stage;
      result.passed = evaluation.passed;
      result.checksExecuted = evaluation.checks.length;
      result.passedChecks = evaluation.checks.filter((item) => item.passed).length;
      result.changedFiles = Array.isArray(artifact?.files) ? artifact.files.map((item) => item?.path) : [];
      result.evaluation = evaluation;
      if (evaluation.stage === 'runtime' && evaluation.checks.length) assert.equal(evaluation.checks.length, 28);
    }
  }
  results.push(result);
  await writeFile(resolve(directory, `${job.id}-result.json`), JSON.stringify(result, null, 2) + '\n', { flag: 'wx' });
}
for (const entry of manifest.files) assert.equal(hash(await readFile(resolve(sealed, entry.path))), entry.sha256);
const report = { caseId: 'E01', executionStage: stage, worksBundleSha256: hash(bundleBytes),
  engineeringManifestSha256: hash(manifestBytes), executionProjectSha256: hash(executionProjectBytes),
  originalProjectFilesPerArm: 30, outputTokenLimitPerArm: 10000,
  modelsCalledByThisChecker: 0, hostArtifactExecution: false,
  artifactRuntime: 'sealed TS/Vue compiler + isolated VM with real Vue renderer',
  browserGeometryValidated: false, additionalTestsOrRulesAdded: false,
  allPassed: results.every((result) => result.passed), results };
await writeFile(resolve(directory, 'summary.json'), JSON.stringify(report, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify({ caseId: 'E01', stage, modelCalls: 0, hostArtifactExecution: false,
  arms: results.map((result) => ({ jobId: result.jobId, arm: result.arm, finishReason: result.finishReason,
    stage: result.stage, passed: result.passed, checksExecuted: result.checksExecuted, passedChecks: result.passedChecks })) }));
