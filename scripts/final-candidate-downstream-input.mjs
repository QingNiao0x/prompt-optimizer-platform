/**
 * 将冻结的真实增强证据转换为实际作品对照输入。只接收合成材料，不补造失败的提示词或确认答案。
 * 输出只能新建于 tmp；评测评分卡、登录信息和未选择选项不进入执行器。
 */
import { readFile, writeFile, realpath, stat } from 'node:fs/promises';
import { resolve, relative, isAbsolute, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { safeMaterialPath } from './prompt-output-evaluation.mjs';

const root = fileURLToPath(new URL('../', import.meta.url));
const args = process.argv.slice(2);
if (args.includes('--help')) {
  console.log('node scripts/final-candidate-downstream-input.mjs --comparison-dir <run> --model <published optimizer ID> --output <new tmp file> [--cases <IDs>]');
  process.exit(0);
}
const arg = name => { const i = args.indexOf(name); if (i < 0 || !args[i + 1]) throw new Error(`Missing ${name}`); return args[i + 1]; };
const source = await realpath(resolve(arg('--comparison-dir')));
const target = resolve(arg('--output'));
for (const path of [source, target]) {
  const rel = relative(root, path);
  if (!rel.startsWith('tmp/') && !rel.startsWith('tmp\\')) throw new Error('WORKSPACE_TMP_REQUIRED');
  if (isAbsolute(rel) || rel.startsWith('..')) throw new Error('PATH_ESCAPE');
  safeMaterialPath(rel);
}
const parent = await realpath(resolve(target, '..'));
if (!parent.startsWith((await realpath(resolve(root, 'tmp'))) + '/')
  && !parent.startsWith((await realpath(resolve(root, 'tmp'))) + '\\')) throw new Error('OUTPUT_LINK_ESCAPE');
const parse = async name => {
  if (!/^[A-Za-z0-9_-]+\.json$/.test(name)) throw new Error('INVALID_EVIDENCE_FILENAME');
  const actual = await realpath(resolve(source, name));
  if (!actual.startsWith(source + sep) || (await stat(actual)).size > 8_000_000) throw new Error('EVIDENCE_LINK_ESCAPE_OR_SIZE');
  return JSON.parse(await readFile(actual, 'utf8'));
};
const manifest = await parse('manifest.json');
if (manifest.dataAuthorization !== 'SYNTHETIC' || manifest.kind !== 'REAL_AUTHENTICATED_API') throw new Error('REAL_SYNTHETIC_EVIDENCE_REQUIRED');
const casesText = await readFile(resolve(source, 'cases.json'), 'utf8');
if (createHash('sha256').update(casesText).digest('hex') !== manifest.caseSha256) throw new Error('FROZEN_INPUT_CHANGED');
const model = arg('--model');
if (!manifest.plannedPublishedModels.includes(model)) throw new Error('OPTIMIZER_NOT_IN_FROZEN_CATALOG');
const selected = args.includes('--cases') ? arg('--cases').split(',') : null;
const cases = JSON.parse(casesText).filter(item => !selected || selected.includes(item.id));
if (!cases.length || (selected && cases.length !== new Set(selected).size)) throw new Error('INVALID_CASE_SELECTION');
const modelSlug = model.replace(/[^a-zA-Z0-9_-]/g, '_');
const readEvidence = async (caseId, suffix) => {
  try { return await parse(`${caseId}-${modelSlug}-${suffix}.json`); }
  catch (error) { if (error.code === 'ENOENT') return null; throw error; }
};
const usable = (entry, item) => {
  if (!entry) return false;
  if (entry.caseId !== item.id || entry.modelId !== model) throw new Error('EVIDENCE_BINDING_MISMATCH');
  return entry.status === 'COLLECTED' && entry.result?.provider?.mock === false
    && entry.result.provider.model === model && !!entry.result.optimizedPrompt?.trim();
};
const normalized = [];
for (const item of cases) {
  const direct = await readEvidence(item.id, 'direct');
  const plan = await readEvidence(item.id, 'plan-final');
  const directOk = usable(direct, item), planOk = usable(plan, item);
  if (planOk && (!plan.plan?.planId || !Array.isArray(plan.answers)
    || plan.plan.questions.length !== plan.answers.length
    || plan.answers.some(answer => !plan.plan.questions.some(question => question.id === answer.questionId && question.question === answer.question)))) {
    throw new Error('BOUND_ACTUAL_PLAN_ANSWERS_REQUIRED');
  }
  normalized.push({ id: item.id, domain: item.domain, rawPrompt: item.rawPrompt,
    contextDescription: item.contextDescription, files: item.files.map(({ path, content }) => ({ path, content })),
    optimizerModelId: model, direct: directOk ? { prompt: direct.result.optimizedPrompt } : null,
    plan: planOk ? { prompt: plan.result.optimizedPrompt, serverAccepted: true } : null,
    submittedAnswers: planOk ? plan.answers.map(answer => ({ ...answer, actuallySubmitted: true })) : [],
    generationFailures: [['direct', directOk, direct], ['plan', planOk, plan]].filter(([, ok]) => !ok)
      .map(([arm, , entry]) => ({ arm, status: entry ? 'FAILED' : 'NOT_COLLECTED', code: entry?.failure?.code ?? null })),
  });
}
await writeFile(target, JSON.stringify({ schemaVersion: 1, dataAuthorization: 'SYNTHETIC',
  origin: 'FINAL_CANDIDATE_REAL_API', sourceManifest: relative(root, resolve(source, 'manifest.json')),
  sourceCandidateHashes: manifest.sourceHashes, cases: normalized }, null, 2) + '\n', { flag: 'wx', encoding: 'utf8' });
console.log(JSON.stringify({ output: target, cases: normalized.length,
  acceptedPlan: normalized.filter(item => item.plan).length, acceptedDirect: normalized.filter(item => item.direct).length,
  generationFailures: normalized.flatMap(item => item.generationFailures).length, paidCalls: 0 }));
