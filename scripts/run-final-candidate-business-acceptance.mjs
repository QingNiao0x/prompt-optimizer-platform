/** 使用正常已登录的本地浏览器运行冻结验收；每题答案审核文件与模型输入隔离，首次结果不覆盖。 */
import { createRequire } from 'node:module';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { createFinalCandidateAcceptance } from './final-candidate-business-acceptance.mjs';

const args = process.argv.slice(2);
if (args.includes('--help')) {
  console.log('node scripts/run-final-candidate-business-acceptance.mjs --run <directory> --action setup|collect|finish --cases <json or comma IDs> --model <published ID> [--arm plan|direct] [--answers <reviewed json>]');
  process.exit(0);
}
const option = name => args[args.indexOf(name) + 1];
for (const name of ['--run', '--action']) if (!args.includes(name)) throw new Error(`Missing ${name}`);
const run = resolve(option('--run'));
const action = option('--action');
if (!['setup', 'collect', 'finish'].includes(action)) throw new Error('Invalid action');
const webRequire = createRequire(new URL('../apps/web/package.json', import.meta.url));
const { chromium } = webRequire('@playwright/test');
const browser = await chromium.connectOverCDP('http://127.0.0.1:9325');
const page = browser.contexts().flatMap(context => context.pages()).find(page => page.url().startsWith('http://127.0.0.1:5175/'));
if (!page) throw new Error('No authenticated local acceptance page');
const cases = action === 'setup' ? JSON.parse(await readFile(resolve(option('--cases')), 'utf8')) : undefined;
const session = await createFinalCandidateAcceptance(page, { output: run, cases, resume: action !== 'setup', callsPerModelLimit: 48 });
if (action !== 'setup') {
  const model = option('--model');
  const evidenceTag = args.includes('--evidence-tag') ? option('--evidence-tag') : '';
  if (evidenceTag && !/^[a-zA-Z0-9_-]{1,32}$/.test(evidenceTag)) throw new Error('Invalid evidence tag');
  const ids = option('--cases').split(',');
  const reviewed = action === 'finish' ? JSON.parse(await readFile(resolve(option('--answers')), 'utf8')) : null;
  for (const caseId of ids) {
    const evidenceId = `${caseId}-${model.replace(/[^a-zA-Z0-9_-]/g, '_')}-plan`;
    const result = action === 'collect'
      ? await session.collect(caseId, model, args.includes('--arm') ? option('--arm') : 'plan')
      : await session.finish({ ...JSON.parse(await readFile(resolve(run, `${evidenceId}.json`), 'utf8')),
        // 修正验收工具自己的回答审核格式时，另存尝试标识；计划、原始需求及首次失败不改变。
        id: evidenceId + (evidenceTag ? '-' + evidenceTag : '') }, reviewed[caseId] ?? {});
    console.log(JSON.stringify({ caseId, model, action, status: result.status,
      questions: result.plan?.questions.length, ambiguities: result.result?.ambiguities.length,
      latencyMs: result.result?.latencyMs ?? result.plan?.latencyMs,
      requests: result.requests.map(item => ({ status: item.status, elapsedMs: item.elapsedMs, code: item.code })) }));
  }
} else console.log(JSON.stringify({ action, cases: session.cases.length, models: session.models.length, run }));
// 不关闭共享 Chrome，守护脚本在整轮结束后统一释放自己的浏览器。
process.exitCode = 0;
await browser.close();
