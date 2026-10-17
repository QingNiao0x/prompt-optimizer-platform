/** Offline evidence audit only: preserve both AI reviews, their differences and independent hard failures. */
import { readFile, writeFile, mkdir, realpath, stat } from 'node:fs/promises';
import { resolve, dirname, relative, isAbsolute, sep } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { createHash } from 'node:crypto';
import assert from 'node:assert/strict';
import { auditIndependentReview } from '../../../../scripts/me00a-review-checks.mjs';
import { completedExecutionWork } from '../../../../scripts/me00a-eval.mjs';
import { parseStrictJson } from '../../../../scripts/me00a-work-checks.mjs';
import { REVIEW_RUBRIC, combineHardGates, scoreDisagreements } from '../../../../scripts/me00a-rubric.mjs';

const PUBLIC = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(PUBLIC, '../../../..');
const WORK = resolve(ROOT, 'tmp/me04-candidate33-live-20261018');
const GROUPS = ['PATCH', 'MAIL'];
const ARMS = ['RAW', 'COMPACT', 'STRUCTURED'];
const REVIEWERS = { R1: 'deepseek-flash', R2: 'deepseek-v4-pro' };
const PARAMETERS = { temperature: 0.2, max_tokens: 10000, thinking: { type: 'disabled' }, stream: false };
const hash = value => createHash('sha256').update(value).digest('hex');
const same = (left, right) => JSON.stringify(left) === JSON.stringify(right);
const relativeVerdicts = { LEFT_WIN: 'WIN', RIGHT_WIN: 'LOSS', TIE: 'TIE', BOTH_FAIL: 'BOTH_FAIL' };

/** Evidence must remain in the repository; reject symlinks escaping it and unexpectedly large inputs. */
async function ownedText(path) {
  const actual = await realpath(path);
  const repo = await realpath(ROOT);
  const rel = relative(repo, actual);
  assert(rel && !rel.startsWith('..') && !isAbsolute(rel), 'Evidence path escaped repository');
  assert((await stat(actual)).size <= 2_000_000, 'Evidence exceeds offline audit input limit');
  return readFile(actual, 'utf8');
}

async function json(path) { return parseStrictJson(await ownedText(path)); }

/** Never replace a prior audit or any review original. Public output contains only anonymous statistics/hashes. */
async function saveNew(path, value, base) {
  assert(path.startsWith(base + sep), 'Audit output outside its fixed directory');
  await mkdir(dirname(path), { recursive: true });
  const repo = await realpath(ROOT);
  const actualBase = await realpath(base);
  const actualParent = await realpath(dirname(path));
  assert(actualBase.startsWith(repo + sep) && (actualParent === actualBase || actualParent.startsWith(actualBase + sep)),
    'Audit output link escaped evidence directory');
  await writeFile(path, `${JSON.stringify(value, null, 2)}\n`, { flag: 'wx', encoding: 'utf8' });
}

/** Convert the unchanged left/right result to enhanced-versus-RAW only after private unblinding. */
export function enhancedVerdict(pair, baseline, enhanced) {
  assert(pair && [pair.left, pair.right].includes(baseline) && [pair.left, pair.right].includes(enhanced)
    && baseline !== enhanced && Object.hasOwn(relativeVerdicts, pair.verdict), 'Exact frozen comparison required');
  if (['TIE', 'BOTH_FAIL'].includes(pair.verdict)) return pair.verdict;
  const winner = pair.verdict === 'LEFT_WIN' ? pair.left : pair.right;
  return winner === enhanced ? 'WIN' : 'LOSS';
}

/** Literal quotes bind to what the reviewer saw: the shared original task, not an unseen execution prompt. */
export function auditReviewText(text, bindings) {
  const audit = auditIndependentReview(text, bindings);
  const directionErrors = [];
  if (audit.strictJsonAndSchemaValid) {
    for (const [pairIndex, pair] of audit.originalReview.pairs.entries()) {
      if (pair.relativeVerdict !== relativeVerdicts[pair.verdict]) directionErrors.push({
        code: 'RELATIVE_VERDICT_DIRECTION_MISMATCH', pairIndex, left: pair.left, right: pair.right,
        originalVerdict: pair.verdict, originalRelativeVerdict: pair.relativeVerdict ?? null,
        requiredRelativeVerdict: relativeVerdicts[pair.verdict],
      });
    }
    if (!Array.isArray(audit.originalReview.promptAmbiguities) || !Array.isArray(audit.originalReview.uncertainties)) {
      directionErrors.push({ code: 'REVIEW_UNCERTAINTY_FIELDS_INVALID' });
    }
  }
  return { ...audit, deterministicChecksPassed: audit.deterministicChecksPassed && !directionErrors.length,
    errors: [...audit.errors, ...directionErrors], relativeVerdictConvention: 'Relative to the unchanged left label',
    sharedOriginalTaskUsedForEvidenceBinding: true };
}

/** Scores never clear hard failure, a reported severe issue or still-open semantic checks. */
export function comparisonGate({ baselineHard, enhancedHard, baselineSevere, enhancedSevere, auditsValid }) {
  const hardGate = combineHardGates({ baseline: baselineHard, enhanced: enhancedHard });
  return { hardGate, baselineSevereReported: baselineSevere, enhancedSevereReported: enhancedSevere,
    severeIssueState: baselineSevere || enhancedSevere ? 'REVIEW_REPORTED_REQUIRES_EVIDENCE_ADJUDICATION' : 'NONE_REPORTED',
    acceptanceGate: hardGate === 'FAIL' ? 'FAIL' : 'OPEN',
    acceptanceBlocked: hardGate !== 'PASS' || baselineSevere || enhancedSevere || !auditsValid,
    limitation: 'An audited literal quote does not prove its interpretation or severity; AI agreement does not automatically close semantic gates.',
    scoresCannotOverrideHardOrSevereFailure: true };
}

/** Verify raw HTTP response, frozen request and saved review all describe the same one independent call. */
async function loadReview(id, lock, works, pairs, gates) {
  const directory = resolve(WORK, 'reviews', id);
  const request = await json(resolve(directory, 'request.json'));
  assert(request.model === REVIEWERS[id] && hash(JSON.stringify(request)) === lock.requestSha256[id]
    && request.messages?.length === 2 && request.messages[0].role === 'system' && request.messages[1].role === 'user'
    && hash(request.messages[0].content) === lock.systemSha256 && hash(request.messages[1].content) === lock.packetSha256
    && same(request.response_format, { type: 'json_object' }), 'Review frozen request drift');
  for (const [key, value] of Object.entries(PARAMETERS)) assert(same(request[key], value), 'Review parameter drift');
  const rawResponse = await ownedText(resolve(directory, 'response.raw.bin'));
  const record = await json(resolve(directory, 'response.json'));
  assert(record.version === 'candidate33-live' && record.sourceCandidate === 'candidate33'
    && record.phase === 'candidateValidation' && record.purpose === 'REVIEW' && record.operation === id
    && same(record.request, request) && record.responseRawSha256 === hash(rawResponse)
    && same(record.response, parseStrictJson(rawResponse)), 'Review raw response/record binding mismatch');
  const text = await ownedText(resolve(directory, 'review.md'));
  assert(completedExecutionWork(record).text === text, 'Saved review differs from complete actual response');
  return { id, requestedModel: request.model, providerReportedModel: record.response.model ?? null,
    requestSha256: hash(JSON.stringify(request)), responseRawSha256: hash(rawResponse), reviewTextSha256: hash(text),
    tokenUsage: record.response.usage ?? null, elapsedMs: record.elapsedMs ?? null,
    audit: auditReviewText(text, { works, pairs, gates }) };
}

async function auditSavedReviews() {
  const casesText = await ownedText(resolve(PUBLIC, 'live-cases.json'));
  const catalogue = parseStrictJson(casesText);
  const mappingText = await ownedText(resolve(WORK, 'reviews/blind-mapping.private.json'));
  const mapping = parseStrictJson(mappingText);
  const lockText = await ownedText(resolve(WORK, 'reviews/input-lock.json'));
  const lock = parseStrictJson(lockText);
  const hardText = await ownedText(resolve(WORK, 'hard-checks.json'));
  const hard = parseStrictJson(hardText);
  assert(catalogue.candidate === 'candidate33' && lock.sourceCandidate === 'candidate33' && lock.independentCalls === true
    && lock.sameSupplier === true && lock.rubricId === REVIEW_RUBRIC.id && lock.casesSha256 === hash(casesText)
    && lock.hardChecksFileSha256 === hash(hardText) && same(lock.parameters, PARAMETERS)
    && mapping.fixedBeforeAnyReviewCall === true && Object.keys(mapping.cases ?? {}).sort().join() === GROUPS.slice().sort().join()
    && hard.candidate === 'candidate33' && hard.casesSha256 === hash(casesText), 'Frozen audit input mismatch');
  const firstRequest = await json(resolve(WORK, 'reviews/R1/request.json'));
  const packet = parseStrictJson(firstRequest.messages?.[1]?.content ?? '');
  assert(hash(firstRequest.messages[1].content) === lock.packetSha256 && packet.rubricId === REVIEW_RUBRIC.id,
    'Frozen blind packet mismatch');
  const works = {}, gates = {}, primary = [], expectedPairs = [];
  const labelInfo = {};
  for (const [index, group] of GROUPS.entries()) {
    const entry = mapping.cases[group];
    const sample = catalogue.cases.find(item => item.id === group);
    assert(sample && entry.task === `TASK_${index + 1}` && sample.planConfirmation === null
      && !sample.files.length && sample.includeExamples === false, 'Review case scope mismatch');
    const task = packet.tasks[entry.task];
    const labels = Object.keys(entry.labels ?? {});
    assert(labels.length === 3 && Object.values(entry.labels).slice().sort().join() === ARMS.slice().sort().join()
      && Object.keys(task.works).sort().join() === labels.slice().sort().join()
      && task.originalTask === sample.rawPrompt && task.actualConfirmations?.length === 0 && task.sharedMaterials?.length === 0,
      'Blind mapping/original task mismatch');
    const taskPairs = [];
    for (let left = 0; left < labels.length; left++) for (let right = left + 1; right < labels.length; right++) {
      taskPairs.push({ left: labels[left], right: labels[right] });
    }
    assert(same(task.pairs, taskPairs), 'Blind pair order drift');
    expectedPairs.push(...taskPairs);
    for (const label of labels) {
      const arm = entry.labels[label];
      const text = await ownedText(resolve(WORK, 'works', group, arm, 'work.md'));
      assert(hash(text) === lock.workHashes[group][arm] && task.works[label].work === text
        && hard.results[group][arm].workSha256 === hash(text), 'Reviewed work/label hash drift');
      const requestText = await ownedText(resolve(WORK, 'works', group, arm, 'request.json'));
      assert(hash(requestText) === lock.requestFileHashes[group][arm], 'Reviewed execution request file drift');
      works[label] = { text, input: sample.rawPrompt, inputSha256: hash(sample.rawPrompt) };
      labelInfo[label] = { group, arm };
      const check = hard.results[group][arm];
      gates[label] = combineHardGates({ mechanical: check.mechanicalGate, complete: check.completeHardGate });
    }
    const baseline = labels.find(label => entry.labels[label] === 'RAW');
    assert(entry.primaryComparisons?.length === 2, 'Exactly two primary comparisons per task required');
    for (const arm of ['COMPACT', 'STRUCTURED']) {
      const enhanced = labels.find(label => entry.labels[label] === arm);
      assert(entry.primaryComparisons.filter(item => item.baseline === baseline && item.enhanced === enhanced && item.enhancedArm === arm).length === 1,
        'Primary comparison mapping drift');
      primary.push({ group, enhancedArm: arm, baseline, enhanced });
    }
  }
  assert(same(lock.pairs, expectedPairs) && Object.keys(packet.tasks).length === GROUPS.length, 'Frozen six-pair scope drift');
  const reviewers = {};
  for (const id of Object.keys(REVIEWERS)) reviewers[id] = await loadReview(id, lock, works, expectedPairs, gates);
  const comparisons = primary.map(comparison => {
    const perReviewer = Object.fromEntries(Object.entries(reviewers).map(([id, value]) => {
      const review = value.audit.originalReview;
      const pair = review?.pairs.find(item => [item.left, item.right].includes(comparison.baseline)
        && [item.left, item.right].includes(comparison.enhanced));
      const severe = label => review?.issues.filter(issue => issue.work === label && issue.severity === 'SEVERE') ?? [];
      return [id, { originalPair: pair ?? null, enhancedRelativeVerdict: pair ? enhancedVerdict(pair, comparison.baseline, comparison.enhanced) : 'UNVERIFIED',
        deterministicReviewValid: value.audit.deterministicChecksPassed,
        baselineSevereIssueIndices: severe(comparison.baseline).map(issue => review.issues.indexOf(issue)),
        enhancedSevereIssueIndices: severe(comparison.enhanced).map(issue => review.issues.indexOf(issue)) }];
    }));
    const values = Object.values(perReviewer);
    return { ...comparison, perReviewer,
      originalVerdictsDisagree: values[0].enhancedRelativeVerdict !== values[1].enhancedRelativeVerdict,
      severeReportsDisagree: Boolean(values[0].baselineSevereIssueIndices.length) !== Boolean(values[1].baselineSevereIssueIndices.length)
        || Boolean(values[0].enhancedSevereIssueIndices.length) !== Boolean(values[1].enhancedSevereIssueIndices.length),
      ...comparisonGate({ baselineHard: gates[comparison.baseline], enhancedHard: gates[comparison.enhanced],
        baselineSevere: values.some(value => value.baselineSevereIssueIndices.length > 0),
        enhancedSevere: values.some(value => value.enhancedSevereIssueIndices.length > 0),
        auditsValid: values.every(value => value.deterministicReviewValid) }) };
  });
  const bothSchemaValid = Object.values(reviewers).every(value => value.audit.strictJsonAndSchemaValid);
  const gaps = bothSchemaValid
    ? scoreDisagreements(reviewers.R1.audit.originalReview.scores, reviewers.R2.audit.originalReview.scores)
      .map(gap => ({ ...gap, ...labelInfo[gap.work] })) : [];
  const details = { schemaVersion: 1, sourceCandidate: 'candidate33', rubricId: REVIEW_RUBRIC.id,
    modelCalls: 0, originalReviewTextUnchanged: true, sharedOriginalTaskEvidenceBinding: true,
    auditInputHashes: { cases: hash(casesText), mapping: hash(mappingText), reviewInputLock: hash(lockText), hardChecks: hash(hardText) },
    gatePolicy: 'Deterministic schema/quote/direction audit only; no automatic semantic judgment, averaging or replacement of a reviewer verdict.',
    reviewers, comparisons, scoreDisagreements: gaps, workHardGates: gates,
    sameSupplier: true, independentAiReviewsNotHumanExperts: true, independentTaskCount: GROUPS.length,
    limitation: 'Two tasks and one work per arm are diagnostic only; no statistical win-rate or global quality claim is established.' };
  const summary = { schemaVersion: 1, sourceCandidate: 'candidate33', rubricId: REVIEW_RUBRIC.id, modelCalls: 0,
    independentTaskCount: GROUPS.length, works: Object.keys(works).length, primaryComparisons: comparisons.length,
    blindPairsPerReviewer: expectedPairs.length, sameSupplier: true, reviewsAreNotHumanExperts: true,
    auditInputHashes: details.auditInputHashes,
    reviewers: Object.fromEntries(Object.entries(reviewers).map(([id, value]) => [id, {
      requestedModel: value.requestedModel, providerReportedModel: value.providerReportedModel,
      requestSha256: value.requestSha256, responseRawSha256: value.responseRawSha256, reviewTextSha256: value.reviewTextSha256,
      strictJsonAndSchemaValid: value.audit.strictJsonAndSchemaValid, deterministicChecksPassed: value.audit.deterministicChecksPassed,
      issueCount: value.audit.originalReview?.issues.length ?? null,
      reportedSevereIssueCount: value.audit.originalReview?.issues.filter(issue => issue.severity === 'SEVERE').length ?? null,
      auditErrors: value.audit.errors.map(error => ({ code: error.code, issueIndex: error.issueIndex ?? null, pairIndex: error.pairIndex ?? null })),
    }])),
    comparisons: comparisons.map(item => ({ group: item.group, enhancedArm: item.enhancedArm,
      originalVerdicts: Object.fromEntries(Object.entries(item.perReviewer).map(([id, value]) => [id, value.enhancedRelativeVerdict])),
      allDeterministicReviewChecksPassed: Object.values(item.perReviewer).every(value => value.deterministicReviewValid),
      originalVerdictsDisagree: item.originalVerdictsDisagree, severeReportsDisagree: item.severeReportsDisagree,
      hardGate: item.hardGate, baselineHardGate: gates[item.baseline], enhancedHardGate: gates[item.enhanced],
      baselineSevereReported: item.baselineSevereReported, enhancedSevereReported: item.enhancedSevereReported,
      severeIssueState: item.severeIssueState, acceptanceGate: item.acceptanceGate, acceptanceBlocked: item.acceptanceBlocked })),
    disagreements: { verdictComparisons: comparisons.filter(item => item.originalVerdictsDisagree).length,
      severeReportComparisons: comparisons.filter(item => item.severeReportsDisagree).length,
      scoreGapsAtLeastTwo: gaps.map(({ group, arm, dimension, first, second, state }) => ({ group, arm, dimension, first, second, state })),
      scoreComparisonAvailable: bothSchemaValid },
    finalAcceptanceGate: combineHardGates(Object.fromEntries(comparisons.map(item => [`${item.group}-${item.enhancedArm}`, item.acceptanceGate]))),
    scoresCannotOverrideHardOrSevereFailure: true, semanticJudgmentAutomaticallyPassed: false,
    limitation: details.limitation };
  await saveNew(resolve(WORK, 'reviews/audit.private.json'), details, WORK);
  await saveNew(resolve(PUBLIC, 'review-audit.json'), summary, PUBLIC);
  console.log(JSON.stringify({ offlineAuditSaved: true, modelCalls: 0, primaryComparisons: comparisons.length,
    reviewAuditsValid: Object.values(reviewers).every(value => value.audit.deterministicChecksPassed),
    finalAcceptanceGate: summary.finalAcceptanceGate }));
}

const directRun = process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href;
if (directRun) {
  if (!process.argv[2]) console.log('Offline usage after both original reviews exist: audit-reviews.mjs audit');
  else { assert(process.argv[2] === 'audit' && process.argv.length === 3, 'Only the fixed offline audit scope is allowed'); await auditSavedReviews(); }
}
