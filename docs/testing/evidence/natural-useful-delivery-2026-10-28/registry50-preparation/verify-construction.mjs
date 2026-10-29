import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url)), evidence = resolve(here, '..');
const bytes = name => readFile(resolve(evidence, name));
const json = async name => JSON.parse(await bytes(name));
const sha = value => createHash('sha256').update(value).digest('hex');
const parseInput = request => {
  const content = request.messages[1].content;
  return JSON.parse(content.slice(content.indexOf('{')));
};
const comparisons = [];
for (const caseId of ['Q01', 'U02', 'U03']) {
  const base = `registry50-zero-call-capture/${caseId}`;
  const prior = await json(`registry49-zero-call-capture/${caseId}/original-input.json`);
  const next = await json(`${base}/original-input.json`);
  assert.equal(next.strategyVersion, '50');
  const oldVersion = structuredClone(next); oldVersion.strategyVersion = '49';
  assert.deepEqual(oldVersion, prior);
  const review = await json(`${base}/capture-review.json`);
  assert.equal(review.actualModelCalls, 0); assert.equal(review.capturedHttpAttempts, 2);
  assert.equal(review.preparationExtractionQualityProven, false); assert.equal(review.writerResultManufactured, false);
  const prepare = await json(`${base}/preparation-request.json`), writer = await json(`${base}/writer-request.json`);
  for (const request of [prepare, writer]) {
    assert.equal(request.model, 'deepseek-flash'); assert.equal(request.max_tokens, 8192);
    assert.equal(request.temperature, 0.2); assert.equal(request.thinking.type, 'disabled');
    assert.equal(request.response_format.type, 'json_object'); assert.equal(request.stream, false);
  }
  const prepareInput = parseInput(prepare), writerInput = parseInput(writer);
  const core = structuredClone(writerInput); delete core.taskScope; delete core.preparedRequirements;
  assert.deepEqual(core, prepareInput); assert.equal(core.strategyVersion, '50');
  comparisons.push({ caseId, completeOriginal49InputPreservedExceptVersion: true,
    completePrepareAndWriterInputEqual: true, guardStages: review.audits.map(item => item.callStage),
    preparationRequestSha256: sha(await bytes(`${base}/preparation-request.json`)),
    writerRequestSha256: sha(await bytes(`${base}/writer-request.json`)),
    sourceCatalog: prepareInput.taskSources, sourceCatalogMeaning: 'Server-owned id/kind metadata; full original text remains in the unchanged original input fields' });
}
const report = { status: 'ACTUAL50_PRODUCT_BRANCH_CONSTRUCTION_PASS_NOT_EXTRACTION_OR_QUALITY', checkedAt: new Date().toISOString(),
  actualModelCalls: 0, sourceClasses: 'services/api/target/classes after owner engineering complete',
  temporaryRunner: 'tmp/registry50-product-capture-runner', capturedProductHttpAttempts: 6, caseCount: 3,
  parameters: '8192/.2/disabled/JSON/streamfalse', productSourceRecompiled: false,
  sourceEngineeringReport: 'registry50-engineering-verification.json',
  sourceEngineeringReportSha256: sha(await bytes('registry50-engineering-verification.json')), comparisons,
  captureBoundary: 'Actual50 Provider branch and actual strict reader+resolve, complete source directory unchanged. First response is deliberately synthetic/incomplete and proves construction only; writer request factory aborts deliberately before a response. No fixture is authorized as a live registry.',
  mockCallLogsNotActualNetworkCount: 'completed/UPSTREAM_UNAVAILABLE lines come from controlled network-disabled HTTP factory, not a real upstream failure.',
  toolingCorrection: 'Initial readonly report probe assumed taskSources carried text; actual catalog is id/kind metadata. Corrected report uses exact metadata and compares all complete original input fields. No stage, original or model call was changed.' };
await writeFile(resolve(here, 'construction-verification.json'), JSON.stringify(report, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify({ cases: comparisons.length, actualProviderRequests: 6, actualNetworkCalls: 0,
  originalSourceDirectoryPreserved: true, syntheticPreparationNotExtraction: true }));
