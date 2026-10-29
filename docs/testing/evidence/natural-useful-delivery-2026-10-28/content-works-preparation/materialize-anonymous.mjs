import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, realpathSync, writeFileSync } from 'node:fs';
import { dirname, isAbsolute, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

// This entry only packages closed originals. It never executes or retries a job.
// Coordinator provenance stays beside this script, outside the anonymous packet.
const preparation = dirname(fileURLToPath(import.meta.url));
const evidence = resolve(preparation, '..');
const repository = resolve(preparation, '..', '..', '..', '..', '..');
const digest = (value) => createHash('sha256').update(value).digest('hex');
const readJson = (path) => JSON.parse(readFileSync(path, 'utf8'));
const stable = (value) => JSON.stringify(value && typeof value === 'object'
  ? Array.isArray(value) ? value.map((item) => JSON.parse(stable(item)))
    : Object.fromEntries(Object.keys(value).sort().map((key) => [key, JSON.parse(stable(value[key]))]))
  : value);
const assert = (condition, message) => { if (!condition) throw new Error(message); };
const inside = (parent, child) => {
  const suffix = relative(parent, child);
  return suffix === '' || (!isAbsolute(suffix) && suffix !== '..' && !suffix.startsWith(`..${sep}`));
};
const rejectHoldout = (path) => assert(!path.split(/[\\/]/).some((part) => part.toLowerCase() === 'sealed-holdout'), 'Holdout access is outside this entry.');

/** Retain failed/truncated partial content without treating it as a full delivery. */
function classify(result, trace, delivery) {
  const content = typeof result?.body?.data?.content === 'string' ? result.body.data.content : null;
  const httpStatus = Number.isInteger(result?.status) ? result.status : null;
  const apiFinishReason = typeof result?.body?.data?.finishReason === 'string' ? result.body.data.finishReason : null;
  const upstreamHttpStatus = Number.isInteger(trace?.httpStatus) ? trace.httpStatus : null;
  const choices = trace?.response?.choices;
  const choice = Array.isArray(choices) && choices.length === 1 ? choices[0] : null;
  const upstreamFinishReason = typeof choice?.finish_reason === 'string' ? choice.finish_reason : null;
  const status = { httpStatus, upstreamHttpStatus, apiFinishReason, upstreamFinishReason };
  if (!result) return { content, status, state: 'MISSING_RESULT' };
  if (httpStatus === null) return { content, status, state: 'INVALID_RESULT_ENVELOPE' };
  if (!delivery) return { content, status, state: 'MISSING_DELIVERY_EVIDENCE' };
  if (delivery && delivery.status !== httpStatus) return { content, status, state: 'STATUS_EVIDENCE_CONFLICT' };
  if (httpStatus !== 200) return { content, status, state: 'HTTP_FAILURE' };
  if (!trace) return { content, status, state: 'MISSING_UPSTREAM_EVIDENCE' };
  if (upstreamHttpStatus !== 200) return { content, status, state: 'UPSTREAM_HTTP_FAILURE' };
  if (!choice) return { content, status, state: 'INVALID_UPSTREAM_CHOICES' };
  if (apiFinishReason === null || upstreamFinishReason === null) return { content, status, state: 'MISSING_FINISH_REASON' };
  if (apiFinishReason !== upstreamFinishReason) return { content, status, state: 'FINISH_REASON_CONFLICT' };
  if (choice.message?.content !== content) return { content, status, state: 'CONTENT_EVIDENCE_CONFLICT' };
  if (['length', 'max_tokens'].includes(apiFinishReason)) return { content, status, state: 'TRUNCATED' };
  if (apiFinishReason !== 'stop') return { content, status, state: 'NON_FINAL_COMPLETION' };
  if (content === null || content.trim() === '') return { content, status, state: 'EMPTY_RESPONSE' };
  if (delivery.delivered !== true) return { content, status, state: 'DELIVERY_EVIDENCE_CONFLICT' };
  return { content, status, state: 'DELIVERED' };
}

function selfTest() {
  const content = '  原样正文\n\n尾行\n';
  const result = { status: 200, body: { data: { content, finishReason: 'stop' } } };
  const trace = { httpStatus: 200, response: { choices: [{ finish_reason: 'stop', message: { content } }] } };
  const delivery = { status: 200, delivered: true };
  assert(classify(result, trace, delivery).state === 'DELIVERED', 'Valid original was rejected.');
  assert(classify(result, trace, delivery).content === content, 'Original whitespace was changed.');
  const cutResult = structuredClone(result), cutTrace = structuredClone(trace);
  cutResult.body.data.finishReason = 'length';
  cutTrace.response.choices[0].finish_reason = 'length';
  assert(classify(cutResult, cutTrace, delivery).state === 'TRUNCATED', 'Truncation was counted as delivery.');
  assert(classify(cutResult, cutTrace, delivery).content === content, 'Failed partial content was discarded.');
  assert(classify({ status: 503 }, undefined, { status: 503, delivered: false }).state === 'HTTP_FAILURE', 'HTTP failure was counted as delivery.');
  assert(classify(undefined, undefined).state === 'MISSING_RESULT', 'Missing work was counted as delivery.');
  assert(classify(result, undefined, delivery).state === 'MISSING_UPSTREAM_EVIDENCE', 'Missing trace was ignored.');
  assert(classify(result, cutTrace, delivery).state === 'FINISH_REASON_CONFLICT', 'Conflicting finish reason was ignored.');
  assert(classify(result, trace, { status: 500 }).state === 'STATUS_EVIDENCE_CONFLICT', 'Conflicting closure status was ignored.');
  const changed = structuredClone(trace);
  changed.response.choices[0].message.content = 'different';
  assert(classify(result, changed, delivery).state === 'CONTENT_EVIDENCE_CONFLICT', 'Conflicting original content was ignored.');
  console.log(JSON.stringify({ selfTest: 'PASS', modelCalls: 0, filesWritten: 0 }));
}

function main() {
  const args = process.argv.slice(2);
  if (args.length === 1 && args[0] === '--self-test') return selfTest();
  if (args.length === 1 && args[0] === '--help') {
    console.log('node materialize-anonymous.mjs [--stage <closed-stage-directory>] [--output <new-anonymous-directory>] [--check]');
    return;
  }
  let stage = resolve(evidence, 'content-works46');
  let output = resolve(preparation, 'anonymous');
  let checkOnly = false;
  for (let index = 0; index < args.length; index += 1) {
    const argument = args[index];
    if (argument === '--check') checkOnly = true;
    else if (argument === '--stage' || argument === '--output') {
      assert(args[index + 1] && !args[index + 1].startsWith('--'), `Missing value: ${argument}`);
      const value = resolve(args[++index]);
      if (argument === '--stage') stage = value;
      else output = value;
    } else throw new Error(`Unknown argument: ${argument}`);
  }
  rejectHoldout(stage);
  rejectHoldout(output);
  assert(inside(evidence, stage) && stage !== evidence, 'Stage must stay inside this evidence root.');
  assert(inside(evidence, output) && output !== evidence, 'Anonymous output must stay inside this evidence root.');
  assert(!inside(stage, output), 'Anonymous output cannot be inside the closed stage.');
  assert(!existsSync(output), 'Anonymous output already exists; do not replace a review packet.');
  assert(existsSync(resolve(stage, 'closure.json')), 'Stage has not closed; no packet was written.');
  stage = realpathSync(stage);
  rejectHoldout(stage);
  assert(inside(realpathSync(evidence), stage), 'Stage resolves outside the evidence root.');
  // Existing ancestors must also stay within the evidence root; avoid a symlink escape on output.
  let ancestor = dirname(output);
  while (!existsSync(ancestor)) ancestor = dirname(ancestor);
  assert(inside(realpathSync(evidence), realpathSync(ancestor)), 'Output resolves outside the evidence root.');

  const manifest = readJson(resolve(preparation, 'preparation-manifest.json'));
  for (const file of manifest.files) {
    const path = resolve(preparation, file.path);
    assert(inside(preparation, path), 'Prepared manifest path escapes its directory.');
    assert(digest(readFileSync(path)) === file.sha256, `Prepared original digest mismatch: ${file.path}`);
  }
  const bundle = readJson(resolve(preparation, 'bundle.json'));
  const key = readJson(resolve(preparation, 'coordinator-key.json'));
  const plan = readJson(resolve(preparation, 'anonymous-review-plan.json'));
  const criteria = readJson(resolve(preparation, 'criteria.json'));
  const closure = readJson(resolve(stage, 'closure.json'));
  const protocol = readJson(resolve(stage, 'protocol.json'));
  const stageJobs = readJson(resolve(stage, 'jobs.json'));
  assert(closure.status === 'CLOSED' && closure.entryLockedByClosure === true, 'Stage is not closed and locked.');
  assert(bundle.jobs.length === 10 && key.map.length === 10, 'Expected all ten prepared works.');
  const ids = bundle.jobs.map((job) => job.id);
  assert(new Set(ids).size === 10 && new Set(key.map.map((item) => item.jobId)).size === 10, 'Duplicate prepared identity.');
  assert(key.map.every((item) => ids.includes(item.jobId) && /^W\d{2}$/.test(item.workId)), 'Anonymous map does not match the frozen jobs.');
  assert(new Set(key.map.map((item) => item.workId)).size === 10, 'Duplicate anonymous identifier.');
  assert(stable([...protocol.allowedJobs].sort()) === stable([...ids].sort()), 'Closed protocol does not name exactly the prepared jobs.');
  assert(stageJobs.length === 10, 'Closed stage has unexpected jobs.');
  for (const expected of bundle.jobs) {
    const actual = stageJobs.find((job) => job.id === expected.id);
    assert(actual && actual.phase === 'EXECUTION' && actual.caseId === expected.caseId
      && actual.version === expected.version && stable(actual.approvedModelRequest) === stable(expected.approvedModelRequest), 'Closed request differs from the prepared request.');
  }

  const closedFiles = new Map();
  for (const file of closure.files ?? []) {
    let path = isAbsolute(file.path) ? resolve(file.path) : resolve(repository, file.path);
    if (!inside(stage, path)) path = resolve(stage, file.path);
    rejectHoldout(path);
    assert(inside(stage, path) && path !== stage, 'Closure file escapes the closed stage.');
    assert(existsSync(path) && inside(stage, realpathSync(path)), 'Closed original is absent or resolves outside the stage.');
    assert(digest(readFileSync(path)) === file.sha256, 'Closed original digest mismatch.');
    assert(!closedFiles.has(path), 'Closure contains a duplicate file.');
    closedFiles.set(path, file.sha256);
  }
  assert(closedFiles.size > 0, 'Closure has no locked originals.');
  const traces = new Map();
  for (const path of closedFiles.keys()) {
    if (!inside(resolve(stage, 'traces'), path)) continue;
    const trace = readJson(path);
    assert(ids.includes(trace.jobId), 'Closed stage has an unexpected model call.');
    assert(!traces.has(trace.jobId), 'Multiple attempts found; do not select a favorable work.');
    const expected = bundle.jobs.find((job) => job.id === trace.jobId);
    assert(stable(trace.request) === stable(expected.approvedModelRequest), 'Captured model request differs from the frozen request.');
    traces.set(trace.jobId, trace);
  }
  assert(closure.actualCalls === traces.size && traces.size <= 10, 'Closed call accounting is inconsistent.');

  const packet = [];
  const auditWorks = [];
  const enhancedPrompts = bundle.jobs.filter((job) => job.version !== 'ORIGINAL').map((job) => job.approvedModelRequest.messages[1].content);
  for (const mapping of [...key.map].sort((left, right) => left.workId.localeCompare(right.workId))) {
    const expected = bundle.jobs.find((job) => job.id === mapping.jobId);
    assert(mapping.caseId === expected.caseId && mapping.arm === expected.version, 'Anonymous key does not match its prepared case.');
    const path = resolve(stage, 'results', `${mapping.jobId}.json`);
    assert(!existsSync(path) || closedFiles.has(path), 'Result exists without a closure digest.');
    let result = null;
    let resultParseError = false;
    if (closedFiles.has(path)) {
      try { result = readJson(path); } catch { resultParseError = true; }
    }
    if (result) assert(result.id === mapping.jobId && result.caseId === mapping.caseId && result.phase === 'EXECUTION', 'Result identity does not match its frozen job.');
    const deliveries = (closure.deliveries ?? []).filter((item) => item.id === mapping.jobId);
    assert(deliveries.length <= 1, 'Closure has duplicate delivery records.');
    const classified = classify(result, traces.get(mapping.jobId), deliveries[0]);
    if (resultParseError) classified.state = 'INVALID_RESULT_JSON';
    if (classified.content !== null) {
      assert(!ids.some((id) => classified.content.includes(id)), 'Work contains a job identifier; coordinator identity review is required before distribution.');
      assert(!enhancedPrompts.some((prompt) => classified.content.includes(prompt)), 'Work echoes a complete enhanced prompt; coordinator identity review is required before distribution.');
      assert(!/(?:策略版本|候选版本|chainVersion|strategyVersion)\s*[：:=]?\s*["']?(?:31|46)\b/i.test(classified.content), 'Work contains a strategy identity; coordinator identity review is required before distribution.');
    }
    // No wrapper error body is copied: it may contain request identifiers or a model brand.
    // All available textual work, including a failed partial answer, remains byte-for-byte unchanged.
    packet.push({ workId: mapping.workId, caseId: mapping.caseId, ...classified });
    auditWorks.push({ ...mapping, sourceResult: relative(repository, path), resultSha256: closedFiles.get(path) ?? null, state: classified.state, contentSha256: classified.content === null ? null : digest(classified.content) });
  }
  const pairs = plan.comparisons.map((comparison, index) => ({ comparisonId: `P${String(index + 1).padStart(2, '0')}`, caseId: comparison.caseId, left: comparison.left, right: comparison.right }));
  const rubric = {
    common: criteria.common,
    cases: criteria.cases,
    severity: criteria.severity,
    comparison: Object.fromEntries(['judgments', 'firstAssessEachWorkIndependently', 'explainWithQuotedEvidenceAndRequiredEdits', 'missingOrTruncatedWorkIsNotOpponentSemanticWin', 'sufficientInputTieIsFidelityPassNotImprovement', 'longerWorkOrMoreDimensionsDoNotWinAutomatically', 'reviewers'].map((field) => [field, criteria.comparison[field]])),
    individualTemplate: plan.individualTemplate,
    pairTemplate: plan.pairTemplate,
    disclosure: plan.disclosure,
  };
  const summary = { state: 'CLOSED_INPUTS_VERIFIED', works: packet.length, delivered: packet.filter((item) => item.state === 'DELIVERED').length, failures: packet.filter((item) => item.state !== 'DELIVERED').length, modelCallsMadeByThisEntry: 0 };
  if (checkOnly) { console.log(JSON.stringify({ ...summary, filesWritten: 0 }, null, 2)); return; }

  const auditPath = resolve(preparation, 'materialization-coordinator-audit.json');
  assert(!existsSync(auditPath), 'Coordinator audit already exists; do not replace an earlier packet record.');
  mkdirSync(output, { recursive: true });
  for (const directory of ['questions', 'materials', 'works']) mkdirSync(resolve(output, directory));
  const writeJson = (path, value) => writeFileSync(path, `${JSON.stringify(value, null, 2)}\n`, { encoding: 'utf8', flag: 'wx' });
  for (const item of bundle.cases) {
    writeFileSync(resolve(output, 'questions', `${item.id}.txt`), item.rawPrompt, { encoding: 'utf8', flag: 'wx' });
    if (item.id === 'Q01') {
      assert(item.executorMaterials.length === 1, 'Unexpected shared query material.');
      writeFileSync(resolve(output, 'materials', 'Q01.json'), item.executorMaterials[0].content, { encoding: 'utf8', flag: 'wx' });
    } else {
      assert(item.id === 'V03' && item.executorMaterials.length === 0 && item.explicitNoMaterials === true, 'Unexpected no-material case.');
      writeFileSync(resolve(output, 'materials', 'V03.txt'), item.commonExecutionContext, { encoding: 'utf8', flag: 'wx' });
    }
  }
  for (const item of packet) {
    if (item.content !== null) writeFileSync(resolve(output, 'works', `${item.workId}.txt`), item.content, { encoding: 'utf8', flag: 'wx' });
    writeJson(resolve(output, 'works', `${item.workId}.json`), { workId: item.workId, caseId: item.caseId, state: item.state, ...item.status, workAvailable: item.content !== null });
  }
  writeJson(resolve(output, 'index.json'), {
    state: 'READY_FOR_BLIND_REVIEW',
    failurePolicy: '失败、缺失和截断单独保留；部分正文不是完整交付，不补采，也不自动判对手语义获胜。',
    works: packet.map((item) => ({ workId: item.workId, caseId: item.caseId, statusFile: `works/${item.workId}.json`, workFile: item.content === null ? null : `works/${item.workId}.txt`, rawQuestionFile: `questions/${item.caseId}.txt`, commonMaterialsFile: `materials/${item.caseId}.${item.caseId === 'Q01' ? 'json' : 'txt'}` })),
  });
  writeJson(resolve(output, 'criteria.json'), rubric);
  writeJson(resolve(output, 'comparisons.json'), pairs);
  writeJson(auditPath, {
    role: 'COORDINATOR_ONLY_UNBLIND_AFTER_REVIEW_LOCK',
    ...summary,
    stage: relative(repository, stage),
    anonymousDirectory: relative(repository, output),
    closedClosureSha256: digest(readFileSync(resolve(stage, 'closure.json'))),
    preparedManifestSha256: digest(readFileSync(resolve(preparation, 'preparation-manifest.json'))),
    materializerSha256: digest(readFileSync(fileURLToPath(import.meta.url))),
    works: auditWorks,
    comparisons: pairs.map((pair, index) => ({ ...pair, originalComparisonId: plan.comparisons[index].comparisonId })),
    revealPolicy: '先锁定匿名逐份审阅与P编号配对结果及其哈希，再由root读取本文件关联原臂；本文件不得提供给盲审者。',
  });
  console.log(JSON.stringify({ ...summary, anonymousDirectory: output, coordinatorAudit: auditPath }, null, 2));
}

try { main(); } catch (error) {
  console.error(`Anonymous materialization stopped: ${error.message}`);
  process.exitCode = 1;
}
