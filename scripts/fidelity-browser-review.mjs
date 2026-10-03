/**
 * 核对真实生成结果的历史落库与原生剪贴板。无Mock接口，也不额外调用模型。
 * 复制检查将同轮真实响应载入工作台Store；这是输出回放，不冒称重新走完上传和生成界面。
 */
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
import { createHash } from 'node:crypto';

const root = fileURLToPath(new URL('../', import.meta.url));
const argument = process.argv[2];
if (argument === '--help') {
  console.log('node scripts/fidelity-browser-review.mjs <tmp/fidelity-acceptance/run-directory>');
  process.exit(0);
}
if (!argument) throw new Error('缺少验收目录。');
const output = resolve(argument);
if (!output.startsWith(resolve(root, 'tmp/fidelity-acceptance') + sep)) throw new Error('非法验收目录。');
// 每次补验单独留档；首次控件等待失败的证据不能被后续成功覆盖。
const reviewDirectory = resolve(output, 'reviews', new Date().toISOString().replace(/[:.]/g, '-'));
const parse = async name => JSON.parse(await readFile(resolve(output, name), 'utf8'));
const hash = text => createHash('sha256').update(text).digest('hex');
// Windows 原生剪贴板会将 LF 转为 CRLF；仅统一行结束符，不 trim 或忽略正文字符。
const logicalText = text => text.replace(/\r\n/g, '\n');
const manifest = await parse('manifest.json');
const frozenCases = await parse('cases.json');
// 定向补验使用独立的场景清单，不改动原始冻结输入，也不要求重新生成无关场景。
let selectedCaseIds;
try {
  selectedCaseIds = (await parse('selected-cases.json')).caseIds;
  if (!Array.isArray(selectedCaseIds) || selectedCaseIds.length === 0
    || new Set(selectedCaseIds).size !== selectedCaseIds.length
    || selectedCaseIds.some(id => !frozenCases.some(item => item.id === id))) {
    throw new Error('补验场景清单不合法。');
  }
} catch (error) { if (error.code !== 'ENOENT') throw error; }
const cases = selectedCaseIds ? frozenCases.filter(item => selectedCaseIds.includes(item.id)) : frozenCases;
const clipboardCaseIds = [...new Set([
  ...['02_software_threshold_conflict', '03_research_yll_unresolved', '08_data_unresolved_denominator']
    .filter(id => cases.some(item => item.id === id)),
  ...cases.map(item => item.id),
])].slice(0, 3);
const require = createRequire(new URL('../apps/web/package.json', import.meta.url));
const { chromium, expect } = require('@playwright/test');
const browser = await chromium.connectOverCDP('http://127.0.0.1:9325');
const context = browser.contexts()[0];
const page = await context.newPage();
page.setDefaultTimeout(15000);
await context.grantPermissions(['clipboard-read', 'clipboard-write'], { origin: 'http://127.0.0.1:5175' });
const report = { startedAt: new Date().toISOString(), caseIds: cases.map(item => item.id), clipboardCaseIds,
  history: [], clipboard: [], scope: '真实历史API；真实响应在工作台Store回放后使用原生剪贴板；不生成新结果、不打开外部AI。' };

try {
  // 页面未就绪同样保留失败证据；不能把未进入复制步骤计为复制通过或产品缺陷。
  await page.goto('http://127.0.0.1:5175/workbench');
  const authStatus = await page.evaluate(async () => (await fetch('/api/v1/auth/me')).status);
  if (authStatus !== 200) throw new Error(`验收浏览器尚未正常登录（HTTP ${authStatus}），未执行历史与剪贴板检查。`);
  await page.getByLabel('原始提示词', { exact: true }).waitFor({ state: 'attached' });
  // 以本轮原始提示词和创建时间限定记录；不导出同一账号的无关历史。
  for (const item of cases) {
    const records = await page.evaluate(async ({ keyword, startedAt, rawPrompt }) => {
      const response = await fetch('/api/v1/optimization-history?current=1&size=50&keyword=' + encodeURIComponent(keyword));
      if (!response.ok) throw new Error('历史列表HTTP ' + response.status);
      const data = (await response.json()).data;
      // 列表只有 rawPromptPreview；完整原始提示词必须在详情中比对，不能用不存在的字段过滤。
      const selected = data.records.filter(record => Date.parse(record.createdAt) >= Date.parse(startedAt));
      const details = [];
      for (const record of selected) {
        const detail = await fetch('/api/v1/optimization-history/' + record.id);
        if (!detail.ok) throw new Error('历史详情HTTP ' + detail.status);
        const content = (await detail.json()).data;
        if (content.rawPrompt === rawPrompt) details.push(content);
      }
      return details.map(detail => ({ id: detail.id, optimizedPrompt: detail.optimizedPrompt, sections: detail.sections }));
    }, { keyword: item.rawPrompt.slice(0, 25), startedAt: manifest.startedAt, rawPrompt: item.rawPrompt });
    for (const arm of ['direct', 'plan-final']) {
      const result = await parse(`results/${item.id}--${arm}.json`);
      const matching = records.find(record => record.optimizedPrompt === result.final.optimizedPrompt);
      report.history.push({ id: item.id, arm, matched: !!matching,
        // JSONB 可能改变对象键顺序；按协议字段和原有段落顺序逐值比较，不修改正文。
        sectionsEqual: !!matching && JSON.stringify(matching.sections.map(s => [s.type, s.title, s.content]))
          === JSON.stringify(result.final.sections.map(s => [s.type, s.title, s.content])),
        promptSha256: hash(result.final.optimizedPrompt) });
    }
  }
  for (const width of [1440, 390]) {
    await page.setViewportSize({ width, height: 1000 });
    for (const caseId of clipboardCaseIds) {
      const result = (await parse(`results/${caseId}--plan-final.json`)).final;
      await page.evaluate(async result => {
        const { useOptimizationStore } = await import('/src/stores/optimization.ts');
        const store = useOptimizationStore();
        store.result = result;
        store.contextSnapshot = result.contextReport;
        store.rawPrompt = '真实验收结果的复制回放；本次不请求模型。';
      }, result);
      if (width < 900) await page.getByRole('tab', { name: '增强结果', exact: true }).click();
      await page.bringToFront();
      await page.locator('.copy-main-button').click();
      await expect.poll(async () => logicalText(await page.evaluate(() => navigator.clipboard.readText())))
        .toBe(logicalText(result.optimizedPrompt));
      const rawCopied = await page.evaluate(() => navigator.clipboard.readText());
      const copied = logicalText(rawCopied);
      const task = result.sections.find(section => section.type === 'TASK');
      await page.getByRole('button', { name: '编辑', exact: true }).click();
      await page.locator('#section-TASK').fill(task.content + '\n本行仅验证本地编辑复制，不执行任务。');
      await page.getByRole('button', { name: '保存修改', exact: true }).click();
      await page.locator('.copy-main-button').click();
      await expect.poll(() => page.evaluate(() => navigator.clipboard.readText()))
        .toContain('本行仅验证本地编辑复制，不执行任务。');
      const edited = logicalText(await page.evaluate(() => navigator.clipboard.readText()));
      await page.getByRole('button', { name: '编辑', exact: true }).click();
      await page.locator('#section-CONSTRAINTS').fill('未保存的草稿');
      await page.getByRole('button', { name: '取消', exact: true }).click();
      await page.locator('.copy-main-button').click();
      await expect.poll(async () => logicalText(await page.evaluate(() => navigator.clipboard.readText()))).toBe(edited);
      const cancelled = logicalText(await page.evaluate(() => navigator.clipboard.readText()));
      report.clipboard.push({ id: caseId, width, exactCopy: copied === logicalText(result.optimizedPrompt),
        byteExact: rawCopied === result.optimizedPrompt, newlinePolicy: 'CRLF->LF only',
        chars: copied.length, sha256: hash(copied), rawClipboardSha256: hash(rawCopied),
        editedConstraintsPreserved: result.sections.filter(s => s.type === 'CONSTRAINTS').every(s => edited.includes(logicalText(s.content))),
        editApplied: edited.includes('本行仅验证本地编辑复制，不执行任务。'),
        cancelledEditUnchanged: cancelled === edited });
    }
  }
} catch (error) { report.error = error.message; }
finally {
  await page.close();
  await mkdir(reviewDirectory, { recursive: true });
  await writeFile(resolve(reviewDirectory, 'browser-history-review.json'), JSON.stringify(report, null, 2), { encoding: 'utf8', flag: 'wx' });
}
const passed = !report.error && report.history.length === cases.length * 2 && report.history.every(r=>r.matched && r.sectionsEqual)
  && report.clipboard.length === clipboardCaseIds.length * 2
  && report.clipboard.every(r=>r.exactCopy && r.editedConstraintsPreserved && r.editApplied && r.cancelledEditUnchanged);
console.log(JSON.stringify({ passed, historyCount: report.history.length, clipboardCount: report.clipboard.length, error: report.error, reviewDirectory }));
process.exit(passed ? 0 : 1);
