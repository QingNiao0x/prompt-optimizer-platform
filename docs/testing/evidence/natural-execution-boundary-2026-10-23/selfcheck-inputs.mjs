import assert from 'node:assert/strict';
import { enhancementInput, executionRequest, executionMaterialSuffix, reviewCaseInputs } from './evaluation-inputs.mjs';

// 本检查只构造进程内合成输入，不读取账号、凭据或网络，不产生付费调用。
const sample = { id: 'BOUNDARY_CHECK', rawPrompt: '根据资料完成用户要求的作品。',
  contextDescription: '普通增强上下文。', files: [{ path: 'synthetic.txt', content: '普通文件内容。' }],
  executorMaterials: [{ name: '执行阶段新增资料.txt', content: 'EXECUTOR_ONLY_DISTINCTIVE_SYNTHETIC_DATA_7d2631。' }],
  answerBundle: '用户确认的已知条件。', acceptance: ['评分卡不向生成和执行外发。'] };
const protocol = { modelId: 'deepseek:deepseek-flash', upstreamModel: 'deepseek-flash',
  parameters: { temperature: 0.2, executionMaxTokens: 4096 } };
const input = enhancementInput(sample, protocol);
assert.equal(Object.hasOwn(input, 'executorMaterials'), false);
assert.equal(JSON.stringify(input).includes(sample.executorMaterials[0].content), false);
assert.equal(JSON.stringify(input).includes(sample.acceptance[0]), false);
const suffix = executionMaterialSuffix(sample);
const requests = ['ORIGINAL_PROMPT', 'BASELINE_PROMPT', 'CANDIDATE_PROMPT'].map(prompt => executionRequest(sample, prompt, protocol));
for (let index = 0; index < requests.length; index++) {
  assert.equal(requests[index].messages[1].content, ['ORIGINAL_PROMPT', 'BASELINE_PROMPT', 'CANDIDATE_PROMPT'][index] + suffix);
  assert.equal(requests[index].messages[1].content.includes(sample.executorMaterials[0].content), true);
  assert.equal(JSON.stringify(requests[index]).includes(sample.acceptance[0]), false);
  assert.deepEqual({ ...requests[index], messages: requests[index].messages.slice(0, 1) },
    { ...requests[0], messages: requests[0].messages.slice(0, 1) });
}
assert.deepEqual(reviewCaseInputs(sample).executorMaterials, sample.executorMaterials);
assert.deepEqual(reviewCaseInputs(sample).acceptance, sample.acceptance);
console.log(JSON.stringify({ event: 'EVALUATION_INFORMATION_BOUNDARY_CHECKED_OFFLINE',
  enhancementSeesExecutorMaterials: false, allThreeExecutorsSeeIdenticalMaterials: true,
  acceptanceOnlyInReview: true, modelCalls: 0 }));
