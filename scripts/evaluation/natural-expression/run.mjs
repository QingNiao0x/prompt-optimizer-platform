/** 冻结需求的分阶段真实对照，所有入口共用先落盘计数的官方端点转发。 */
import assert from 'node:assert/strict';
import { readFileSync, writeFileSync, mkdirSync, existsSync, readdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { createHash } from 'node:crypto';
import { createServer } from 'node:http';
import { spawn } from 'node:child_process';

const root = process.cwd();
const directory = resolve(process.argv[2]);
const phase = process.argv[3];
const read = path => JSON.parse(readFileSync(path, 'utf8').replace(/^\uFEFF/, ''));
const protocol = read(resolve(directory, 'protocol.json'));
assert(Object.hasOwn(protocol.phaseLimits, phase));
assert(!existsSync(resolve(directory, 'closure.json')), '本批已关闭');
for (const file of protocol.files) {
  assert.equal(createHash('sha256').update(readFileSync(resolve(root, file.path))).digest('hex'), file.sha256, file.path);
}
const save = (name, data) => writeFileSync(resolve(directory, name), JSON.stringify(data, null, 2) + '\n', { flag: 'wx' });
const traces = resolve(directory, 'traces');
mkdirSync(traces, { recursive: true });
let pending = false;
async function invoke(payload) {
  assert(!pending);
  const prior = readdirSync(traces).filter(name => /^\d{3}\.json$/.test(name)).map(name => read(resolve(traces, name)));
  assert(prior.length < protocol.maximum && prior.filter(item => item.phase === phase).length < protocol.phaseLimits[phase], '调用上限');
  pending = true;
  const number = prior.length + 1;
  const path = resolve(traces, `${String(number).padStart(3, '0')}.json`);
  const request = { ...payload, model: protocol.model, temperature: protocol.temperature, thinking: { type: protocol.thinking } };
  const trace = { number, phase, request, startedAt: new Date().toISOString() };
  writeFileSync(path, JSON.stringify(trace, null, 2), { flag: 'wx' });
  try {
    const response = await fetch(protocol.endpoint, { method: 'POST', redirect: 'error', signal: AbortSignal.timeout(120000),
      headers: { 'content-type': 'application/json', authorization: `Bearer ${process.env.MODEL_API_KEY}` }, body: JSON.stringify(request) });
    const body = await response.json();
    for (const choice of body.choices ?? []) delete choice.message?.reasoning_content;
    Object.assign(trace, { status: response.status, response: body, finishedAt: new Date().toISOString() });
    return { status: response.status, body };
  } catch (error) { trace.failure = error.name; throw error; }
  finally { writeFileSync(path, JSON.stringify(trace, null, 2)); pending = false; }
}
const server = createServer(async (request, response) => {
  try {
    const chunks = [];
    for await (const chunk of request) chunks.push(chunk);
    const result = await invoke(JSON.parse(Buffer.concat(chunks).toString('utf8')));
    response.writeHead(result.status, { 'content-type': 'application/json' }); response.end(JSON.stringify(result.body));
  } catch { response.writeHead(502); response.end('{"error":{"message":"evaluation stopped"}}'); }
});
await new Promise((resolve, reject) => { server.once('error', reject); server.listen(9079, '127.0.0.1', resolve); });
const messages = (system, value) => ({ max_tokens: protocol.maxTokens,
  messages: [{ role: 'system', content: system }, { role: 'user', content: JSON.stringify(value) }] });
try {
  const cases = read(resolve(directory, 'cases.json'));
  if (phase === 'generation') {
    assert(!existsSync(resolve(directory, 'generation.started.json')));
    save('generation.started.json', { time: new Date().toISOString() });
    const dependencies = readFileSync(resolve(root, 'tmp/backend-local-managed/2026-11-06T18-01-49-217Z-0b294b71/runtime-classpath.txt'), 'utf8').trim();
    const child = spawn('E:/Delelop/Java/jdk-21/bin/java.exe', ['-Xmx512m',
      '-Djdk.net.unixdomain.tmpdir=' + resolve(root, 'tmp/backend-local-managed/nio'), '--class-path',
      resolve(root, 'services/api/target/classes') + ';' + dependencies, '--source', '21',
      resolve(root, 'scripts/evaluation/natural-expression/ExpressionPaths.java'), directory], { stdio: 'inherit' });
    assert.equal(await new Promise(resolve => child.on('exit', resolve)), 0);
    for (const sample of cases) {
      save(`${sample.id}_simple.json`, await invoke(messages(
        '将需求完善成清楚、自然、可执行的提示词。结合给定材料，保留用户意图、明确要求和真实回答，补充有用内容。只返回优化提示词。', sample.input)));
    }
  } else if (phase === 'works') {
    const selection = read(resolve(directory, 'work-selection.json'));
    for (const sample of cases.filter(item => selection.cases.includes(item.id))) {
      const prompts = { raw: sample.input.rawPrompt,
        simple: read(resolve(directory, `${sample.id}_simple.json`)).body?.choices?.[0]?.message?.content };
      for (const version of ['core-repair-v5', 'core-expression-v1']) {
        const result = read(resolve(directory, `${sample.id}_${version}.json`));
        if (result.success) prompts[version] = result.body;
      }
      for (let round = 1; round <= protocol.repetitions; round++) {
        const arms = Object.entries(prompts);
        if (round === 2) arms.reverse();
        for (const [arm, prompt] of arms) {
          if (!prompt) continue;
          save(`${sample.id}_${arm}_work${round}.json`, await invoke(messages(
            '执行用户任务，保留原交付范围和限制。', { prompt, context: sample.input.context,
              planningContext: sample.input.planningContext, actualAnswers: sample.input.actualAnswers })));
        }
      }
    }
  } else if (phase === 'holdout') {
    // 入口复用实际Provider，题目只在候选冻结后的独立文件中加载。
    throw new Error('请使用已冻结的新题Provider入口');
  } else {
    console.log('WORKFLOW_RELAY_READY');
    await new Promise(resolve => {
      process.stdin.setEncoding('utf8');
      process.stdin.on('data', text => { if (text.trim() === 'close') resolve(); });
    });
  }
} finally {
  save(`${phase}.closure.json`, { time: new Date().toISOString(),
    actualCalls: readdirSync(traces).filter(name => /^\d{3}\.json$/.test(name)).length });
  server.close();
}
