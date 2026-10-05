/**
 * 为实际作品创建匿名 AI 辅助评审任务，再校验并导入评审结果。
 * 复用现有有预算的执行器；评审与用户任务的调用次数分开，不冒充独立人工或专业评审。
 */
import { readFile, writeFile, realpath } from 'node:fs/promises';
import { resolve, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { safeMaterialPath, reviewScore } from './prompt-output-evaluation.mjs';

const root = fileURLToPath(new URL('../', import.meta.url));
const args = process.argv.slice(2);
if (args.includes('--help')) {
  console.log('prepare --run <blinded real output run> --output <new tmp input>\nimport --run <original run> --review-run <AI review execution run> --output <new tmp reviews>');
  process.exit(0);
}
const value = name => { const i = args.indexOf(name); if (i < 0 || !args[i + 1]) throw new Error(`Missing ${name}`); return args[i + 1]; };
const assert = (ok, message) => { if (!ok) throw new Error(message); };
const hash = text => createHash('sha256').update(text).digest('hex');
const owned = async path => {
  const actual = await realpath(resolve(path));
  const rel = relative(root, actual); safeMaterialPath(rel);
  assert(rel.startsWith('tmp/') || rel.startsWith('tmp\\'), 'TMP_REQUIRED');
  return actual;
};
const read = async path => JSON.parse(await readFile(await owned(path), 'utf8'));
const run = await owned(value('--run'));
const output = resolve(value('--output'));
const parent = await owned(resolve(output, '..'));
safeMaterialPath(relative(root, output));
assert(parent === resolve(output, '..'), 'OUTPUT_LINK_ESCAPE');
const batch = await read(resolve(run, 'jobs.json'));
const manifest = await read(resolve(run, 'manifest.json'));
assert(hash(await readFile(resolve(run, 'jobs.json'), 'utf8')) === manifest.jobsSha256, 'JOBS_CHANGED');
const mapping = await read(resolve(run, 'blind-mapping.json'));
const template = await read(resolve(run, 'review-template.json'));

if (args[0] === 'prepare') {
  const groups = new Map();
  for (const item of mapping) {
    const job = batch.jobs.find(job => job.id === item.jobId);
    assert(job, 'UNKNOWN_BLIND_JOB');
    const text = await readFile(await owned(resolve(run, 'blind', item.blindId + '-work.md')), 'utf8');
    assert(hash(text) === item.outputSha256, 'ACTUAL_WORK_CHANGED');
    // 未获得回答的作品单独评审，防止模型看到邻近作品的答案后误罚原始信息组。
    const groupId = job.caseId + '-m' + batch.executionModels.findIndex(model => model.id === job.modelId)
      + '-' + job.informationTrack;
    const group = groups.get(groupId) ?? [];
    group.push({ blindId: item.blindId, text, task: await read(resolve(run, 'blind', item.blindId + '-task.json')) });
    groups.set(groupId, group);
  }
  const cases = [...groups].map(([id, items]) => ({ id, domain: '匿名实际作品评审', optimizerModelId: null,
    rawPrompt: '请评审附件中的匿名实际任务成果，不评价或重写提示词。每份作品按它自己的 task.json 中原始任务、相同资料及实际答案评审。本组作品获得的信息一致：submittedAnswers为空代表这些决定尚未提供，不能因为其他批次或自己推测就按已确认要求评价。不能预设某一版更好，不因篇幅长或表格多加分。\n'
      + 'accuracy、completeness、ruleFidelity、usability 四项分别用 0-4 整数：0无法使用，1严重不足，2部分达到，3达到主要要求，4完整达到。usability同时考虑结构清楚和可执行性，冗长重复扣分；未知不得写成缺失功能或已定参数。明确规则反转、伪造事实/法律/专业结论、漏掉执行关键前提、交付范围越界记入criticalErrors，形式小问题不要升级为严重错误。新闻要求600-800汉字时可检查长度，教学需检查算式，不要求和理想答案逐字相同。医学/法律/金融结论只按材料检查，未核实的专业有效性不可记为认证通过。\n'
      + '只返回JSON对象{"entries":[{"blindId":"B001","status":"REVIEWED","scores":{"accuracy":3,"completeness":3,"ruleFidelity":3,"usability":3},"criticalErrors":[],"evidence":["该作品中一段连续的原文"],"notes":"简要依据"}]}。必须覆盖全部匿名ID。evidence只需一段8-30字的连续原文，必须来自当前ID的work.md，逐字复制且保留Markdown符号，不引用task.json，不把意思相同的改写作为引文，不跨段拼接。其他评分依据在notes解释，不需要增加长引文。不能确定的整体任务口径用status=INPUT_AMBIGUITY并说明，不强行打分。',
    contextDescription: '本任务评审真实生成的合成业务作品；AI辅助分数有主观性，不替代人工专业审核。',
    files: items.flatMap(item => [{ path: `artifacts/${item.blindId}-task.json`, content: JSON.stringify(item.task, null, 2) },
      { path: `artifacts/${item.blindId}-work.md`, content: item.text },
      { path: `artifacts/${item.blindId}-literal-evidence.json`, content: JSON.stringify({
        note: '以下短片段均逐字提取自当前作品，只用于验证引文；是否满足任务必须独立阅读整份作品。',
        quotes: [...new Set(item.text.split(/\r?\n/).filter(line => line.trim().length >= 8)
          .slice(0, 30).map(line => line.trim().slice(0, 24)))],
        fullWorkCharactersWithoutWhitespace: item.text.replace(/\s/g, '').length,
        bodyWithoutHeadingsCharacters: item.text.split(/\r?\n/).filter(line => !/^\s*#/.test(line))
          .join('').replace(/\s/g, '').length,
        bodyHanCharacters: (item.text.split(/\r?\n/).filter(line => !/^\s*#/.test(line))
          .join('').match(/\p{Script=Han}/gu) ?? []).length,
      }) }]), submittedAnswers: [], generationFailures: [],
  }));
  assert(cases.length > 0 && cases.length <= 24, 'INVALID_REVIEW_GROUP_COUNT');
  await writeFile(output, JSON.stringify({ schemaVersion: 1, dataAuthorization: 'SYNTHETIC', origin: 'BLINDED_AI_WORK_REVIEW',
    sourceRun: relative(root, run), professionalReview: 'NOT_PERFORMED', cases }, null, 2) + '\n', { flag: 'wx' });
  console.log(JSON.stringify({ output, reviewTasks: cases.length, actualArtifacts: mapping.length, paidCalls: 0 }));
} else if (args[0] === 'import') {
  const reviewRun = await owned(value('--review-run'));
  const reviewBatch = await read(resolve(reviewRun, 'jobs.json'));
  const reviewManifest = await read(resolve(reviewRun, 'manifest.json'));
  assert(hash(await readFile(resolve(reviewRun, 'jobs.json'), 'utf8')) === reviewManifest.jobsSha256, 'REVIEW_JOBS_CHANGED');
  const reviewInput = await read(resolve(reviewRun, 'input.json'));
  assert(reviewInput.sourceRun === relative(root, run), 'REVIEW_SOURCE_RUN_MISMATCH');
  const byId = new Map(); const problems = [];
  for (const job of reviewBatch.jobs) {
    const result = await read(resolve(reviewRun, 'execution', job.id + '.json'));
    if (result.status !== 'SUCCESS') { problems.push({ jobId: job.id, reason: result.status }); continue; }
    assert(hash(result.output) === result.outputSha256 && result.executionUserSha256 === job.userHash, 'REVIEW_RESULT_CHANGED');
    try {
      const json = JSON.parse(result.output.trim().replace(/^```(?:json)?\s*|\s*```$/g, ''));
      assert(Array.isArray(json.entries), 'INVALID_REVIEW_ENTRIES');
      const expectedIds = reviewInput.cases.find(item => item.id === job.caseId)?.files
        .filter(file => /\/B\d+-work\.md$/.test(file.path)).map(file => file.path.match(/\/(B\d+)-work\.md$/)[1]);
      assert(expectedIds && json.entries.length === expectedIds.length
        && new Set(json.entries.map(item => item.blindId)).size === expectedIds.length
        && json.entries.every(item => expectedIds.includes(item.blindId)), 'INCOMPLETE_OR_CROSS_GROUP_REVIEW');
      // 一组作品全部校验通过后才导入，避免失败组的前几条悄悄成为有效分数。
      const validated = [];
      for (const item of json.entries) {
        const expected = template.entries.find(entry => entry.blindId === item.blindId);
        assert(expected && !byId.has(item.blindId), 'UNKNOWN_OR_DUPLICATE_REVIEW_ID');
        const work = await readFile(await owned(resolve(run, 'blind', item.blindId + '-work.md')), 'utf8');
        assert(hash(work) === expected.outputSha256, 'BLIND_WORK_CHANGED');
        const entry = { ...item, outputSha256: expected.outputSha256 };
        reviewScore(entry);
        if (item.status === 'REVIEWED') assert(item.evidence.every(quote => work.includes(quote)), 'UNVERIFIED_EVIDENCE_QUOTE');
        validated.push(entry);
      }
      for (const entry of validated) byId.set(entry.blindId, entry);
    } catch (error) { problems.push({ jobId: job.id, reason: error.message }); }
  }
  const entries = template.entries.map(entry => byId.get(entry.blindId) ?? entry);
  await writeFile(output, JSON.stringify({ schemaVersion: 1,
    reviewer: { id: 'blinded-model-' + reviewBatch.executionModels.map(model => model.id).join(','), kind: 'AI_ASSISTED',
      reviewedAt: new Date().toISOString(), professionalDomain: 'NOT_CERTIFIED', reviewRun: relative(root, reviewRun) },
    professionalReview: 'NOT_PERFORMED', problems, entries }, null, 2) + '\n', { flag: 'wx' });
  console.log(JSON.stringify({ output, reviewed: byId.size, pending: entries.length - byId.size, problems }));
} else throw new Error('INVALID_ACTION');
