export interface ApiResponse<T> {
  requestId: string;
  data: T;
}

export interface ApiErrorPayload {
  requestId?: string;
  error?: {
    code?: string;
    message?: string;
    retryable?: boolean;
    details?: Record<string, unknown>;
  };
}

export interface ContextFileInput {
  path: string;
  content: string;
  language: string;
  /** 大型文档完成分片解析后生成的临时索引编号。 */
  documentId?: string;
  /** 原始文件字节数，仅用于展示本次发送范围，不包含文件正文。 */
  sizeBytes?: number;
}

export interface ContextAnalysisRequest {
  customDescription: string;
  files: ContextFileInput[];
}

export interface TechnologyStackItem {
  name: string;
  source: string;
  confidence: number;
}

export interface DependencyItem {
  ecosystem: string;
  name: string;
  version: string;
  source: string;
}

export interface FileSnippet {
  path: string;
  language: string;
  content: string;
  summary: string;
  truncated: boolean;
}

export interface ContextSnapshot {
  customDescription: string;
  technologyStack: TechnologyStackItem[];
  dependencies: DependencyItem[];
  directoryTree: string[];
  fileSnippets: FileSnippet[];
  /** COMPLETE 表示所有文件完成解析；PARTIAL/FAILED 会同时给出原因。 */
  analysisStatus?: 'EMPTY' | 'COMPLETE' | 'PARTIAL' | 'FAILED';
  fileCoverage?: FileAnalysisCoverage[];
  warnings: string[];
  redactions: string[];
  analysisVersion: string;
}

export interface FileAnalysisCoverage {
  path: string;
  extractionStatus: 'COMPLETE' | 'PARTIAL' | 'FAILED';
  sourceBytes: number;
  extractedCharacters: number;
  indexedChunks: number;
  selectedChunks: number;
  selectedCharacters: number;
  contextLimited: boolean;
  message: string;
}

export type DocumentProcessingPhase =
  | 'UPLOADING'
  | 'QUEUED'
  | 'EXTRACTING'
  | 'INDEXING'
  | 'SUMMARIZING'
  | 'READY'
  | 'PARTIAL'
  | 'FAILED'
  | 'CANCELLED';

export interface DocumentUploadStatus {
  documentId: string;
  path: string;
  language: string;
  phase: DocumentProcessingPhase;
  fileSizeBytes: number;
  uploadedBytes: number;
  progressPercent: number;
  extractedCharacters: number;
  chunkCount: number;
  summary: string;
  warnings: string[];
  errorMessage: string;
  expiresAt: string;
  chunkSizeBytes: number;
}

export type TemplateCode =
  | 'AUTO'
  | 'FEATURE_DEVELOPMENT'
  | 'BUG_FIX'
  | 'REFACTORING'
  | 'TESTING';

export interface EnhancementOptions {
  templateCode: TemplateCode;
  includeConversationHistory: boolean;
  includePermissionBoundaries: boolean;
  includeExamples: boolean;
}

export interface OptimizationRequest {
  rawPrompt: string;
  context: ContextAnalysisRequest;
  enhancement: EnhancementOptions;
  conversationHistory: [];
  permissionPolicy: {
    protectedPaths: string[];
    requireConfirmationFor: string[];
  };
}

export type PromptSectionType =
  | 'BACKGROUND'
  | 'TASK'
  | 'OUTPUT'
  | 'CONSTRAINTS'
  | 'CLARIFICATIONS'
  | 'ACCEPTANCE'
  | 'EXAMPLES';

export interface PromptSection {
  type: PromptSectionType;
  title: string;
  content: string;
}

export interface ProviderMetadata {
  provider: string;
  model: string;
  mock: boolean;
}

export interface OptimizationResult {
  optimizedPrompt: string;
  sections: PromptSection[];
  contextReport: ContextSnapshot;
  ambiguities: string[];
  appliedConstraints: string[];
  templateCode: TemplateCode;
  provider: ProviderMetadata;
  latencyMs: number;
}

export interface OptimizationHistorySummary {
  id: string;
  templateCode: TemplateCode;
  rawPromptPreview: string;
  providerName: string;
  modelName: string;
  mock: boolean;
  latencyMs: number | null;
  createdAt: string;
}

export interface OptimizationHistoryPage {
  items: OptimizationHistorySummary[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

export interface OptimizationHistoryDetail {
  id: string;
  rawPrompt: string;
  optimizedPrompt: string;
  sections: PromptSection[];
  contextSummary: Record<string, unknown>;
  ambiguities: string[];
  appliedConstraints: string[];
  templateCode: TemplateCode;
  providerName: string;
  modelName: string;
  mock: boolean;
  latencyMs: number | null;
  createdAt: string;
  includePermissionBoundaries: boolean;
  includeExamples: boolean;
  conversationHistory: [];
  permissionPolicy: Record<string, unknown>;
}

export interface ReoptimizationResult {
  recordId: string;
  result: OptimizationResult;
}

export type ProviderType = 'OPENAI' | 'ANTHROPIC' | 'DEEPSEEK' | 'CUSTOM';

export interface ProviderConfigSummary {
  id: string;
  providerType: ProviderType;
  displayName: string;
  endpointUrl: string;
  modelName: string;
  apiKeyLast4: string;
  parameters: Record<string, unknown>;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface ProviderConfigSavePayload {
  providerType: ProviderType;
  displayName: string;
  endpointUrl: string;
  modelName: string;
  apiKey: string;
  parameters: Record<string, unknown>;
  enabled: boolean;
}

export interface ProviderConfigUpdatePayload {
  displayName?: string;
  endpointUrl?: string;
  modelName?: string;
  apiKey?: string;
  parameters?: Record<string, unknown>;
  enabled?: boolean;
}
