import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { combineHardGates, scoreDisagreements } from '../../../../scripts/me00a-rubric.mjs';

// Only derive diagnostics from saved originals; no model calls or review rewriting.
const folder = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(folder, '../../../..');
const read = name => JSON.parse(fs.readFileSync(path.join(folder, name), 'utf8').replace(/^\uFEFF/, ''));
const write = (name, value) => fs.writeFileSync(path.join(folder, name),
  JSON.stringify(value, null, 2) + '\n', { flag: 'wx' });
const sha = file => crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
const initial = read('evaluation-tools-initial-sha256.json');
const files = fs.readdirSync(folder).filter(name => /\.(mjs|py|ps1|java)$/.test(name));
write('evaluation-tools-final-sha256.json', {
  modelCalls: 0,
  tools: files.map(name => {
    const relative = path.relative(root, path.join(folder, name));
    return { path: relative.replaceAll('\\', '/'), sha256: sha(path.join(folder, name)),
      initialSha256: (initial[relative] ?? initial[relative.replaceAll('\\', '/')]) ?? null,
      changedAfterInitialRegistry: (initial[relative] ?? initial[relative.replaceAll('\\', '/')]) ? (initial[relative] ?? initial[relative.replaceAll('\\', '/')]) !== sha(path.join(folder, name)) : null };
  }),
  explanations: [
    'After the two-call Plan ceiling failed, the existing generation/execution/review purposes were restricted to the independent Flash raw/direct subset, both without actual answers; no allocation transfer',
    'The first copy attempt read a missing Plan enhancement path; it stopped before execution I/O and was fixed to copy only the successful direct result',
    'The original Plan failures, reviews and works are unchanged; actual per-purpose counts remain in the closed ledger',
    'Added offline PlanFailureReplay and final derivation tools do not call models',
  ],
});
const reviews = read('real/review-summary.json');
const first = {}, second = {};
reviews.summaries.forEach((review, index) => {
  for (const verdict of review.verdicts) {
    (index ? second : first)[verdict.id + '/A'] = verdict.scoresA;
    (index ? second : first)[verdict.id + '/B'] = verdict.scoresB;
  }
});
write('review-disagreements.json', {
  type: 'DERIVED_DIAGNOSTIC_NOT_ADJUDICATION',
  independentAiNotHumanExperts: true,
  sameProviderDifferentModels: true,
  ordinalScoreGapsAtLeastTwo: scoreDisagreements(first, second),
  verdictDisagreements: reviews.summaries[0].verdicts.flatMap(a => {
    const b = reviews.summaries[1].verdicts.find(v => v.id === a.id);
    return a.preferred === b.preferred ? [] : [{ id: a.id, first: a.preferred, second: b.preferred }];
  }),
  statisticalClaim: 'None: one reused Q05 task, one sample per arm; no statistical effectiveness claim',
  adoptionDecision: 'NOT_PASSED: strict review JSON and literal-quote errors preserved; independent gates take precedence',
});
const mechanical = read('real/hard-gates.json');
const extra = {
  'Q05-direct-same-info/enhanced': { plannedRepetitionsAndAllocation: 'FAIL', operationalScoringProposal: 'FAIL' },
};
const rows = Object.entries(mechanical).map(([id, gate]) => {
  const checks = { existingMeasuredGate: gate, ...extra[id] };
  return { id, checks, gate: combineHardGates(checks) };
});
write('final-gates.json', {
  candidate: 'candidate22', overall: 'NOT_PASSED', modelCalls: 0, rows,
  sources: ['real/hard-gates.json', 'real/work-measurements.json', 'substantive-check.json'],
  notes: [
    'OPEN remains unverified, never PASS',
    'Both execution arms have zero actual confirmation information after natural Plan failed; not a fresh three-arm or natural QA benefit test',
    'Same-information comparisons share actual answers; natural question-answer benefit is a different track',
    'No aggregate win rate, no averaging away severe errors, no revised model works',
    'Candidate23 offline changes are not validated by these candidate22 works',
  ],
});
process.stdout.write(JSON.stringify({ realModelCalls: 0, finalGates: rows.length, status: 'NOT_PASSED' }) + '\n');
