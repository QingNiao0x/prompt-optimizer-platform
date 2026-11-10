/** 同信息三种使用方式及候选对照。先落盘计数，失败保留，目录封存后拒绝重跑。 */
import assert from 'node:assert/strict';
import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';
import { createHash } from 'node:crypto';
import { createServer } from 'node:http';
import { spawn } from 'node:child_process';

const root = process.cwd();
const directory = resolve(process.argv[2]);
mkdirSync(directory, { recursive: true });
const read = path => JSON.parse(readFileSync(path, 'utf8').replace(/^\uFEFF/, ''));
const save = (name, value) => writeFileSync(resolve(directory, name), JSON.stringify(value, null, 2), { flag: 'wx' });
assert(!existsSync(resolve(directory, 'protocol.json')), '已冻结目录不能重新执行');
const cases = read(resolve(root, 'docs/testing/evidence/natural-protocol-scope-2026-11-09/cases.json'));
save('cases.json', cases);
save('protocol.json', { maximum: 100, endpoint: 'https://api.deepseek.com/chat/completions',
  model: 'deepseek-flash', temperature: 0.2, thinking: 'disabled', qualityRetries: 0,
  scope: '已授权合成案例，Provider增强及同信息作品。没有真实业务源码。',
  arms: ['raw', 'simple', 'core-repair-v5', 'core-balanced-v1'],
  works: ['W_CODE', 'R_PLAN', 'R_REPORT'], repetitions: 2,
  acceptance: '工程故障与正文收益分开。来源/题答/覆盖不能丢失。作品无新增严重错误且必要修改减少才采用正文策略。充分需求持平可接受。内部审阅不冒充真人评阅。',
  sources: ['NaturalPromptBalancedInstructions.java', 'NaturalPromptProtocolRepairInstructions.java'].map(name => ({ name,
    sha256: createHash('sha256').update(readFileSync(resolve(root, 'services/api/src/main/java/com/promptoptimizer/provider/domain', name))).digest('hex') })) });
let count = 0;
let pending = false;
async function invoke(payload) {
  assert(!pending && count < 100);
  pending = true;
  const number = ++count;
  const request = { ...payload, model: 'deepseek-flash', temperature: 0.2, thinking: { type: 'disabled' } };
  const path = resolve(directory, `call-${String(number).padStart(3, '0')}.json`);
  const trace = { number, request, startedAt: new Date().toISOString() };
  writeFileSync(path, JSON.stringify(trace, null, 2), { flag: 'wx' });
  try {
    const response = await fetch('https://api.deepseek.com/chat/completions', { method: 'POST',
      headers: { 'content-type': 'application/json', authorization: `Bearer ${process.env.MODEL_API_KEY}` },
      body: JSON.stringify(request), redirect: 'error', signal: AbortSignal.timeout(120000) });
    const body = await response.json();
    for (const choice of body.choices ?? []) delete choice.message?.reasoning_content;
    Object.assign(trace, { status: response.status, response: body, finishedAt: new Date().toISOString() });
    writeFileSync(path, JSON.stringify(trace, null, 2));
    return { status: response.status, body };
  } catch (error) {
    trace.failure = error.name;
    writeFileSync(path, JSON.stringify(trace, null, 2));
    throw error;
  } finally { pending = false; }
}
const server = createServer(async (request, response) => {
  try {
    const chunks = [];
    for await (const chunk of request) chunks.push(chunk);
    const result = await invoke(JSON.parse(Buffer.concat(chunks).toString('utf8')));
    response.writeHead(result.status, { 'content-type': 'application/json' });
    response.end(JSON.stringify(result.body));
  } catch {
    response.writeHead(502); response.end('{"error":{"message":"evaluation failed"}}');
  }
});
await new Promise(resolve => server.listen(9079, '127.0.0.1', resolve));
const messages = (system, user) => ({ max_tokens: 10000, messages: [{ role: 'system', content: system }, { role: 'user', content: user }] });
try {
  const dependencies = readFileSync(resolve(root, 'tmp/backend-local-managed/2026-11-06T18-01-49-217Z-0b294b71/runtime-classpath.txt'), 'utf8').trim();
  const child = spawn('E:/Delelop/Java/jdk-21/bin/java.exe', ['-Xmx512m',
    '-Djdk.net.unixdomain.tmpdir=' + resolve(root, 'tmp/backend-local-managed/nio'), '--class-path',
    resolve(root, 'services/api/target/classes') + ';' + dependencies, '--source', '21',
    resolve(root, 'scripts/evaluation/natural-balanced/BalancedPaths.java'), directory], { stdio: 'inherit' });
  const code = await new Promise(resolve => child.on('exit', resolve));
  assert.equal(code, 0);
  for (const sample of cases) {
    const result = await invoke(messages('将需求完善成清楚、自然、可执行的提示词。结合给定材料，保留用户意图、明确要求和真实回答，补充有用内容。只返回优化提示词。', JSON.stringify(sample.input)));
    save(`${sample.id}_simple.json`, result);
  }
  for (const sample of cases.filter(sample => ['W_CODE', 'R_PLAN', 'R_REPORT'].includes(sample.id))) {
    const prompts = { raw: sample.input.rawPrompt, simple: read(resolve(directory, `${sample.id}_simple.json`)).body?.choices?.[0]?.message?.content };
    for (const version of ['core-repair-v5', 'core-balanced-v1']) {
      const result = read(resolve(directory, `${sample.id}_${version}.json`));
      if (result.success) prompts[version] = result.body;
    }
    for (let round = 1; round <= 2; round++) {
      const arms = Object.entries(prompts);
      if (round === 2) arms.reverse();
      for (const [arm, prompt] of arms) {
        if (!prompt) continue;
        const input = { prompt, context: sample.input.context, planningContext: sample.input.planningContext, actualAnswers: sample.input.actualAnswers };
        save(`${sample.id}_${arm}_work${round}.json`, await invoke(messages('执行用户任务，所有对照获得相同资料及实际题答。保留原交付范围和限制。', JSON.stringify(input))));
      }
    }
  }
} finally {
  save('closure.json', { actualCalls: count, maximum: 100, closedAt: new Date().toISOString() });
  server.close();
}
