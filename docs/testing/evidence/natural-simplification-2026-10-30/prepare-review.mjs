import { readFile, readdir, writeFile, mkdir } from 'node:fs/promises';
import { randomInt } from 'node:crypto';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { readJson, saveNew, sha } from './simple-baseline.mjs';

const root = dirname(fileURLToPath(import.meta.url));
const repo = resolve(root, '../../../..');
const previous = resolve(repo, 'docs/testing/evidence/natural-writer-input70-2026-10-29');
const privateRoot = resolve(repo, 'tmp/natural-writer-input70-2026-10-29/simple-review');
const closure = await readJson(resolve(root, 'closure.json'));
if (closure.status !== 'CLOSED') throw Error('All model attempts must be closed before review');
const cases = await readJson(resolve(root, 'cases.json'));
const historical = [];
for (const name of await readdir(resolve(previous, 'works/traces'))) historical.push(await readJson(resolve(previous, 'works/traces', name)));
const packet = [];
const mapping = [];
const form = { reviewerKind: 'HUMAN', completed: false, reviews: [] };
for (let i = 0; i < cases.length; i++) {
  const sample = cases[i];
  const arms = [];
  for (const [arm, jobId] of Object.entries(sample.historicalR1)) {
    const trace = historical.find(item => item.jobId === jobId);
    if (!trace || trace.httpStatus !== 200) throw Error('Prespecified historical R1 missing');
    arms.push({ arm, jobId, content: trace.response.choices[0].message.content, httpStatus: trace.httpStatus });
  }
  const simple = await readJson(resolve(root, 'traces', 'SIMPLE_N' + (i + 1) + '_EXEC.json'));
  arms.push({ arm: 'simple', jobId: simple.jobId, content: simple.response?.choices?.[0]?.message?.content ?? '', httpStatus: simple.httpStatus ?? 0 });
  // 只打乱事先指定的三份首次作品；不从R1/R2里挑较好的那份。映射不进入给用户的评阅包。
  for (let j = arms.length - 1; j > 0; j--) { const k = randomInt(j + 1); [arms[j], arms[k]] = [arms[k], arms[j]]; }
  const works = arms.map((item, j) => {
    const workId = sample.id + '-' + String.fromCharCode(65 + j);
    mapping.push({ workId, arm: item.arm, jobId: item.jobId, sha256: sha(item.content) });
    return { workId, content: item.content, delivered: item.httpStatus === 200 };
  });
  packet.push({ caseId: sample.id, rawPrompt: sample.rawPrompt, materials: sample.materials, works });
  form.reviews.push({ caseId: sample.id, preferredWorkIds: [],
    works: works.map(work => ({ workId: work.workId, directlyUsable: null, requiredEdits: null, severeErrors: null, reason: '' })) });
}
await mkdir(privateRoot, { recursive: true });
await saveNew(resolve(privateRoot, 'blind-map.json'), { mapping });
await saveNew(resolve(root, 'anonymous-works.json'), { description: '预定R1、相同信息、三个匿名臂；内部材料，非独立留出；不隐藏已有第二次结果的存在。', cases: packet });
await saveNew(resolve(root, 'human-review-form.json'), form);
const sections = ['# 匿名实际作品评阅包', '',
  '每题有三份作品，均获得相同材料。请先按是否能直接使用、遗漏/错误和必要修改量判断，再选偏好。字数多、格式好看不单独算收益。', '',
  '这里是两道已见合成题的有限试验，不代表真实用户总体。每臂主比较只用事先指定的首次作品；历史第二次结果继续保留在原报告。代码可以阅读，执行检查由隔离夹具完成。', '',
  '请在同目录 human-review-form.json 填写，或直接回复每题偏好的A/B/C及需要修改的地方。当前表单尚未填写，不代表任何真人结论。', ''];
for (const sample of packet) {
  sections.push('## ' + sample.caseId, '', '原始提示词：' + sample.rawPrompt, '', '### 共同材料', '');
  for (const material of sample.materials) sections.push('**' + material.path + '**', '', material.content, '');
  for (const work of sample.works) sections.push('### 作品 ' + work.workId, '', '````text', work.content, '````', '');
}
const markdown = sections.join('\n');
await writeFile(resolve(root, '匿名作品评阅包.md'), markdown, { flag: 'wx' });
await saveNew(resolve(root, 'review-manifest.json'), {
  preparedAt: new Date().toISOString(), primaryHistoricalRepetition: 1, humanReviewCompleted: false,
  packetSha256: sha(markdown), mappingSha256: sha({ mapping }), originalClosureUnchanged: true,
  noAutomaticAdoption: true, pending: '真实用户判断和真实用户任务对照；不能由代理填写真人结论'
});
console.log(JSON.stringify({ prepared: true, works: packet.reduce((n, item) => n + item.works.length, 0), humanReviewCompleted: false }));
