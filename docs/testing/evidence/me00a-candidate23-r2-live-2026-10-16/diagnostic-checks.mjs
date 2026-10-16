import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { combineHardGates } from '../../../../scripts/me00a-rubric.mjs';

// Saved-original checks only. No calls, rewritten answers, ratings or generated tables.
const out = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(out, '../../../..');
const read = p => JSON.parse(fs.readFileSync(p, 'utf8').replace(/^\uFEFF/, ''));
const save = (name, value) => fs.writeFileSync(path.join(out, name),
  JSON.stringify(value, null, 2) + '\n', { flag: 'wx' });
const sha = p => crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
const manifest = read(path.join(root, 'docs/testing/evidence/me00a-candidate23-offline-2026-10-16/candidate23-source-r2.json'));
for (const file of manifest.sourceFiles) {
  if (sha(path.join(root, file.path)) !== file.sha256) throw new Error('Frozen source drift');
}
for (const file of manifest.runtimeFiles) {
  if (sha(path.join(root, manifest.runtimeSnapshot, file.path)) !== file.sha256) throw new Error('Frozen runtime drift');
}
save('source-runtime-verification.json', {
  candidate: 'candidate23-r2', sourceFiles: manifest.sourceFiles.length, runtimeFiles: manifest.runtimeFiles.length,
  sourceTreeSha256: manifest.sourceTreeSha256, runtimeTreeSha256: manifest.runtimeTreeSha256,
  verifiedAfterAllRealCalls: true, modelCalls: 0,
});
const folder = path.join(out, 'real/Q05-plan');
const platform = read(path.join(folder, 'platform.json')).body.data;
const history = read(path.join(folder, 'history-projection.json'));
const lock = read(path.join(folder, 'clipboard-lock.json'));
const inputPaths = ['Q05-plan', 'Q05-plan-pro'].map(id => path.join(out, 'real', id, 'enhanced-actual-execution-input.md'));
if (!inputPaths.every(p => sha(p) === lock.sha256) || history.optimizedPrompt !== platform.optimizedPrompt
  || JSON.stringify(history.sections) !== JSON.stringify(platform.sections)) throw new Error('Actual copy/history mismatch');
const answers = read(path.join(folder, 'actual-answers.json'));
const copy = fs.readFileSync(inputPaths[0], 'utf8');
const prefix = '已委托提出拟议设计，实际采用参数仍未确认';
save('input-copy-history-verification.json', {
  nativeClipboardEqualsBothActualInputs: true, copiedEqualsHistory: true, sectionsEqual: true,
  clipboardSha256: lock.sha256, actualQuestions: answers.length, modelAmbiguities: platform.ambiguities.length,
  sharedStatusPrefixCountInCopy: copy.split(prefix).length - 1,
  allFiveQuestionsRetainedInApi: answers.every(a => platform.ambiguities.some(v => v.includes(a.question))),
  selectedOptions: 0, actualParameterConfirmed: false, scriptAppendedAnswers: false,
  scope: 'Real API and history, injected result store and native copy. Not the complete UI generation flow.',
  historySourceScope: 'API detail does not expose raw planConfirmation. Server buildResultMetadata stores it; no separate database JSON assertion in this batch.',
});
const measurements = read(path.join(out, 'real/work-measurements.json')).works;
const rows = measurements.map(measurement => {
  const work = fs.readFileSync(path.join(out, 'real', measurement.id, 'enhanced-work.md'), 'utf8');
  const refs = [...work.matchAll(/附表[一二三四五六七八九十0-9]+/gu)].map(m => m[0]);
  const findings = measurement.id === 'Q05-plan' ? {
    appendixDelivery: 'FAIL', requestedCategoryFramework: 'FAIL', fullLevelLabels: 'PASS',
    operationalRubricSpecificity: 'OPEN', promptEvaluationObject: 'PASS',
  } : {
    appendixDelivery: 'FAIL', requestedCategoryFramework: 'PASS', fullLevelAnchors: 'FAIL',
    promptEvaluationObject: 'FAIL', questionRoundDefinition: 'OPEN',
  };
  const checks = { length: measurement.length.pass ? 'PASS' : 'FAIL', ...findings };
  return { id: measurement.id, bodyHan: measurement.length.bodyHan, withHeadingsHan: measurement.length.withHeadingsHan,
    checks, gate: combineHardGates(checks), appendixReferences: refs,
    appendixTablesDelivered: measurement.markdownTableRows, originalWorkSha256: measurement.workSha256 };
});
save('final-gates.json', {
  type: 'BOUNDED_OFFLINE_DIAGNOSTIC_NOT_INDEPENDENT_REVIEW', overall: 'NOT_PASSED', rows,
  independentReviews: 0, noRawOrDirectComparator: true, noWinRateOrCausalQualityClaim: true,
  modelCalls: 0, sources: ['real/work-measurements.json', 'real/Q05-plan/enhanced-work.md', 'real/Q05-plan-pro/enhanced-work.md'],
  limits: ['Finite task-specific observations; not a generic appendix or scientific accuracy validator',
    'Five generic labels in Flash do not establish a fully operational rubric',
    'Pro answer-quality wording conflicts with prompt-quality target; no sole-cause model-capability conclusion'],
});
process.stdout.write(JSON.stringify({ inputAndHistory: 'PASS', commonPrefix: 1,
  bodyHan: rows.map(row => row.bodyHan), overall: 'NOT_PASSED', modelCalls: 0 }) + '\n');
