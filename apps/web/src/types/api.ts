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

export interface AuthenticatedUser {
  userId: string;
  tenantId: string;
  workspaceId: string;
  email: string;
  displayName: string;
  platformAdmin: boolean;
}

/** 平台已发布给终端用户的模型，不包含端点或密钥。 */
export interface AvailableModel {
  id: string;
  displayName: string;
  provider: string;
  defaultModel: boolean;
}

export interface AdminModel {
  id: string;
  publicId: string;
  routeKey: string;
  upstreamModel: string;
  displayName: string;
  enabled: boolean;
  defaultModel: boolean;
  sortOrder: number;
}

export interface ModelRoute {
  key: string;
  providerName: string;
}

export type AdminModelChange = Omit<AdminModel, 'id' | 'publicId'>;

export interface LoginPayload {
  identifier: string;
  password: string;
}

export interface EmailRegistrationCodePayload {
  email: string;
}

export interface EmailRegistrationCodeStatus {
  resendAfterSeconds: number;
  expiresInSeconds: number;
}

export interface EmailRegistrationPayload {
  email: string;
  verificationCode: string;
  password: string;
}

export interface CsrfTokenMetadata {
  headerName: string;
  parameterName: string;
  token: string;
}

export interface PlanningContextReference {
  contextId: string;
  version: string;
}

export interface PlanningContextDigest {
  description: string;
  technologies: string[];
  dependencies: string[];
  directoryOverview: string[];
  fileSummaries: string[];
  analysisStatus: string;
  analyzedFileCount: number;
  warnings: string[];
}

export interface PlanningContextPreparation extends PlanningContextReference {
  digest: PlanningContextDigest;
  contextReport: ContextSnapshot;
  expiresAt: string;
  latencyMs: number;
}

export interface PlanningContextRequest {
  rawPrompt: string;
  context: ContextAnalysisRequest;
  permissionPolicy: {
    protectedPaths: string[];
    requireConfirmationFor: string[];
  };
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
  | 'GENERAL'
  | 'RESEARCH_ANALYSIS'
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

export interface ConversationMessage {
  role: 'user' | 'assistant';
  content: string;
}

export interface OptimizationRequest {
  rawPrompt: string;
  modelId?: string | null;
  context: ContextAnalysisRequest;
  enhancement: EnhancementOptions;
  conversationHistory: ConversationMessage[];
  permissionPolicy: {
    protectedPaths: string[];
    requireConfirmationFor: string[];
  };
  planConfirmation?: PlanConfirmation | null;
}

export type PlanQuestionType = 'SINGLE_CHOICE' | 'MULTIPLE_CHOICE' | 'FREE_TEXT';

export interface PlanOption {
  id: string;
  label: string;
  description: string;
  answer: string;
  recommended: boolean;
}

export interface PlanQuestion {
  id: string;
  question: string;
  hint: string;
  type: PlanQuestionType;
  options: PlanOption[];
  examples: string[];
  allowCustomAnswer: boolean;
}

export interface OptimizationPlanRequest {
  rawPrompt: string;
  modelId?: string | null;
  contextDescription: string;
  conversationHistory: ConversationMessage[];
  planningContext?: PlanningContextReference | null;
}

export interface OptimizationPlan {
  summary: string;
  questions: PlanQuestion[];
  templateCode: TemplateCode;
  provider: ProviderMetadata;
  latencyMs: number;
  planId?: string | null;
  planningContext?: PlanningContextReference | null;
  expiresAt?: string | null;
}

export interface PlanAnswer {
  questionId: string;
  question: string;
  answer: string;
}

export interface PlanConfirmation {
  planId?: string | null;
  planningContext?: PlanningContextReference | null;
  answers: PlanAnswer[];
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
  warnings?: string[];
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

export interface OptimizationHistoryFilters {
  keyword?: string;
  dateRange?: readonly [string, string] | null;
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
  conversationHistory: ConversationMessage[];
  permissionPolicy: Record<string, unknown>;
}

export interface ReoptimizationResult {
  recordId: string;
  result: OptimizationResult;
}

