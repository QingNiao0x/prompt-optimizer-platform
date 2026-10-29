import assert from 'node:assert/strict';
import { createHash, randomBytes } from 'node:crypto';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url)), root = resolve(here, '..');
const stage = process.argv[2];
assert.match(stage ?? '', /^[a-z0-9-]+$/);
const json = async path => JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''));
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const closure = await json(resolve(root, stage, 'closure.json'));
assert.equal(closure.status, 'CLOSED');
assert.equal(closure.entryLockedByClosure, true);
const jobs = await json(resolve(root, stage, 'jobs.json'));
assert.equal(jobs.length, 6);
assert.ok(jobs.every(job => job.purpose === 'WRITER_THINKING_ABLATION'));
// 不依靠进程退出码或HTTP成功当交付。先验证闭批结果/trace原件，再原样展示所有正式样本。
for (const entry of closure.files) {
  let file = resolve(root, '../../../..', entry.path);
  if (!file.startsWith(resolve(root, stage) + '\\') && !file.startsWith(resolve(root, stage) + '/')) file = resolve(root, stage, entry.path);
  assert.ok(file.startsWith(resolve(root, stage) + '\\') || file.startsWith(resolve(root, stage) + '/'), '闭批路径超出本阶段');
  assert.equal(sha(await readFile(file)), entry.sha256, '闭批原件哈希不符');
}
const output = resolve(here, 'anonymous', stage), keyDir = resolve(here, 'coordinator');
await mkdir(output, { recursive: true }); await mkdir(keyDir, { recursive: true });
const map = [], packetHashes = [];
for (const caseId of ['Q01', 'U02', 'U03']) {
  const pair = jobs.filter(job => job.caseId === caseId);
  assert.equal(pair.length, 2);
  assert.deepEqual(pair[0].approvedModelRequest.messages, pair[1].approvedModelRequest.messages);
  const user = pair[0].approvedModelRequest.messages.find(message => message.role === 'user').content;
  const input = JSON.parse(user.slice(user.indexOf('{')));
  const task = Object.fromEntries(['rawPrompt', 'context', 'actualAnswers', 'conversation', 'mandatoryRules', 'planningContext']
    .filter(key => Object.hasOwn(input, key)).map(key => [key, input[key]]));
  if (randomBytes(1)[0] % 2 === 1) pair.reverse();
  const works = [];
  for (const [index, job] of pair.entries()) {
    const label = index ? 'B' : 'A';
    const result = await json(resolve(root, stage, 'results', job.id + '.json'));
    const content = result.body?.data?.content, finishReason = result.body?.data?.finishReason;
    let optimizedPrompt = null;
    try {
      const parsed = JSON.parse(content);
      if (typeof parsed.optimizedPrompt === 'string') optimizedPrompt = parsed.optimizedPrompt;
    } catch { /* 原件保留为无可抽取正文；不修JSON或替换正式样本。 */ }
    works.push({ label, httpStatus: result.status, finishReason,
      extractionState: optimizedPrompt === null ? 'NO_EXTRACTABLE_BODY' : 'BODY_EXTRACTED_NOT_PRODUCT_VALIDATED',
      optimizedPrompt, rawResponseWhenNoBody: optimizedPrompt === null ? content ?? null : undefined });
    map.push({ caseId, label, jobId: job.id, thinkingArm: job.thinkingArm,
      requestSha256: sha(JSON.stringify(job.approvedModelRequest)), resultContentSha256: typeof content === 'string' ? sha(content) : null });
  }
  const packet = { caseId, scope: '已见开发题的内部匿名正文对照，不是未见题或实际作品评测',
    task, works, criteria: ['保留原目标、事实状态、全部独立条件及真正委托范围',
      '补充具体处理内容和可执行要求，直接帮助原定交付；篇幅、重排或同义改写不计收益',
      '资料尚未随输入传入不等于现实不存在；不制造问卷或填空模板',
      '合理设计变体允许；指出最小必要删改，不依赖模型自报或内部检查表'],
    boundary: '只审正文语义。产品原校验另外运行，BODY_EXTRACTED不代表来源/安全/覆盖或采用通过。隐藏臂身份、用量、思考、内部自评。' };
  const bytes = JSON.stringify(packet, null, 2) + '\n';
  await writeFile(resolve(output, caseId + '.json'), bytes, { flag: 'wx' });
  packetHashes.push({ file: caseId + '.json', sha256: sha(bytes) });
}
await writeFile(resolve(keyDir, stage + '-map.json'), JSON.stringify({ stage, map }, null, 2) + '\n', { flag: 'wx' });
await writeFile(resolve(output, 'manifest.json'), JSON.stringify({ stage, packetHashes, modeIdentitiesHidden: true,
  sourceClosureSha256: sha(await readFile(resolve(root, stage, 'closure.json'))), actualNewModelCalls: 0 }, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify({ output, packets: 3, works: 6, actualNewModelCalls: 0 }));
