import { readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { createHash } from 'node:crypto';
import { checkResearchLength, BODY_HAN_POLICY } from '../../../scripts/me00a-work-checks.mjs';
const output = resolve('tmp/me00a-20261012/candidate13-real');
const hash = text => createHash('sha256').update(text).digest('hex');
const manifest = JSON.parse(await readFile(resolve('docs/testing/evidence/me00a-candidate13-2026-10-15/candidate13-source.json'), 'utf8'));
const names = ['doubao-raw', 'doubao-direct', 'doubao-plan', 'deepseek-raw', 'deepseek-direct', 'deepseek-plan'];
const mapping = Object.fromEntries(names.sort((a, b) => hash(manifest.frozenAt + a).localeCompare(hash(manifest.frozenAt + b)))
  .map((name, index) => ['UVWXYZ'[index], name]));
const inverse = Object.fromEntries(Object.entries(mapping).map(([id, name]) => [name, id]));
const works = {}, lengths = {}, metadata = {};
for (const name of names) {
  works[name] = await readFile(resolve(output, 'works', `${name}.md`), 'utf8');
  metadata[name] = JSON.parse(await readFile(resolve(output, 'works', `${name}.json`), 'utf8'));
  if (metadata[name].workSha256 !== hash(works[name]) || !metadata[name].literalOutgoingPayloadMatches?.some(item => item.exact)) throw new Error('Work or literal submission evidence mismatch');
  lengths[name] = checkResearchLength(works[name]);
}
const inputs = Object.fromEntries(await Promise.all(['raw', 'direct', 'plan'].map(async arm => [arm, await readFile(resolve(output, arm, 'execution-input.md'), 'utf8')])));
const inputMapping = Object.fromEntries(['raw', 'direct', 'plan'].sort((a, b) => hash(inputs[a]).localeCompare(hash(inputs[b]))).map((arm, i) => ['P' + (i + 1), arm]));
const inputInverse = Object.fromEntries(Object.entries(inputMapping).map(([id, arm]) => [arm, id]));
const pairs = ['doubao', 'deepseek'].flatMap(vendor => [['raw', 'direct'], ['raw', 'plan'], ['direct', 'plan']]
  .map(([a, b]) => ({ left: inverse[`${vendor}-${a}`], right: inverse[`${vendor}-${b}`] })));
const originalSameInformation = inputs.raw;
const pack = `你是独立AI辅助评审，不是人工专家。评审的是下面六份“研究设计方法部分”实际作品的交付质量。它们来自同一原题与同一实际确认信息，不同输入版本；模型供应商、模式标签以匿名编号隐藏，段落中自然包含的术语不删除。不要预设增强必有收益。另一个评审的意见不会提供。\n\n`
  + `原始任务与实际逐字题答：\n${originalSameInformation}\n\n`
  + `任务语义：本项目Plan是增强器提出关键问题、用户回答、有效答案融入优化提示词；不是模型自行生成执行计划再作答。用户委托提出拟议推荐，允许给有条件的设计候选，实际参数仍未确认；不能用未确认推迟全部设计。评价对象在研究中是提示词质量，可辅以下游作品。原题没有要求在所有可能层级随机化，也不普遍禁止2×2设计；任何设计须与每模式每任务独立重复3次、两名评审独立盲评及公平对照自洽。\n\n`
  + `实际执行输入（逐字保存，评测没有给增强组补答案或尺度）：\n`
  + Object.entries(inputMapping).map(([id, arm]) => `输入${id}：\n${inputs[arm]}`).join('\n\n') + '\n\n'
  + Object.entries(mapping).map(([id, name]) => `作品${id}，执行输入${inputInverse[name.split('-')[1]]}：\n${works[name]}`).join('\n\n')
  + `\n\n独立篇幅检查（固定口径${BODY_HAN_POLICY.id}，Unicode汉字800至1200；排除Markdown标题、行首加粗小标题和明确附表；替代含标题计数一并保留，不能改用能过的口径；JSON/示例开关本题不适用）：\n${JSON.stringify(Object.fromEntries(Object.entries(mapping).map(([id, name]) => [id, lengths[name]])))}\n`
  + `\n逐份评价准确性、完整性、清晰度、可执行性，各1至5分。核对可开展的任务类别/来源、随机化单元与重复结构、盲评及双评覆盖、评分尺度/操作定义/分歧规则、公平对照的信息获取/封存/分发及谁提问谁回答。引述真实文字，区分明确错误、未充分说明及可合理推荐；不要把可推荐参数当作编造观测数据。自报字数不能证明合规。严重目标改变、事实错误、规则反转和硬门禁失败单列，平均分不得抵消；双方都失败时用BOTH_FAIL。对归因先标出现位置，原因可为输入歧义、执行违约、混合或未定，不凭单份作品诊断能力边界。\n`
  + `\n只输出完整严格JSON，不包Markdown代码围栏，不附其他文字：{"reviewerType":"AI_ASSISTED","scores":{"U":{"accuracy":1,"completeness":1,"clarity":1,"executability":1}},"issues":[{"work":"U","severity":"MAJOR|MINOR|SEVERE","evidence":"作品或输入中的完整原句","reason":"依据","origin":"INPUT|WORK|MIXED|UNDETERMINED"}],"pairs":[{"left":"U","right":"V","verdict":"LEFT_WIN|RIGHT_WIN|TIE|BOTH_FAIL","reason":"依据"}]}。scores必须有U,V,W,X,Y,Z六项；issues可为空。pairs必须且仅有以下六对，方向不变：${JSON.stringify(pairs)}。`;
const saveNew = (name, value) => writeFile(resolve(output, name), typeof value === 'string' ? value : JSON.stringify(value, null, 2), { flag: 'wx' });
await saveNew('review-mapping.json', { mapping, inputMapping, pairs });
await saveNew('independent-length-check.json', { policy: BODY_HAN_POLICY, works: lengths, strictJson: 'NOT_APPLICABLE', examples: 'DISABLED_NOT_APPLICABLE' });
await saveNew('review-pack.md', pack);
await saveNew('review-lock.json', { frozenAt: new Date().toISOString(), inputSha256: hash(pack), mappingSha256: hash(JSON.stringify({ mapping, inputMapping, pairs })),
  reviewers: ['doubao', 'deepseek'], vendorAndArmLabelsHidden: true, samePack: true, newChats: true, exactModelVersion: 'UNKNOWN', noOtherReviewerOpinion: true });
console.log(JSON.stringify({ works: 6, packChars: pack.length, inputSha256: hash(pack), pairs: 6, lengthChecks: lengths }));
