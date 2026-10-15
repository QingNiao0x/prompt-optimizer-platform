/** Offline assessment evidence and fixed-input cross-model diagnostics; this module never calls a provider. */
import { createHash } from 'node:crypto';
import { isDeepStrictEqual } from 'node:util';
import { rawExecutionInput, enhancedExecutionInput } from './me00a-eval.mjs';

const RESPONSIBILITY_SECTIONS = new Set(['BACKGROUND', 'TASK', 'OUTPUT', 'CONSTRAINTS']);
const COVERAGE_STATES = new Set(['COVERED', 'PARTIAL', 'MISSING', 'NOT_APPLICABLE', 'UNVERIFIED']);
const ARMS = ['raw', 'direct', 'plan'];
export const CROSS_MODEL_VENDORS = Object.freeze(['deepseek', 'kimi', 'doubao', 'gpt']);
export const textSha256 = value => createHash('sha256').update(value, 'utf8').digest('hex');

/** Quote existence proves provenance, not professional sufficiency; semantic verdicts remain explicit offline observations. */
export function assessmentCoverageRecord(model, copied, observations = [], works = []) {
  if (!model?.assessment || !Array.isArray(model.sections) || typeof copied !== 'string' || !copied.trim())
    throw new Error('Assessment, sections and literal copied body required');
  const gaps = model.assessment.gaps;
  if (!Array.isArray(gaps) || gaps.length > 6) throw new Error('Invalid assessment gap list');
  const sections = model.sections.filter(section => RESPONSIBILITY_SECTIONS.has(section.type));
  const seen = new Set();
  const checked = observations.map(observation => {
    const { gapIndex, component, status, reason, evidence = [] } = observation;
    if (!Number.isInteger(gapIndex) || !gaps[gapIndex] || typeof component !== 'string' || !component.trim()
        || !COVERAGE_STATES.has(status) || typeof reason !== 'string' || !reason.trim())
      throw new Error('Explicit gap, component, coverage state and reason required');
    const key = `${gapIndex}:${component}`;
    if (seen.has(key)) throw new Error('Duplicate assessment observation');
    seen.add(key);
    // Metadata, examples and acceptance reminders cannot serve as the execution obligation's evidence.
    for (const item of evidence) {
      const section = sections.find(value => value.type === item.sectionType);
      if (!section || typeof item.quote !== 'string' || !item.quote.trim()
          || !section.content.includes(item.quote) || !copied.includes(item.quote))
        throw new Error('Evidence must occur in a responsibility section and the actual copy');
    }
    if (['COVERED', 'PARTIAL'].includes(status) && !evidence.length) throw new Error('Positive coverage needs literal evidence');
    if (status === 'MISSING' && evidence.length) throw new Error('Missing component cannot carry positive coverage evidence');
    return { gapIndex, component, status, reason, evidence, adjudicationMethod: 'OFFLINE_EVIDENCE_REVIEW' };
  });
  const downstream = works.map(work => {
    if (typeof work.id !== 'string' || !work.id || typeof work.text !== 'string'
        || work.inputSha256 !== textSha256(copied)) throw new Error('Downstream work must bind its actual copied input');
    const findings = (work.findings ?? []).map(finding => {
      if (!COVERAGE_STATES.has(finding.status) || typeof finding.component !== 'string'
          || typeof finding.reason !== 'string' || !finding.reason.trim()
          || (finding.quotes ?? []).some(quote => typeof quote !== 'string' || !quote.trim() || !work.text.includes(quote)))
        throw new Error('Downstream finding needs a supported state, reason and true quotes');
      if (['COVERED', 'PARTIAL'].includes(finding.status) && !(finding.quotes?.length))
        throw new Error('Positive work finding needs literal evidence');
      return { ...finding, adjudicationMethod: 'OFFLINE_EVIDENCE_REVIEW' };
    });
    return { id: work.id, inputSha256: work.inputSha256, workSha256: textSha256(work.text), findings };
  });
  return {
    schema: 'me00a-assessment-coverage-v1', runtimeValidation: false, automaticSemanticVerdict: false,
    newModelCalls: 0, copiedSha256: textSha256(copied),
    assessmentSha256: textSha256(JSON.stringify(model.assessment)),
    responsibilitySections: sections, gaps: gaps.map((gap, gapIndex) => ({ gapIndex, ...gap,
      defaultStatus: 'UNVERIFIED', observations: checked.filter(item => item.gapIndex === gapIndex) })),
    downstream,
  };
}

/** Raw gets only actual answers; enhanced arms are the platform's literal copies, without evaluator answer or scale repair. */
export function freezeCrossModelInputs(sample, answers, copies) {
  if (!sample?.id || typeof sample.rawPrompt !== 'string' || !sample.rawPrompt.trim()
      || !Array.isArray(sample.context?.files) || !Array.isArray(answers)
      || answers.some(answer => typeof answer.question !== 'string' || !answer.question.trim()
        || typeof answer.answer !== 'string' || !answer.answer.trim())) throw new Error('Frozen case and actual answers required');
  if (ARMS.slice(1).some(arm => typeof copies?.[arm] !== 'string' || !copies[arm].trim()))
    throw new Error('Both actual platform copies required');
  const input = { raw: rawExecutionInput(sample, answers),
    direct: enhancedExecutionInput(copies.direct, sample), plan: enhancedExecutionInput(copies.plan, sample) };
  return {
    schema: 'me00a-cross-model-input-v1', caseId: sample.id,
    actualAnswersSha256: textSha256(JSON.stringify(answers)),
    materialsSha256: textSha256(JSON.stringify(sample.context.files)),
    copiedBodySha256: { direct: textSha256(copies.direct), plan: textSha256(copies.plan) },
    noEvaluatorAnswerRepair: true,
    arms: ARMS.map(arm => ({ arm, input: input[arm], sha256: textSha256(input[arm]) })),
  };
}

/** Require one resolved profile per vendor; all three arms use that profile's same documented settings. */
export function crossModelExecutionPlan(frozen, profiles) {
  if (!Array.isArray(frozen?.arms) || frozen.arms.length !== 3
      || new Set(frozen.arms.map(arm => arm.arm)).size !== 3
      || frozen.arms.some(arm => !ARMS.includes(arm.arm) || textSha256(arm.input) !== arm.sha256))
    throw new Error('Frozen inputs or hashes invalid');
  if (!Array.isArray(profiles) || profiles.length !== 4
      || new Set(profiles.map(profile => profile.vendor)).size !== 4
      || profiles.some(profile => !CROSS_MODEL_VENDORS.includes(profile.vendor) || !profile.modelId
        || !['API', 'WEB'].includes(profile.surface) || !profile.parameters || profile.resolutionStatus !== 'RESOLVED'))
    throw new Error('Four resolved vendor/model/surface profiles required');
  return profiles.flatMap(profile => frozen.arms.map(arm => ({ caseId: frozen.caseId,
    vendor: profile.vendor, modelId: profile.modelId, surface: profile.surface,
    parameters: structuredClone(profile.parameters), arm: arm.arm, repeat: 1,
    input: arm.input, inputSha256: arm.sha256 })));
}

/** Closed batches and phase caps apply before account loading, request reservation or network access. */
export function validateCrossModelBatch(protocol, actual, plan) {
  const batch = protocol?.activeBatch;
  if (batch?.authorizationStatus !== 'APPROVED_BY_USER' || batch.status !== 'OPEN'
      || batch.version !== 'candidate13' || batch.secondRoundAuthorized !== false)
    throw new Error('An open authorized candidate13 batch is required');
  if (!Number.isInteger(actual?.total) || !Number.isInteger(actual?.candidateValidation)
      || !Number.isInteger(actual?.baseline) || actual.total !== actual.baseline + actual.candidateValidation)
    throw new Error('Actual ledger totals must reconcile');
  if (actual.total !== batch.callsBefore || actual.candidateValidation !== batch.candidateCallsBefore)
    throw new Error('Ledger changed since batch freeze');
  if (!Number.isInteger(batch.maximumNewCalls) || batch.maximumNewCalls !== 20
      || actual.total + batch.maximumNewCalls > protocol.maximumActualModelCalls
      || actual.candidateValidation + batch.maximumNewCalls > protocol.phaseLimits?.candidateValidation
      || actual.baseline > protocol.phaseLimits?.baseline) throw new Error('Insufficient frozen total or phase allowance');
  if (!Array.isArray(plan) || plan.length !== 12) throw new Error('Exactly twelve frozen diagnostic works required');
  const coverage = new Set();
  for (const request of plan) {
    const profile = batch.executionProfiles?.find(value => value.vendor === request.vendor);
    if (request.caseId !== 'Q05' || request.repeat !== 1 || !ARMS.includes(request.arm)
        || !profile || profile.modelId !== request.modelId || profile.surface !== request.surface
        || !isDeepStrictEqual(profile.parameters, request.parameters)
        || request.inputSha256 !== batch.inputHashes?.[request.arm]
        || textSha256(request.input) !== request.inputSha256) throw new Error('Request outside frozen batch scope');
    coverage.add(`${request.vendor}:${request.arm}`);
  }
  if (coverage.size !== 12 || CROSS_MODEL_VENDORS.some(vendor => ARMS.some(arm => !coverage.has(`${vendor}:${arm}`))))
    throw new Error('Vendor/arm coverage incomplete or duplicated');
  if (!Array.isArray(batch.reviewProfiles) || batch.reviewProfiles.length !== 2
      || new Set(batch.reviewProfiles.map(profile => profile.vendor)).size !== 2
      || batch.reviewProfiles.some(profile => !CROSS_MODEL_VENDORS.includes(profile.vendor) || !profile.modelId)
      || !isDeepStrictEqual(batch.purposeMaximums, { PLATFORM_GENERATION: 6, EXECUTION: 12, REVIEW: 2 }))
    throw new Error('Frozen purpose allocation and two independent vendor reviewers required');
  return { maximumNewCalls: 20, platformMaximum: 6, works: 12, reviews: 2, secondRoundAuthorized: false };
}
