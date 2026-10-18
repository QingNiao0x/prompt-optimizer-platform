"""Reuse guarded external calls for exactly two same-information works and two independent reviews."""
from pathlib import Path

PUBLIC = Path(__file__).resolve().parent
ROOT = PUBLIC.parents[3]


def main():
    source = (ROOT / 'docs/testing/evidence/me04-candidate37-rules-examples-2026-10-18/external-calls.mjs').read_text(encoding='utf-8')
    source = source.replace('me04-candidate37-rules-examples-2026-10-18', PUBLIC.name).replace('me04-candidate37-live-20261018', 'me01-candidate40-live-20261018')
    source = source.replace('candidate37', 'candidate40').replace("const groups = { PATCH: 'PATCH', REQUESTS: 'REQUESTS' };", "const groups = { TS: 'TS-IMPLICIT' };")
    source = source.replace("const arms = ['RAW', 'COMPACT', 'STRUCTURED'];", "const arms = ['RAW', 'PLAN'];")
    source = source.replace("const permittedArms = { PATCH: ['STRUCTURED'], REQUESTS: arms };", "const permittedArms = { TS: arms };")
    source = source.replace('batch.callsBefore !== 403', 'batch.callsBefore !== 415').replace('batch.maximumNewCalls !== 12', 'batch.maximumNewCalls !== 13')
    source = source.replace('batch.purposeMaximums?.EXECUTION !== 4', 'batch.purposeMaximums?.EXECUTION !== 2')
    source = source.replace('protocol.maximumActualModelCalls !== 425', 'protocol.maximumActualModelCalls !== 438').replace('candidateValidation !== 345', 'candidateValidation !== 358')
    source = source.replace('live-cases.json', 'request-cases.json').replace("for (const group of ['REQUESTS'])", "for (const group of ['TS'])")
    start = source.index('async function verifiedCopy(')
    end = source.index('async function approvedBatch()', start)
    source = source[:start] + '''async function verifiedCopy() {
  const directory = resolve(work, 'flow/TS-IMPLICIT');
  const platform = await json(resolve(directory, 'platform.private.json'));
  const copy = await readFile(resolve(directory, 'native-copy.md'), 'utf8');
  const check = await json(resolve(directory, 'check.json'));
  if (platform.status !== 200 || platform.body.data.provider.mock !== false || !check.success
      || !check.nativeCopyMatches || !check.historyMatches || hash(copy) !== check.copySha256
      || newline(copy) !== newline(platform.body.data.optimizedPrompt)) throw Error('Verified unchanged native copy required');
  return copy;
}

/** Raw receives only verbatim actual submitted answers; Plan is executed exactly as copied. */
async function prepareWorks() {
  const caseBytes = await readFile(resolve(pub, 'request-cases.json')); const cases = JSON.parse(caseBytes.toString('utf8'));
  const sample = cases.find(row => row.id === 'TS-IMPLICIT'); const executionSample = { ...sample, context: { files: [] } };
  const confirmation = await json(resolve(work, 'flow/TS-IMPLICIT/actual-confirmation.private.json'));
  const submitted = await json(resolve(work, 'flow/TS-IMPLICIT/enhancement-request.private.json'));
  if (!equal(confirmation, submitted.planConfirmation)) throw Error('Actual confirmation mismatch');
  const copy = await verifiedCopy();
  const inputs = { RAW: rawExecutionInput(executionSample, confirmation.answers), PLAN: enhancedExecutionInput(copy, executionSample) };
  if (inputs.PLAN !== copy) throw Error('Plan body patched by execution helper');
  const source = await readFile(resolve(pub, 'candidate40-source.json'));
  const lock = { sourceCandidate: 'candidate40', sourceManifestSha256: hash(source), casesSha256: hash(caseBytes),
    model: 'deepseek-flash', parameters, actualConfirmations: confirmation.answers, systemTaskInstructionAdded: false,
    sharedMaterials: [], groups: { TS: { caseId: sample.id, nativeCopyHashes: { PLAN: hash(copy) }, arms: {} } } };
  for (const arm of arms) {
    const request = { model: 'deepseek-flash', ...parameters, messages: [{ role: 'user', content: inputs[arm] }] };
    await save(resolve(work, 'works/TS', arm, 'request.json'), request);
    lock.groups.TS.arms[arm] = { requestSha256: hash(JSON.stringify(request)), promptSha256: hash(inputs[arm]) };
  }
  await save(resolve(work, 'works/input-lock.json'), lock);
  console.log('TWO_SAME_INFORMATION_WORKS_FROZEN; no model call');
}

''' + source[end:]
    start = source.index('async function verifyWork(')
    end = source.index('async function verifyReview(', start)
    source = source[:start] + '''async function verifyWork(group, arm, request) {
  const lock = await json(resolve(work, 'works/input-lock.json'));
  const cases = await json(resolve(pub, 'request-cases.json'));
  const sample = cases.find(row => row.id === 'TS-IMPLICIT');
  const confirmation = await json(resolve(work, 'flow/TS-IMPLICIT/actual-confirmation.private.json'));
  const manifest = await json(resolve(pub, 'candidate40-source.json'));
  if (lock.sourceCandidate !== 'candidate40' || lock.model !== 'deepseek-flash'
      || lock.casesSha256 !== hash(await readFile(resolve(pub, 'request-cases.json')))
      || lock.sourceManifestSha256 !== hash(await readFile(resolve(pub, 'candidate40-source.json')))
      || !equal(lock.actualConfirmations, confirmation.answers)) throw Error('Work scope or actual answer drift');
  for (const row of manifest.sourceFiles) if (hash(await readFile(resolve(row.path))) !== row.sha256) throw Error('Source drift');
  const copy = await verifiedCopy();
  const expected = arm === 'RAW' ? rawExecutionInput({ ...sample, context: { files: [] } }, confirmation.answers) : copy;
  if (group !== 'TS' || !arms.includes(arm) || request.model !== 'deepseek-flash'
      || request.messages?.length !== 1 || request.messages[0].role !== 'user' || request.messages[0].content !== expected
      || hash(copy) !== lock.groups.TS.nativeCopyHashes.PLAN
      || hash(JSON.stringify(request)) !== lock.groups.TS.arms[arm].requestSha256) throw Error('Work input was changed');
}

''' + source[end:]
    with (PUBLIC / 'external-calls.mjs').open('x', encoding='utf-8') as output:
        output.write(source)


if __name__ == '__main__':
    main()
