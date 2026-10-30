import { readFile, writeFile, readdir } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { readJson, saveNew, sha } from './simple-baseline.mjs';

// 此入口只记录本会话已经收到的这一次明确反馈，不生成评分或模拟其他真人意见。
const root = dirname(fileURLToPath(import.meta.url));
const repo = resolve(root, '../../../..');
const privateRoot = resolve(repo, 'tmp/natural-writer-input70-2026-10-29/simple-review');
const manifest = await readJson(resolve(root, 'review-manifest.json'));
const packetText = await readFile(resolve(root, '匿名作品评阅包.md'), 'utf8');
const mapping = await readJson(resolve(privateRoot, 'blind-map.json'));
if (sha(packetText) !== manifest.packetSha256 || sha(mapping) !== manifest.mappingSha256) throw Error('Blind packet or mapping drifted');
const packet = await readJson(resolve(root, 'anonymous-works.json'));
const sample = packet.cases.find(value => value.caseId === 'W70-N1');
const revealed = mapping.mapping.filter(value => value.workId.startsWith('W70-N1-'));
for (const entry of revealed) {
  if (sha(sample.works.find(value => value.workId === entry.workId).content) !== entry.sha256) throw Error('Anonymous work content mismatch');
}
const selected = revealed.find(value => value.workId === 'W70-N1-C');
if (selected.arm !== 'original' || selected.jobId !== 'W70-N1_VORIGINAL_EXEC_R1') throw Error('Unexpected selected original provenance');
const historicalTraces = resolve(repo, 'docs/testing/evidence/natural-writer-input70-2026-10-29/works/traces');
let original;
for (const file of await readdir(historicalTraces)) {
  const trace = await readJson(resolve(historicalTraces, file));
  if (trace.jobId === selected.jobId) original = trace;
}
if (!original || sha(original.response.choices[0].message.content) !== selected.sha256
    || original.request.messages[1].content !== sample.rawPrompt) throw Error('Historical original request/response mismatch');
const feedback = {
  recordedAt: new Date().toISOString(), reviewerKind: 'HUMAN_USER', source: 'Current chat asynchronous question reply',
  caseId: 'W70-N1', originalReply: '这三份通知中作品C最好', preferredWorkIds: ['W70-N1-C'],
  rankingOfOtherWorks: 'NOT_PROVIDED', directlyUsable: null, requiredEdits: null, severeErrors: null, reason: null,
  feedbackProvidedBeforeReveal: true, packetSha256: manifest.packetSha256,
  preferredArm: selected.arm, preferredJobId: selected.jobId, preferredContentSha256: selected.sha256,
  disclosedCaseMapping: revealed, otherCaseMappingDisclosed: false,
  interpretation: '这份通知的真人偏好选择原始臂；未证明增强有用户认可的优势。不推导零修改、其他题结果、整体胜率或具体偏好原因。',
  originalContractObservationRetained: 'C仍附有可直接发送/字数合规等元说明，原内部检查的需删改项保留；偏好不等于全部硬要求通过。',
  modelCalls: 0, productAdoptionChanged: false
};
await saveNew(resolve(root, 'human-feedback-notification-01.json'), feedback);
const previousDecision = await readJson(resolve(root, 'decision.json'));
await saveNew(resolve(root, 'decision-after-human-feedback.json'), {
  ...previousDecision, recordedAt: feedback.recordedAt,
  status: 'ENGINEERING_READY_NOTIFICATION_HUMAN_PREFERS_ORIGINAL_FULL_REVIEW_PENDING',
  priorDecisionSha256: sha(await readFile(resolve(root, 'decision.json'), 'utf8')),
  humanPreferenceReceived: true, humanPreferenceCase: 'W70-N1', humanPreferredArm: 'original',
  humanReviewCompleted: false, humanReviewScope: '通知题仅有偏好选择，修改量和原因未提供；CSV未评阅',
  currentRequestRemaining: ['CSV及真实用户任务评阅、必要修改量对照', '采用决定与启用后的普通入口核实'],
  recommendation: '通知题未显示增强的用户收益，保持不采用；保留工程修复，停止扩大复杂链路，不据此追加通知专用规则。'
});
console.log(JSON.stringify({ recorded: true, selected: selected.workId, arm: selected.arm,
  notificationPreferenceComplete: true, fullHumanReviewComplete: false, modelCalls: 0 }));
