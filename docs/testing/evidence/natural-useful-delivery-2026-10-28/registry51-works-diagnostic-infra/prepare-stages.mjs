import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
const here = dirname(fileURLToPath(import.meta.url)), evidence = resolve(here, '..'), repo = resolve(evidence, '../../../..');
const hash = value => createHash('sha256').update(value).digest('hex');
const json = async path => JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''));
const save = (file, value) => writeFile(resolve(here, file), JSON.stringify(value, null, 2) + '\n', { flag: 'wx' });
const prepared = resolve(evidence, 'registry51-works-preparation');
const contentBytes = await readFile(resolve(prepared, 'bundle.json')); assert.equal(hash(contentBytes), '414c1ac7e620776c5cb46f194e09e07514105451c7937c11e0d06e16e6c24b6c');
const content = JSON.parse(contentBytes), code = await json(resolve(prepared, 'code-bundle.json'));
const proofs = await Promise.all(['reuse-provenance.json', 'code-reuse-provenance.json'].map(file => json(resolve(prepared, file))));
const refs = new Map();
function collect(value) {
  if (!value || typeof value !== 'object') return;
  if (typeof value.path === 'string' && /^[a-f0-9]{64}$/.test(value.sha256 ?? '')) refs.set(value.path, value.sha256);
  for (const child of Object.values(value)) if (child && typeof child === 'object') collect(child);
}
proofs.forEach(collect);
for (const [path, expected] of refs) {
  assert.ok(!path.split(/[\\/]/).some(part => part.startsWith('sealed')));
  assert.equal(hash(await readFile(resolve(repo, path))), expected, 'Prepared source drift: ' + path);
}
const promptRefs = [];
for (const [id, stage, version] of [['Q01','registry51-development','51'],['U02','registry51-development','51'],['U03','registry51-development','51'],
  ['U02','completion47','31'],['U03','completion47','31'],['C01','registry51-normal','51'],['C02','registry51-normal','51']]) {
  const file = `${stage}/results/${id}_V${version}_GEN.json`, closure = await json(resolve(evidence, stage, 'closure.json'));
  assert.equal(closure.status, 'CLOSED'); const bytes = await readFile(resolve(evidence, file));
  const declared = closure.files.find(item => item.path.replaceAll('\\','/').endsWith('/' + file)); assert.equal(hash(bytes), declared?.sha256);
  const result = JSON.parse(bytes); assert.equal(result.status, 200);
  const prompt = result.body.data.optimizedPrompt;
  const jobs = [...content.jobs, ...code.jobs].filter(job => job.caseId === id && job.version === version);
  assert.ok(jobs.length === 2); for (const job of jobs) {
    if (id.startsWith('C')) assert.ok(job.approvedModelRequest.messages[1].content.startsWith(prompt));
    else assert.equal(job.approvedModelRequest.messages[1].content, prompt);
  }
  promptRefs.push({ caseId: id, version, result: file, sha256: hash(bytes), promptSha256: hash(prompt), actualBodyStage: result.body.data.processing.completedStages.includes('BODY_GENERATION') });
}
for (const source of [content, code]) for (const sample of source.cases) {
  const jobs = source.jobs.filter(job => job.caseId === sample.id);
  const stripped = jobs.map(job => { const request = structuredClone(job.approvedModelRequest); request.messages[1].content = '__PROMPT__'; return request; });
  for (const request of stripped) assert.deepEqual(request, stripped[0], 'Within-case executor profile/materials differ');
  for (const job of jobs) {
    const p = job.approvedModelRequest; assert.equal(p.model, 'deepseek-flash'); assert.equal(p.temperature, .2); assert.equal(p.stream, false);
    assert.deepEqual(p.thinking, { type: 'disabled' });
    const users = p.messages.filter(message => message.role === 'user').map(message => message.content).join('\n');
    for (const material of sample.executorMaterials ?? []) assert.ok(users.includes(material.content));
    if (job.version === 'ORIGINAL') assert.equal(p.messages[1].content, sample.rawPrompt);
  }
}
const definitions = [['registry51-query-works', content, ['Q01'], 8192, 2], ['registry51-content-works', content, ['U02','U03'],4500,12], ['registry51-code-works',code,['C01','C02'],8192,4]];
const bundles = [];
for (const [stage, source, ids, cap, count] of definitions) {
  const jobs = source.jobs.filter(job => ids.includes(job.caseId)); assert.equal(jobs.length, count);
  for (const job of jobs) assert.equal(job.approvedModelRequest.max_tokens, cap);
  const bundle = { syntheticOnly:true, status:'ROOT_AUTHORIZED_COMPONENT_DIAGNOSTIC_NOT_CANDIDATE_ADOPTION',
    purpose:'Non-Plan shared writer component works diagnosis; original Plan failures and adoption gates unchanged.',
    parameters:{executionMaxTokens:cap},phaseLimits:{GENERATION:0,PLAN:0,EXECUTION:count,REVIEW:0},
    cases:source.cases.filter(sample=>ids.includes(sample.id)),jobs,
    reusedWorkResults:(source.reusedWorkResults??[]).filter(item=>ids.includes(item.caseId)),
    diagnosticDecision:'registry51-works-diagnostic-decision.md', oldAllProductGateConditionNotRewritten:true,
    normalAndDevelopmentSemanticEligibilityDoesNotMeanAllCandidateGatesPass:true, qualityRetriesAllowed:false };
  await save(stage+'-bundle.json',bundle); bundles.push({stage,jobs:count,maxTokens:cap,sha256:hash(await readFile(resolve(here,stage+'-bundle.json')))});
}
await save('preflight.json',{status:'DIAGNOSTIC_ONLY_SOURCE_AND_REQUEST_CHECKS_PASS',actualModelCalls:0,taskActual:179,
  sourceReferencesVerified:refs.size,lockedPromptReferences:promptRefs, noGenerationAdded:true, oldDocumentsNotRewritten:true,
  candidateAdoptionClaimed:false, sourceAndTargetMustRemainUnchangedUntilAll18Close:true,bundles});
console.log(JSON.stringify({actualModelCalls:0,sourceReferencesVerified:refs.size,prompts:promptRefs.length,bundles}));
