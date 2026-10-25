export type PolishStyle = 'FORMAL' | 'NATURAL' | 'CONVERSATIONAL';
export type PolishDepth = 'LIGHT' | 'RESTRUCTURE';
export type PolishSource = 'RAW' | 'ORIGINAL' | 'CURRENT';
export interface PolishInput {
  sourceText: string; style: PolishStyle; depth: PolishDepth; audience: string;
  requirements: string; lockedTexts: string[]; modelId: string;
}
export interface PolishCheck { code: string; message: string; before: string; after: string }
export interface PolishDocument {
  id: string; input: PolishInput; generatedText: string; polishedText: string; sourceRecordId: string | null;
  sourceMode: PolishSource; modelId: string; modelName: string; mock: boolean; editRevision: number;
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
