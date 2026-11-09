/** 本轮串行转发计数，最多24次。密钥仅用于已授权官方端点的认证头。 */
import assert from 'node:assert/strict';
import { readFileSync, writeFileSync, readdirSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { createServer } from 'node:http';
import { spawn } from 'node:child_process';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const directory = dirname(fileURLToPath(import.meta.url));
const root = resolve(directory, '../../../..');
const previous = resolve(root, 'docs/testing/evidence/natural-internal-design-adoption-2026-11-07');
const read = path => JSON.parse(readFileSync(path, 'utf8').replace(/^\uFEFF/, ''));
const save = (name, data) => writeFileSync(resolve(directory, name), JSON.stringify(data, null, 2) + '\n', { flag: 'wx' });
assert(!readdirSync(directory).includes('protocol.json'), '本批只运行一次');
const traces = readdirSync(resolve(previous, 'traces')).filter(name => /^\d{4}\.json$/.test(name))
  .map(name => read(resolve(previous, 'traces', name)));
const samples = [];
for (const id of ['W_QUERY', 'W_CODE', 'R_PLAN', 'R_WAIT', 'R_REPORT']) {
  const trace = traces.find(trace => {
    if (!trace.job.startsWith('current_' + id + '_')) return false;
    if (id === 'W_CODE' && !trace.job.includes('contextfix')) return false;
    const user = trace.request.messages.find(message => message.role === 'user').content;
    const input = JSON.parse(user.slice(user.indexOf('{')));
    return id !== 'R_PLAN' || input.actualAnswers.length > 0;
  });
  assert(trace, id);
  const user = trace.request.messages.find(message => message.role === 'user').content;
  const input = JSON.parse(user.slice(user.indexOf('{')));
  for (const key of ['taskSources', 'taskScope', 'inputBoundary', 'planProgress']) delete input[key];
  input.modelId = null;
  input.strategyVersion = 'core-repair-v5';
  samples.push({ id, input });
}
save('cases.json', samples);
const files = ['provider/domain/NaturalPromptProtocolRepairInstructions.java',
  'provider/infrastructure/openai/OpenAiCompatibleNaturalPromptProvider.java',
  'provider/infrastructure/openai/NaturalPromptJsonFailure.java'];
save('protocol.json', { frozenAt: new Date().toISOString(), maximum: 24, qualityRetries: 0,
  endpoint: 'https://api.deepseek.com/chat/completions', model: 'deepseek-flash', temperature: 0.2,
  cases: samples.map(sample => sample.id), scope: 'Provider real paths only, no new daily deployment or work-quality adoption',
  files: files.map(path => ({ path, sha256: createHash('sha256').update(readFileSync(resolve(root,
    'services/api/src/main/java/com/promptoptimizer', path))).digest('hex') })) });
let count = 0;
let pending = false;
const server = createServer(async (request, response) => {
  try {
    assert(!pending && count < 24);
    pending = true;
    const chunks = [];
    for await (const chunk of request) chunks.push(chunk);
    const payload = JSON.parse(Buffer.concat(chunks).toString('utf8'));
    assert.equal(payload.model, 'deepseek-flash');
    const trace = { number: ++count, startedAt: new Date().toISOString(), request: payload };
    const name = `call-${String(count).padStart(2, '0')}.json`;
    save(name, trace);
    const upstream = await fetch('https://api.deepseek.com/chat/completions', { method: 'POST',
      headers: { 'content-type': 'application/json', authorization: `Bearer ${process.env.MODEL_API_KEY}` },
      body: JSON.stringify(payload), redirect: 'error', signal: AbortSignal.timeout(120000) });
    const body = await upstream.text();
    trace.status = upstream.status;
    trace.response = JSON.parse(body);
    for (const choice of trace.response.choices ?? []) delete choice.message?.reasoning_content;
    writeFileSync(resolve(directory, name), JSON.stringify(trace, null, 2) + '\n');
    response.writeHead(upstream.status, { 'content-type': 'application/json' });
    response.end(body);
  } catch (error) {
    response.writeHead(502, { 'content-type': 'application/json' });
    response.end('{"error":{"message":"evaluation failed"}}');
    console.log(error.name);
  } finally { pending = false; }
});
await new Promise(resolve => server.listen(9079, '127.0.0.1', resolve));
const dependencies = readFileSync(resolve(root,
  'tmp/backend-local-managed/2026-11-06T18-01-49-217Z-0b294b71/runtime-classpath.txt'), 'utf8').trim();
const child = spawn('E:/Delelop/Java/jdk-21/bin/java.exe', ['-Xmx512m',
  '-Djdk.net.unixdomain.tmpdir=' + resolve(root, 'tmp/backend-local-managed/nio'), '--class-path',
  resolve(root, 'services/api/target/classes') + ';' + dependencies, '--source', '21',
  resolve(directory, 'LivePaths.java'), directory], { cwd: root, stdio: 'inherit' });
child.on('exit', code => {
  save('closure.json', { closedAt: new Date().toISOString(), actualCalls: count, maximum: 24, exitCode: code });
  server.close();
  process.exitCode = code ?? 1;
});
