import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { summarizeRequestsReport, requestsCheck } from './prepare-reviews.mjs';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../../../..');
const WORK = resolve(ROOT, 'tmp/me04-candidate34-live-20261018');
const digest = value => createHash('sha256').update(value).digest('hex');

function fixture() {
  return {
    mechanicalGate: 'PASS', originalTaskMechanicalGate: 'PASS', syntax: { status: 'PASS', files: [{ status: 'PASS' }] },
    originalTaskChecks: [{ state: 'PASS' }, { state: 'PASS' }],
    authoredTests: [{ state: 'PASS', observation: { authoredTests: { tests: 16, failures: 0, errors: 0, skipped: 0 } } }],
    diagnostics: [{ syntheticCredentialDiagnostic: 'ECHO_OBSERVED', observation: { canarySentToTransport: true } },
      { syntheticCredentialDiagnostic: 'ECHO_OBSERVED', observation: { canarySentToTransport: false } },
      { syntheticCredentialDiagnostic: 'NOT_EXERCISED_NO_DECLARED_URL_INPUT' }],
    enhancedCommitments: { state: 'FAIL', checks: [{ name: 'explicit_credential_non_echo_commitment', state: 'FAIL' }] },
  };
}

test('whitelist drops nested paths, arm names, quoted metadata and captured output', () => {
  const report = fixture();
  const leak = 'IDENTITY_LEAK RAW COMPACT STRUCTURED E:\\private\\native-copy.md';
  report.work = leak;
  report.arm = leak;
  report.runDirectory = leak;
  report.extraction = { files: [{ name: leak, source: leak, sha256: leak }] };
  report.syntax.files[0].name = leak;
  report.originalTaskChecks[0].observation = { stdout: leak, extra: { quote: leak } };
  report.authoredTests[0].file = leak;
  report.authoredTests[0].observation.metadata = { path: leak };
  report.diagnostics[0].observation.returnRepresentation = leak;
  report.enhancedCommitments.copiedPrompt = leak;
  report.enhancedCommitments.checks[0].promptEvidence = leak;
  report.enhancedCommitments.checks[0].reason = leak;
  report.enhancedCommitments.checks[0].evidence = [{ quote: leak }];
  const projected = summarizeRequestsReport(report);
  const text = JSON.stringify(projected);
  for (const value of ['IDENTITY_LEAK', 'RAW', 'COMPACT', 'STRUCTURED', 'native-copy', 'private', 'returnRepresentation'])
    assert.equal(text.includes(value), false, `Leaked metadata: ${value}`);
  assert.equal(projected.completeHardGate, 'FAIL');
});

test('known failure keeps a fixed useful reason while the supplied free-form reason is omitted', () => {
  const report = fixture();
  report.enhancedCommitments.checks[0].reason = 'hidden source label and arbitrary quotation';
  const projected = summarizeRequestsReport(report);
  assert.equal(projected.declaredRequirements.checks[0].state, 'FAIL');
  assert.match(projected.declaredRequirements.checks[0].reason, /Synthetic credential/);
  assert.equal(JSON.stringify(projected).includes('hidden source'), false);
});

test('mechanical pass plus declared failure remains FAIL despite open semantic checks', () => {
  const projected = summarizeRequestsReport(fixture());
  assert.equal(projected.originalTask.state, 'PASS');
  assert.equal(projected.mechanicalGate, 'PASS');
  assert.equal(projected.declaredRequirements.state, 'FAIL');
  assert.equal(projected.completeHardGate, 'FAIL');
});

test('unknown declared failure cannot disappear through the check-name whitelist', () => {
  const report = fixture();
  report.enhancedCommitments = { state: 'OPEN', checks: [{ name: 'RAW path=secret', state: 'FAIL', reason: 'hidden' }] };
  const projected = summarizeRequestsReport(report);
  assert.equal(projected.declaredRequirements.state, 'FAIL');
  assert.equal(projected.completeHardGate, 'FAIL');
  assert.equal(JSON.stringify(projected).includes('RAW'), false);
});

test('supplementary echo does not become an absent original-task requirement', () => {
  const report = fixture();
  report.enhancedCommitments = { state: 'NOT_APPLICABLE_TO_ORIGINAL_TASK', checks: [] };
  report.authoredTests = [];
  const projected = summarizeRequestsReport(report);
  assert.equal(projected.mechanicalGate, 'PASS');
  assert.equal(projected.declaredRequirements.state, 'NOT_APPLICABLE');
  assert.equal(projected.authoredTests.state, 'NOT_PROVIDED');
  assert.equal(projected.completeHardGate, 'OPEN');
});

test('observed echo count does not pretend each canary reached mocked transport', () => {
  const projected = summarizeRequestsReport(fixture());
  assert.equal(projected.syntheticCredentialDiagnostics.echoObservations, 2);
  assert.equal(projected.syntheticCredentialDiagnostics.echoCasesWithCanarySentToMockTransport, 1);
  assert.equal(projected.syntheticCredentialDiagnostics.unexercisedCases, 1);
  assert.equal(projected.authoredTests.testsRun, 16);
  assert.equal(projected.authoredTests.failures, 0);
});

test('mechanical and declared passes do not certify unreviewed semantics', () => {
  const report = fixture();
  report.enhancedCommitments = { state: 'PASS', checks: [{ name: 'supplied_executable_core_tests', state: 'PASS' }] };
  assert.equal(summarizeRequestsReport(report).completeHardGate, 'OPEN');
});

for (const [arm, core, echoes, expected] of [['RAW', 6, 5, 'OPEN'], ['COMPACT', 4, 12, 'FAIL'], ['STRUCTURED', 4, 8, 'FAIL']]) {
  test(`actual report projects validated counts and retains its gate: ${arm}`, async () => {
    const path = resolve(WORK, 'requests-checks', arm, 'result.json');
    const before = await readFile(path);
    const work = await readFile(resolve(WORK, 'works/REQUESTS', arm, 'work.md'), 'utf8');
    const checked = await requestsCheck(arm, work, path);
    const { reportSha256, ...blind } = checked;
    assert.equal(reportSha256, digest(before));
    assert.equal(blind.completeHardGate, expected);
    assert.equal(blind.independentSummary.originalTask.checks, core);
    assert.equal(blind.independentSummary.syntheticCredentialDiagnostics.echoObservations, echoes);
    assert.equal(blind.independentSummary.syntheticCredentialDiagnostics.echoCasesWithCanarySentToMockTransport, echoes);
    assert.equal(Object.hasOwn(blind, 'independentReport'), false);
    assert.equal(JSON.stringify(blind).includes('\\'), false);
    for (const label of ['RAW', 'COMPACT', 'STRUCTURED', 'raw_basic.py', 'copiedPrompt', 'promptEvidence'])
      assert.equal(JSON.stringify(blind).includes(label), false);
    assert.equal(digest(await readFile(path)), digest(before), 'Original checker report changed');
  });
}
