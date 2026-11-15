export type PolishStyle = 'FORMAL' | 'NATURAL' | 'CONVERSATIONAL';
export type PolishDepth = 'LIGHT' | 'RESTRUCTURE';
export type PolishSource = 'RAW' | 'ORIGINAL' | 'CURRENT';
export interface PolishInput {
  sourceText: string; style: PolishStyle; depth: PolishDepth; audience: string;
  requirements: string; lockedTexts: string[]; modelId: string;
}
export interface PolishCheck { code: string; message: string; before: string; after: string }
/** 服务端实际执行快照。旧历史可能缺失，null字段表示未配置或未知。 */
export interface PolishExecutionMetadata {
  strategyVersion: string; skillVersion: string | null; resourceHash: string | null;
  repairInstructionHash: string | null; routeKey: string; upstreamModel: string; attemptCount: number;
  parameters: {
    maxTokens: number | null; maxCompletionTokens: number | null; temperature: number | null;
    stream: boolean; responseFormat: string | null; thinking: string | null;
  } | null;
}
export interface PolishDocument {
  id: string; input: PolishInput; generatedText: string; polishedText: string; sourceRecordId: string | null;
  sourceMode: PolishSource; modelId: string; modelName: string; mock: boolean; editRevision: number;
  executionMetadata?: PolishExecutionMetadata | null;
  checks: PolishCheck[]; historySaved: boolean; persistentHistory: boolean; createdAt: string;
}
export interface PolishModel { id: string; displayName: string; defaultModel: boolean; mock: boolean }
export interface PolishCapabilities { models: PolishModel[]; maxInputCharacters: number; maxOutputCharacters: number; persistentHistory: boolean }
export interface PolishPolicy { enabledModelIds: string[]; defaultModelId: string | null }
export interface PolishHistory { records: PolishDocument[]; current: number; size: number; total: number; pages: number }
export interface PolishOperation { status: 'RUNNING' | 'COMPLETED' | 'FAILED' | 'UNKNOWN'; document: PolishDocument | null }
export const polishStyles: { value: PolishStyle; label: string; description: string }[] = [
  { value: 'FORMAL', label: '正式清晰', description: '适合报告、商务邮件和正式说明。' },
  { value: 'NATURAL', label: '自然流畅', description: '默认，适合一般文章和日常沟通。' },
  { value: 'CONVERSATIONAL', label: '轻松交流', description: '适合社交内容和面向大众的介绍。' },
];
