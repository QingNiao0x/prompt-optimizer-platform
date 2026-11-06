/** 本批唯一外发与计数入口。先冻结、先落盘计数，再向已授权的官方端点发送。 */
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { createServer } from 'node:http';
import { readFile, writeFile, mkdir, readdir, access } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

export const directory = dirname(fileURLToPath(import.meta.url));
export const root = resolve(directory, '../../../..');
export const runtime = resolve(root, 'tmp/natural-internal-design-adoption-2026-11-07');
export const readJson = async path => JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, ''));
export const save = (path, value) => writeFile(path, JSON.stringify(value, null, 2) + '\n', { flag: 'wx' });
export const digest = value => createHash('sha256').update(value).digest('hex');
export const exists = async path => { try { await access(path); return true; } catch { return false; } };
const phaseLimits = { author: 2, regression: 32, enhancement: 48, execute: 60, review: 24, workflow: 34 };
const traceDirectory = resolve(directory, 'traces');
let activeJob;
let pending = false;

export const traceNames = async () => (await readdir(traceDirectory)).filter(name => /^\d{4}\.json$/.test(name)).sort();

/** 冻结正文及输入保护代码。采用后的两处默认配置另记录，不能顺带放行其他源码变化。 */
async function verifyFrozen(phase) {
  assert.equal(await exists(resolve(directory, 'closure.json')), false, '本批已关闭');
  const protocol = await readJson(resolve(directory, 'protocol.json'));
  const adoption = phase === 'workflow' && await exists(resolve(directory, 'adoption.json'))
    ? await readJson(resolve(directory, 'adoption.json')) : undefined;
  for (const file of protocol.files) {
    const adopted = adoption?.sourceHashes?.find(item => item.path === file.path);
    assert.equal(digest(await readFile(resolve(root, file.path))), adopted?.sha256 ?? file.sha256, `冻结文件变化 ${file.path}`);
  }
  return protocol;
}

/** 所有失败和协议修复均占用一次实际调用，不记录认证头，也不保存模型思考内容。 */
async function dispatch(payload, phase, job) {
  assert.equal(pending, false, '本批串行调用');
  pending = true;
  try {
    const protocol = await verifyFrozen(phase);
    for (const key of ['model', 'temperature', 'max_tokens']) assert.equal(payload[key], protocol[key]);
    assert.equal(payload.stream, false);
    assert.equal(payload.thinking?.type, 'disabled');
    const names = await traceNames();
    assert(names.length < protocol.totalMaximum, '实际调用已达到200次');
    const traces = await Promise.all(names.map(name => readJson(resolve(traceDirectory, name))));
    assert(traces.filter(item => item.phase === phase).length < protocol.phaseLimits[phase], '阶段额度已满');
    const credential = process.env.MODEL_API_KEY;
    assert(credential, '没有可用的既有模型凭据');
    const path = resolve(traceDirectory, `${String(names.length + 1).padStart(4, '0')}.json`);
    const trace = { phase, job, startedAt: new Date().toISOString(), request: payload };
    await save(path, trace);
    const started = Date.now();
    try {
      const response = await fetch(protocol.endpoint, {
        method: 'POST', headers: { 'content-type': 'application/json', authorization: `Bearer ${credential}` },
        body: JSON.stringify(payload), signal: AbortSignal.timeout(180_000), redirect: 'error',
      });
      const text = (await response.text()).replaceAll(credential, '[REDACTED]');
      trace.status = response.status;
      try {
        trace.response = JSON.parse(text);
        for (const choice of trace.response.choices ?? []) delete choice.message?.reasoning_content;
      } catch { trace.nonJsonSha256 = digest(text); }
      trace.elapsedMs = Date.now() - started;
      await writeFile(path, JSON.stringify(trace, null, 2) + '\n');
      console.log(JSON.stringify({ call: names.length + 1, phase, job, status: response.status }));
      return { status: response.status, body: trace.response ?? { error: { message: '上游没有返回JSON' } } };
    } catch (error) {
      trace.elapsedMs = Date.now() - started;
      trace.errorType = error.name;
      await writeFile(path, JSON.stringify(trace, null, 2) + '\n');
      throw error;
    }
  } finally { pending = false; }
}

/** 模型输入只可来自当前冻结题，包含题干的字符串亦可位于已有阶段协议内。 */
const containsTask = (value, task) => {
  if (typeof value === 'string') {
    if (value.includes(task)) return true;
    const start = value.indexOf('{');
    if (start < 0) return false;
    try { return containsTask(JSON.parse(value.slice(start)), task); }
    catch { return false; }
  }
  if (Array.isArray(value)) return value.some(item => containsTask(item, task));
  if (value && typeof value === 'object') return Object.values(value).some(item => containsTask(item, task));
  return false;
};

async function allCases() {
  const cases = (await readJson(resolve(directory, 'cases.json'))).cases;
  if (await exists(resolve(directory, 'holdout.json'))) cases.push(...(await readJson(resolve(directory, 'holdout.json'))).cases);
  return cases;
}

async function serve() {
  await verifyFrozen();
  createServer(async (request, response) => {
    try {
      assert.equal(request.method, 'POST');
      const chunks = [];
      let bytes = 0;
      for await (const chunk of request) {
        bytes += chunk.length;
        assert(bytes <= 2_000_000, '本批载荷超出冻结容量');
        chunks.push(chunk);
      }
      const input = JSON.parse(Buffer.concat(chunks).toString('utf8'));
      if (request.url === '/job') {
        assert.equal(pending, false);
        assert(Object.hasOwn(phaseLimits, input.phase));
        assert(/^[A-Za-z0-9_-]{1,100}$/.test(input.job));
        activeJob = input;
        response.writeHead(200, { 'content-type': 'application/json' });
        response.end('{"ready":true}');
        return;
      }
      assert(activeJob, '先绑定本次冻结任务');
      if (request.url === '/chat/completions') {
        const sample = (await allCases()).find(item => item.id === activeJob.caseId);
        assert(sample && input.messages.some(message => containsTask(message.content, sample.rawPrompt)), '业务输入不属于冻结题');
        const result = await dispatch(input, activeJob.phase, activeJob.job);
        response.writeHead(result.status, { 'content-type': 'application/json' });
        response.end(JSON.stringify(result.body));
      } else {
        assert.equal(request.url, '/invoke');
        assert(['author', 'execute', 'review'].includes(activeJob.phase));
        const result = await dispatch(input, activeJob.phase, activeJob.job);
        response.writeHead(200, { 'content-type': 'application/json' });
        response.end(JSON.stringify(result));
      }
    } catch (error) {
      console.log(JSON.stringify({ event: 'evaluation.gate.failed', type: error.name, message: error.message }));
      response.writeHead(502, { 'content-type': 'application/json' });
      response.end('{"error":{"message":"冻结条件或评测转发未通过"}}');
    }
  }).listen(9078, '127.0.0.1', () => console.log('COUNTED_RELAY_READY_9078'));
}

async function freeze() {
  await mkdir(traceDirectory, { recursive: true });
  await mkdir(runtime, { recursive: true });
  const prefix = 'services/api/src/main/java/com/promptoptimizer/';
  const sources = [
    'provider/domain/NaturalPromptInternalDesignInstructions.java', 'provider/domain/NaturalPromptPlanContextInstructions.java',
    'provider/domain/NaturalPromptCoreRepairInstructions.java', 'provider/domain/NaturalPromptStageInstructions.java',
    'provider/domain/NaturalPromptInstructions.java', 'provider/domain/NaturalTaskReview.java',
    'provider/infrastructure/openai/OpenAiCompatibleNaturalPromptProvider.java',
    'enhancement/service/impl/NaturalEnhancementService.java', 'enhancement/service/impl/NaturalEnhancementSettings.java',
    'history/service/impl/MybatisOptimizationHistoryService.java',
  ].map(path => prefix + path);
  const files = [...sources, 'services/api/src/main/resources/application.yml',
    'apps/web/src/features/optimization/optimizationRequest.ts', 'apps/web/src/features/optimization/promptHandoff.ts',
    'apps/web/src/components/prompt/OptimizationResultPanel.vue', 'apps/web/src/components/prompt/PromptHandoffDialog.vue',
    ...['protocol.md', 'cases.json', 'evaluation.mjs', 'browser.mjs', 'runtime.ps1', 'works.mjs', 'check-works.mjs']
      .map(name => `docs/testing/evidence/natural-internal-design-adoption-2026-11-07/${name}`)];
  const hashes = [];
  for (const path of files) hashes.push({ path, sha256: digest(await readFile(resolve(root, path))) });
  const commit = spawnSync('git', ['rev-parse', 'HEAD'], { cwd: root, encoding: 'utf8' });
  assert.equal(commit.status, 0);
  await save(resolve(directory, 'protocol.json'), {
    frozenAt: new Date().toISOString(), sourceCommit: commit.stdout.trim(),
    totalMaximum: 200, phaseLimits, endpoint: 'https://api.deepseek.com/chat/completions',
    model: 'deepseek-flash', temperature: 0.2, max_tokens: 10000, qualityRetries: 0,
    arms: { raw: '原始提示词', current: 'core-repair-v4', candidate: 'internal-design-v1' }, files: hashes,
    externalScope: '冻结的合成材料、公开来源摘要及相应题答、提示词和作品。没有真实业务源码或凭据。',
    assessment: '确定性检查及内部匿名AI辅助评阅，非外部用户或专家验收。',
  });
  console.log('FROZEN_200');
}

if (process.argv[2] === 'freeze') await freeze();
else if (process.argv[2] === 'serve') await serve();
else if (process.argv[2] === 'count') console.log(JSON.stringify({ actualCalls: (await traceNames()).length, maximum: 200 }));
