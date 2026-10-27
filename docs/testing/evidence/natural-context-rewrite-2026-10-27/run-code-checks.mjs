import { readFile, readdir, writeFile, mkdir } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { materializeAndCheckCodeWork } from './check-code-work.mjs';

const evidence = dirname(fileURLToPath(import.meta.url));
const repo = resolve(evidence, '../../../..');
const results = resolve(repo, 'tmp/natural-context-rewrite-2026-10-27/works/results');
const output = resolve(evidence, 'code-work-verdicts');
await mkdir(output, { recursive: true });
const verdicts = [];
for (const name of (await readdir(results)).filter((name) => /_EXEC_R\d+\.json$/.test(name)).sort()) {
  const result = JSON.parse(await readFile(resolve(results, name), 'utf8'));
  let verdict;
  try {
    if (result.status !== 200 || result.body.data.finishReason !== 'stop') throw Error('代码作品未完整交付');
    const work = JSON.parse(result.body.data.content);
    verdict = await materializeAndCheckCodeWork(work,
      resolve(repo, 'tmp/natural-context-rewrite-2026-10-27/code-works', result.id));
  } catch (failure) {
    verdict = { acceptance: { passed: false, checks: [{ id: 'delivery-and-execution', passed: false,
      message: String(failure.message).slice(0, 1200) }] } };
  }
  const record = { id: result.id, version: result.version, ...verdict,
    scope: '同信息下的合成模块业务行为；不执行模型自写测试，不代表真实Vue页面或用户业务系统已修改' };
  await writeFile(resolve(output, name), JSON.stringify(record, null, 2) + '\n', { flag: 'wx' });
  verdicts.push({ id: result.id, passed: record.acceptance.passed,
    passedChecks: record.acceptance.checks.filter((item) => item.passed).length,
    totalChecks: record.acceptance.checks.length });
}
await writeFile(resolve(output, 'summary.json'), JSON.stringify({ verdicts, modelCalls: 0 }, null, 2) + '\n', { flag: 'wx' });
console.log(JSON.stringify({ verdicts, modelCalls: 0 }));
