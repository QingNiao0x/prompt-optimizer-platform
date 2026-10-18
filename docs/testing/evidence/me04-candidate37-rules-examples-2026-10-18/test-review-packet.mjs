/** 验证匿名评审输入保留已核实硬失败，并拒绝错绑作品；不发起模型或网页请求。 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { summarizeRequestsReport, requestsCheck } from './prepare-reviews.mjs';

const base = resolve('tmp/me04-candidate37-live-20261018');
const load = async path => JSON.parse(await readFile(resolve(base, path), 'utf8'));
const reports = Object.fromEntries(await Promise.all(['RAW', 'COMPACT', 'STRUCTURED'].map(async arm =>
  [arm, await load(`requests-checks/${arm}/result.json`)])));

test('Direct and chained credential disclosure both block explicit enhanced promises', () => {
  for (const arm of ['COMPACT', 'STRUCTURED']) {
    assert.equal(summarizeRequestsReport(reports[arm]).completeHardGate, 'FAIL');
  }
  const summary = summarizeRequestsReport(reports.STRUCTURED);
  assert.equal(summary.syntheticCredentialDiagnostics.echoObservations, 0);
  assert.equal(summary.syntheticCredentialDiagnostics.standardTracebackEchoObservations, 2);
  assert(summary.declaredRequirements.checks.some(item => item.state === 'FAIL' && /exception cause\/context/.test(item.reason)));
});

test('Original-task observations never acquire another execution input\'s safety or test promises', () => {
  const summary = summarizeRequestsReport(reports.RAW);
  assert.equal(summary.syntheticCredentialDiagnostics.echoObservations, 3);
  assert.equal(summary.declaredRequirements.state, 'NOT_APPLICABLE');
  assert.equal(summary.authoredTests.state, 'NOT_PROVIDED');
  assert.equal(summary.completeHardGate, 'OPEN');
});

test('Passing authored tests cannot override independent disclosure failures', () => {
  for (const [arm, count] of [['COMPACT', 9], ['STRUCTURED', 17]]) {
    const summary = summarizeRequestsReport(reports[arm]);
    assert.equal(summary.authoredTests.state, 'PASS');
    assert.equal(summary.authoredTests.testsRun, count);
    assert.equal(summary.completeHardGate, 'FAIL');
  }
});

test('Blind summaries do not transmit arbitrary report paths, quotations or labels', () => {
  const altered = structuredClone(reports.STRUCTURED);
  const marker = 'PRIVATE_ACCOUNT_PATH_NOT_REVIEW_INPUT';
  altered.work = marker;
  altered.uncovered = [marker];
  altered.enhancedCommitments.checks[0].promptEvidence = marker;
  altered.enhancedCommitments.checks[0].reason = marker;
  assert(!JSON.stringify(summarizeRequestsReport(altered)).includes(marker));
});

test('A report bound to another full work is rejected before review preparation', async () => {
  const work = await readFile(resolve(base, 'works/REQUESTS/STRUCTURED/work.md'), 'utf8');
  await assert.rejects(requestsCheck('STRUCTURED', work + '\nchanged',
    resolve(base, 'requests-checks/STRUCTURED/result.json')), /unchanged full work/);
});

test('The two independent calls saw identical anonymized inputs without account data or supplied answers', async () => {
  const left = await load('reviews/R1/request.json');
  const right = await load('reviews/R2/request.json');
  assert.notEqual(left.model, right.model);
  assert.deepEqual(left.messages, right.messages);
  const packet = JSON.parse(left.messages[1].content);
  assert.deepEqual(Object.keys(packet.tasks), ['TASK_1']);
  const task = packet.tasks.TASK_1;
  assert.deepEqual(task.actualConfirmations, []);
  assert.deepEqual(task.sharedMaterials, []);
  assert.deepEqual(Object.keys(task.works).sort(), ['TASK_1_A', 'TASK_1_B', 'TASK_1_C']);
});
