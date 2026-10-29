import { createHash } from 'node:crypto';
import { readFile, readdir } from 'node:fs/promises';
import { resolve } from 'node:path';

export const OFFICIAL_MODEL_SELECTION_PROFILE = 'OFFICIAL_MODEL_SELECTION_ABLATION_V1';
const PRO_MODEL = 'deepseek:deepseek-v4-pro';
const SOURCE_STAGE = 'registry50-development';
const EXPECTED_PHASES = { GENERATION: 12, PLAN: 0, EXECUTION: 0, REVIEW: 0 };
const EXPECTED_PARAMETERS = { generationMaxTokens: 8192, executionMaxTokens: 8192, temperature: 0.2, thinking: 'disabled', stream: false };
const sha = value => createHash('sha256').update(value).digest('hex');
const json = async path => JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''));
const equal = (left, right) => JSON.stringify(left) === JSON.stringify(right);

/** Only this native Pro/50 three-input mechanism; the ordinary unprofiled freezer remains Flash-only. */
export async function validateOfficialModelSelectionBundle(bundle, { evidence }) {
  const profile = bundle.modelSelectionProfile;
  const keys = ['name', 'modelId', 'baselineStage', 'baselineJobsSha256', 'baselineProtocolSha256'].sort();
  if (!profile || typeof profile !== 'object' || Array.isArray(profile)
      || !equal(Object.keys(profile).sort(), keys) || profile.name !== OFFICIAL_MODEL_SELECTION_PROFILE
      || profile.modelId !== PRO_MODEL || profile.baselineStage !== SOURCE_STAGE
      || !/^[a-f0-9]{64}$/.test(profile.baselineJobsSha256 ?? '')
      || !/^[a-f0-9]{64}$/.test(profile.baselineProtocolSha256 ?? '')) throw Error('Unknown or overridden official model-selection profile');
  if (bundle.experimentProfile !== undefined || bundle.captureOnly || bundle.cases.length !== 3 || bundle.jobs.length !== 3
      || !equal(bundle.phaseLimits, EXPECTED_PHASES) || !equal(bundle.parameters, EXPECTED_PARAMETERS))
    throw Error('Native Pro comparison is only three GENERATION50 jobs with fixed12 cap and unchanged8192 parameters');
  for (const entry of await readdir(evidence, { withFileTypes: true })) {
    if (!entry.isDirectory()) continue;
    try {
      const prior = await json(resolve(evidence, entry.name, 'protocol.json'));
      if (prior.modelSelectionProfile?.name === OFFICIAL_MODEL_SELECTION_PROFILE)
        throw Error('This one-pass native model comparison was already frozen; no quality retry or closed-stage reopening');
    } catch (error) { if (error.code !== 'ENOENT') throw error; }
  }
  const baseline = resolve(evidence, SOURCE_STAGE);
  const jobsBytes = await readFile(resolve(baseline, 'jobs.json'));
  const protocolBytes = await readFile(resolve(baseline, 'protocol.json'));
  if (sha(jobsBytes) !== profile.baselineJobsSha256 || sha(protocolBytes) !== profile.baselineProtocolSha256)
    throw Error('Actual closed Flash50 baseline hash changed');
  const closure = await json(resolve(baseline, 'closure.json'));
  const sourceProtocol = JSON.parse(protocolBytes);
  const sourceJobs = JSON.parse(jobsBytes);
  if (closure.status !== 'CLOSED' || closure.entryLockedByClosure !== true
      || sourceProtocol.modelId !== 'deepseek:deepseek-flash' || sourceProtocol.upstreamModel !== 'deepseek-flash')
    throw Error('An immutable closed native Flash50 baseline is required, not a quality PASS');
  const caseIds = ['Q01', 'U02', 'U03'];
  if (!equal(bundle.jobs.map(job => job.caseId), caseIds) || !equal(bundle.cases.map(sample => sample.id), caseIds))
    throw Error('Only the three already-seen matching development inputs are in scope');
  for (const job of bundle.jobs) {
    const original = sourceJobs.find(item => item.id === `${job.caseId}_V50_GEN`);
    if (!original || job.id !== `${job.caseId}_V50_PRO_GEN` || job.version !== '50' || job.phase !== 'GENERATION'
        || job.approvedModelRequest !== undefined || job.entrypoint !== undefined
        || job.approvedEnhancementInput?.modelId !== PRO_MODEL) throw Error('Native Pro jobs must use the actual product50 public DTO route');
    const reverted = structuredClone(job.approvedEnhancementInput); reverted.modelId = 'deepseek:deepseek-flash';
    if (!equal(reverted, original.approvedEnhancementInput)) throw Error('Only public DTO.modelId may differ from closed Flash50 input');
  }
  return { name: OFFICIAL_MODEL_SELECTION_PROFILE, modelId: PRO_MODEL, upstreamModel: 'deepseek-v4-pro',
    routeKey: 'deepseek', endpoint: 'https://api.deepseek.com/chat/completions',
    baselineStage: SOURCE_STAGE, baselineJobsSha256: profile.baselineJobsSha256,
    baselineProtocolSha256: profile.baselineProtocolSha256, maximumActualCalls: 12 };
}
