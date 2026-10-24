export type ImagePromptMode = 'REFERENCE_RECONSTRUCTION' | 'CUSTOM_CREATION';
export type ImageReferenceRole = 'GENERAL' | 'SUBJECT' | 'STYLE' | 'LAYOUT';
export interface ImagePromptModel {
  id: string; displayName: string; vision: boolean; maxImages: number; evidence: string; mock: boolean;
}
export interface ImagePromptCapabilities {
  models: ImagePromptModel[]; maxImageBytes: number; maxPixels: number; maxEdge: number;
  maxImages: number; assetTtlSeconds: number; persistentHistory: boolean;
}
export interface ImagePromptAsset {
  id: string; name: string; mimeType: string; width: number; height: number; bytes: number;
  hash: string; expiresAt: string; previewUrl: string; processing: string[];
}
export interface ImagePromptSource {
  id: string; name: string; hash: string; role: ImageReferenceRole; width: number; height: number; expiresAt: string;
}
export interface ImagePromptInput {
  mode: ImagePromptMode; modelId: string; images: { assetId: string; role: ImageReferenceRole }[];
  creationIntent: string; exactText: string; context: string; language: 'ZH' | 'EN'; targetTool: string;
}
export interface ImageObservation {
  imageId: string; category: string; description: string; status: 'OBSERVED' | 'INTERPRETATION' | 'UNCERTAIN';
}
export interface ImagePromptQuestion {
  id: string; type: 'SINGLE_CHOICE' | 'MULTIPLE_CHOICE' | 'TEXT'; question: string;
  options: { id: string; label: string }[]; required: boolean;
}
export interface ImagePromptAnswer { questionId: string; optionIds: string[]; customText: string }
export interface ImagePromptPlan {
  id: string; fingerprint: string; summary: string; questions: ImagePromptQuestion[];
  analysis: ImageObservation[]; warnings: string[]; expiresAt: string; mock: boolean;
}
export interface ImagePromptDocument {
  operationId: string; recordId: string | null; mode: ImagePromptMode; optimizedPrompt: string;
  analysis: ImageObservation[]; warnings: string[]; input: ImagePromptInput; sources: ImagePromptSource[];
  questions: ImagePromptQuestion[]; answers: ImagePromptAnswer[]; modelId: string; modelName: string;
  mock: boolean; editRevision: number; historySaved: boolean; persistentHistory: boolean; createdAt: string;
}
export interface ImagePromptHistoryPage {
  records: ImagePromptDocument[]; total: number; size: number; current: number; pages: number;
}
export interface ImagePromptOperation {
  status: 'RUNNING' | 'COMPLETED' | 'FAILED' | 'UNKNOWN'; plan: ImagePromptPlan | null;
  document: ImagePromptDocument | null; message: string; startedAt: string;
}
