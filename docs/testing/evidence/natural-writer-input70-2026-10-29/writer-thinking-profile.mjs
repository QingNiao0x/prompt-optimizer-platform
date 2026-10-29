import { readFile, readdir } from 'node:fs/promises';
import { resolve, relative, isAbsolute } from 'node:path';
import { createHash } from 'node:crypto';

export const WRITER_THINKING_PROFILE = 'WRITER_THINKING_ABLATION_V1';

/** Compare JSON meaning while retaining every message and all protocol fields. */
export function canonical(value) {
  if (Array.isArray(value)) return '[' + value.map(canonical).join(',') + ']';
  if (value !== null && typeof value === 'object') return '{' + Object.keys(value).sort().map(key => JSON.stringify(key) + ':' + canonical(value[key])).join(',') + '}';
  return JSON.stringify(value);
}

/** This profile is a single future six-call mechanism probe, never a downstream work or repair retry. */
export async function validateWriterThinkingBundle(bundle, { repo, evidence }) {
  if (bundle.experimentProfile !== WRITER_THINKING_PROFILE) throw Error('Unknown writer experiment profile');
  if (bundle.captureOnly || bundle.cases.length !== 3 || bundle.jobs.length !== 6) throw Error('Writer thinking probe requires exactly three paired inputs and six calls');
  if (canonical(bundle.phaseLimits) !== canonical({ GENERATION: 0, PLAN: 0, EXECUTION: 6, REVIEW: 0 })) throw Error('Writer thinking probe must reserve exactly six single-pass mechanism calls');
  if (bundle.parameters?.executionMaxTokens !== 32768 || bundle.parameters?.generationMaxTokens !== 8192
      || bundle.parameters?.temperature !== 0.2 || bundle.parameters?.stream !== false) throw Error('Writer thinking budgets and unchanged base parameters must be explicit');
  for (const item of await readdir(evidence, { withFileTypes: true })) {
    if (!item.isDirectory()) continue;
    try {
      const prior = JSON.parse(await readFile(resolve(evidence, item.name, 'protocol.json'), 'utf8'));
      if (prior.experimentProfile === WRITER_THINKING_PROFILE) throw Error('Writer thinking profile was already frozen; no repeat or closed-stage reopening');
    } catch (failure) { if (failure.code !== 'ENOENT') throw failure; }
  }
  const sources = [];
  for (const sample of bundle.cases) {
    if ((sample.executorMaterials ?? []).length) throw Error('Execution-only materials do not belong in a writer mechanism probe');
    const source = sample.writerRequestSource;
    if (!source || typeof source.path !== 'string' || !/^[a-f0-9]{64}$/.test(source.sha256 ?? '')) throw Error('Actual product HTTP writer request path and SHA256 are required');
    const path = resolve(repo, source.path);
    const within = relative(evidence, path);
    if (isAbsolute(source.path) || isAbsolute(within) || within.startsWith('..') || !within.endsWith('.json')) throw Error('Writer request source must stay inside this synthetic evidence task');
    const bytes = await readFile(path);
    if (createHash('sha256').update(bytes).digest('hex') !== source.sha256) throw Error('Writer request source hash differs');
    const request = JSON.parse(bytes.toString('utf8').replace(/^\uFEFF/, ''));
    const allowed = new Set(['model', 'messages', 'temperature', 'max_tokens', 'thinking', 'stream', 'response_format']);
    if (Object.keys(request).some(key => !allowed.has(key)) || request.model !== 'deepseek-flash'
        || request.temperature !== 0.2 || request.max_tokens !== 8192 || request.stream !== false
        || canonical(request.thinking) !== canonical({ type: 'disabled' })
        || canonical(request.response_format) !== canonical({ type: 'json_object' })
        || !Array.isArray(request.messages) || request.messages.length !== 2
        || request.messages[0]?.role !== 'system' || request.messages[1]?.role !== 'user'
        || request.messages.some(message => typeof message.content !== 'string' || !message.content.trim())) throw Error('Source is not the unmodified disabled/8192 JSON writer request');
    const user = request.messages[1].content;
    const offset = user.indexOf('{');
    if (offset < 0) throw Error('Writer source has no complete task JSON');
    const input = JSON.parse(user.slice(offset));
    if (input.strategyVersion !== '48' || input.rawPrompt !== sample.rawPrompt || !input.taskScope
        || typeof input.taskScope !== 'object' || Array.isArray(input.taskScope)) throw Error('Only actual candidate48 BODY requests with the matching raw input can enter this probe');
    const jobs = bundle.jobs.filter(job => job.caseId === sample.id);
    if (jobs.length !== 2 || new Set(jobs.map(job => job.thinkingArm)).size !== 2
        || !jobs.some(job => job.thinkingArm === 'OFF') || !jobs.some(job => job.thinkingArm === 'ON')) throw Error('Each writer input requires exactly one OFF and one ON call');
    for (const job of jobs) {
      if (job.phase !== 'EXECUTION' || job.version !== '48' || job.purpose !== 'WRITER_THINKING_ABLATION'
          || job.entrypoint !== undefined || job.approvedEnhancementInput !== undefined) throw Error('Thinking profile is restricted to isolated first-writer mechanism replay');
      const expected = structuredClone(request);
      expected.max_tokens = 32768;
      if (job.thinkingArm === 'ON') {
        expected.thinking = { type: 'enabled' };
        expected.reasoning_effort = 'high';
      }
      if (canonical(job.approvedModelRequest) !== canonical(expected)) throw Error('Writer arms may differ only in thinking and effort, with the same explicit 32768 cap');
    }
    sources.push({ caseId: sample.id, originalPath: source.path, sha256: source.sha256,
      snapshot: 'writer-sources/' + sample.id + '.json', bytes });
  }
  return { sources, profile: {
    name: WRITER_THINKING_PROFILE, purpose: 'WRITER_THINKING_ABLATION', maximumActualCalls: 6,
    writerMaxTokens: 32768, readTimeoutSeconds: 300, runnerTimeoutMs: 360000,
    arms: { OFF: { thinking: 'disabled', reasoningEffort: null }, ON: { thinking: 'enabled', reasoningEffort: 'high' } },
    permittedRequestDifferences: ['max_tokens', 'thinking', 'reasoning_effort'],
    unchangedStages: ['SCOPE_ANALYSIS', 'PLAN', 'DOWNSTREAM_EXECUTION'],
    protocolRepairsAllowed: false, qualityRetriesAllowed: false, actualWorkQualityEvidence: false,
    truncationPolicy: 'Record length/timeout/empty content as incomplete delivery or capacity evidence; never treat it as a completed semantic comparison or retry it for quality.',
    writerSources: sources.map(({ bytes, ...source }) => source)
  } };
}
