export type ImagePromptMode = 'REFERENCE_RECONSTRUCTION' | 'CUSTOM_CREATION';
export type ImageReferenceRole = 'GENERAL' | 'SUBJECT' | 'STYLE' | 'LAYOUT';
export type ImagePromptOutputType = 'IMAGE' | 'VIDEO' | 'PPT';
export type ImagePromptMaterialTrack = 'REFERENCE' | 'REPLACEMENT';
export interface ImagePromptMask { x: number; y: number; width: number; height: number }
/** 素材与成品类型独立；只把用户选中的页/帧及实际遮挡提交给模型。 */
export interface ImagePromptMaterialRef {
  assetId: string; track: ImagePromptMaterialTrack; role: ImageReferenceRole;
  units: { unitId: string; masks: ImagePromptMask[] }[];
}
export interface ImagePromptMediaUnit {
  id: string; page: number | null; timestampMs: number | null; width: number; height: number; text: string; previewUrl: string;
}
export interface ImagePromptMediaAsset {
  id: string; name: string; kind: 'IMAGE' | 'VIDEO' | 'PPTX';
  status: 'PROCESSING' | 'READY' | 'PARTIAL' | 'FAILED' | 'CANCELLED'; units: ImagePromptMediaUnit[];
  warnings: string[]; expiresAt: string; totalUnits: number; durationMs: number | null;
}
export interface ImagePromptMediaCapabilities {
  maxAssets: number; maxFileBytes: number; maxSelectedUnits: number; maxVideoDurationMs: number; maxSlides: number; videoAvailable: boolean;
}
export interface ImagePromptMaterialSource {
  assetId: string; name: string; kind: 'IMAGE' | 'VIDEO' | 'PPTX'; track: ImagePromptMaterialTrack; role: ImageReferenceRole;
  units: { id: string; page?: number | null; timestampMs?: number | null; text: string; masks: ImagePromptMask[] }[]; warnings: string[];
}
/** 关系来自用户明确编辑；图片观察或模型建议不能自动升级为用户确认。 */
export interface ImagePromptReplacementRelation {
  id: string; operation: 'PRESERVE' | 'REPLACE' | 'REMOVE' | 'FUSE'; referenceAssetId?: string;
  referenceObject: string; referenceUnitId?: string; replacementAssetId?: string; replacementUnitId?: string; replacementObject: string;
  instruction: string; attributes: string[]; scope: string;
}
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
  outputType?: ImagePromptOutputType; materials?: ImagePromptMaterialRef[]; relations?: ImagePromptReplacementRelation[];
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
  materialSources?: ImagePromptMaterialSource[];
}
export interface ImagePromptDocument {
  operationId: string; recordId: string | null; mode: ImagePromptMode; optimizedPrompt: string;
  analysis: ImageObservation[]; warnings: string[]; input: ImagePromptInput; sources: ImagePromptSource[];
  questions: ImagePromptQuestion[]; answers: ImagePromptAnswer[]; modelId: string; modelName: string;
  mock: boolean; editRevision: number; historySaved: boolean; persistentHistory: boolean; createdAt: string;
  materialSources?: ImagePromptMaterialSource[];
  /** 独立持久化的来源风险；旧版服务/历史可以缺省，界面仍使用 warnings 展示本次合并提示。 */
  evidenceWarnings?: string[];
}
export interface ImagePromptHistoryPage {
  records: ImagePromptDocument[]; total: number; size: number; current: number; pages: number;
}
export interface ImagePromptOperation {
  status: 'RUNNING' | 'COMPLETED' | 'FAILED' | 'UNKNOWN'; plan: ImagePromptPlan | null;
  document: ImagePromptDocument | null; message: string; startedAt: string;
}
