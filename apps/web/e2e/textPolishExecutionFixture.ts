import type { PolishExecutionMetadata } from '../src/types/textPolish';

/** 受控服务端快照，供工作台与历史详情核对展示及纯正文复制。 */
export const executionFixture: PolishExecutionMetadata = {
  strategyVersion: 'text-polish-paragraph-v2', skillVersion: 'text-polish-paragraph-v2',
  resourceHash: 'a'.repeat(64), repairInstructionHash: null, routeKey: 'fixture-route',
  upstreamModel: 'fixture-model', attemptCount: 1,
  parameters: { maxTokens: 8000, maxCompletionTokens: null, temperature: 0.2, stream: false, responseFormat: 'json_object', thinking: 'disabled' },
  selection: { selectorVersion: 'source-language-v1', language: 'EN', profile: 'PARAGRAPH_EDITING', reason: 'SOURCE_LANGUAGE_EVIDENCE' },
  resources: [{ id: 'text-polish-paragraph-v2', version: 'text-polish-paragraph-v2', sha256: 'b'.repeat(64) },
    { id: 'text-polish-paragraph-v2-en-natural', version: 'text-polish-paragraph-v2', sha256: 'c'.repeat(64) }],
};
