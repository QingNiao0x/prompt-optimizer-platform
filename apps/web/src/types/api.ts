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
  /** 管理员维护的版本名称；界面通过 formatModelVersion 显示约定短名，id 只用于请求路由。 */
  displayName: string;
  provider: string;
  defaultModel: boolean;
}

export interface AdminModel {
  id: string;
  publicId: string;
  routeKey: string;
  upstreamModel: string;
  /** 用户可见的模型版本，不是供应商调用 ID。 */
  displayName: string;
  enabled: boolean;
  defaultModel: boolean;
  sortOrder: number;
}

export interface ModelRoute {
  key: string;
  providerName: string;
}

export type AnalyticsRange = 'TODAY' | 'YESTERDAY' | 'THIS_WEEK' | 'THIS_MONTH' | 'LAST_MONTH' | 'CUSTOM';
export type AnalyticsRankingPeriod = 'DAY' | 'WEEK' | 'MONTH';
export type AnalyticsEventType =
  | 'LOGIN'
  | 'LOGOUT'
  | 'APP_VISIT'
  | 'OPTIMIZATION_SUBMITTED'
  | 'PLAN_CREATED'
  | 'CONTEXT_PREPARED'
  | 'CONTEXT_ANALYZED'
  | 'RESULT_EXPORTED'
  | 'RECHARGE_PAID'
  | 'ADMIN_MODEL_CHANGED';

export type AnalyticsClientEventType = Extract<AnalyticsEventType, 'APP_VISIT' | 'RESULT_EXPORTED'>;

/** 浏览器事件固定元数据；账号由服务端身份决定，expectedUserId 只阻止切换账号时错误重放。 */
export interface AnalyticsClientEvent {
  eventId: string;
  eventType: AnalyticsClientEventType;
  occurredAt: string;
  expectedUserId: string;
  expectedLoginSessionId: string | null;
}

/** 独立审计关联号不是认证 Session ID，不能用于恢复或冒用登录。 */
export interface AnalyticsClientContext {
  userId: string;
  loginSessionId: string | null;
}

/** 当前 API 实例的审计投递健康度，不包含事件正文或用户标识。 */
export interface AnalyticsDeliveryStatus {
  status: 'HEALTHY' | 'DEGRADED' | 'UNAVAILABLE';
  scope: 'INSTANCE';
  healthy: boolean;
  initialized: boolean;
  journalAvailable: boolean;
  databaseAvailable: boolean;
  pendingEvents: number;
  oldestPendingAt: string | null;
  oldestPendingAgeSeconds: number;
  databaseFailures: number;
  journalFailures: number;
  corruptFiles: number;
  deliveredEvents: number;
  lastFailureAt: string | null;
  lastDeliveredAt: string | null;
  backlogAlert: boolean;
  pendingAlertThreshold: number;
  oldestPendingAlertSeconds: number;
}

export interface AnalyticsPeriodView {
  fromDate: string;
  toDateInclusive: string;
  fromInclusive: string;
  toExclusive: string;
  zoneId: string;
}

export interface AnalyticsDailyMetric {
  date: string;
  accessCount: number;
  uniqueVisitors: number;
  activeUsers: number;
  actualUsers: number;
  newAccounts: number;
}

export interface AnalyticsHourlyMetric {
  hour: number;
  operationCount: number;
}

export interface AnalyticsMonthlyMetric {
  month: string;
  operationCount: number;
}

export interface AnalyticsDeviceMetric {
  deviceType: 'MOBILE' | 'TABLET' | 'DESKTOP' | 'UNKNOWN' | string;
  loginCount: number;
  uniqueUsers: number;
}

export interface AnalyticsUserRank {
  userId: string;
  displayName: string;
  operationCount: number;
  loginCount: number;
  activeDays: number;
}

export interface AnalyticsRechargeMetric {
  date: string;
  planCode: string;
  planName: string;
  paidCount: number;
  amountMinor: number;
  currency: string;
}

export interface AnalyticsDashboard {
  period: AnalyticsPeriodView;
  registeredAccountCount: number;
  newAccountCount: number;
  actualUserCount: number;
  accessCount: number;
  uniqueVisitorCount: number;
  activeUserCount: number;
  averageDailyActiveUsers: number;
  dailyMetrics: AnalyticsDailyMetric[];
  hourlyUsage: AnalyticsHourlyMetric[];
  monthlyUsage: AnalyticsMonthlyMetric[];
  deviceDistribution: AnalyticsDeviceMetric[];
  rechargeByDay: AnalyticsRechargeMetric[];
  rechargeStatisticsAvailable: boolean;
}

export interface AnalyticsRanking {
  period: AnalyticsPeriodView;
  items: AnalyticsUserRank[];
}

export interface AnalyticsOperationLog {
  eventId: string;
  userId: string;
  displayName: string;
  eventType: AnalyticsEventType;
  occurredAt: string;
  clientIp: string | null;
  country: string | null;
  province: string | null;
  city: string | null;
  loginCountry: string | null;
  loginProvince: string | null;
  loginCity: string | null;
  deviceType: string;
}

export interface AnalyticsOperationLogPage {
  records: AnalyticsOperationLog[];
  total: number;
  size: number;
  current: number;
  pages: number;
}

/** 多个账号条件同时满足；邮箱和名称按不区分大小写的字面关键词匹配。 */
export interface AnalyticsAccountFilters {
  userId?: string;
  email?: string;
  displayName?: string;
}

export interface AnalyticsDashboardQuery extends AnalyticsAccountFilters {
  range: AnalyticsRange;
  fromDate?: string;
  toDate?: string;
}

export interface AnalyticsOperationQuery extends AnalyticsAccountFilters {
  fromDate: string;
  toDate: string;
  eventType?: AnalyticsEventType;
  current: number;
  size: number;
}

export type AdminModelChange = Omit<AdminModel, 'id' | 'publicId'>;

export interface LoginPayload {
  identifier: string;
  password: string;
  captcha: string;
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
  /** 推荐依据；旧会话可缺省，推荐仍须用户主动确认。 */
  recommendationReason?: string;
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
  /** 保留原调用标识用于追踪，不将其当作具体版本。 */
  model: string;
  mock: boolean;
  /** 调用时的原始版本快照；展示短名不改写此值，旧响应缺失时不按当前目录倒填。 */
  modelVersion?: string;
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
  /** 只读溯源证据；旧记录默认缺省，展开内容不替代正文中的业务规则和未决条件。 */
  evidenceCards?: PlanningFactEvidence[];
}

export interface PlanningFactEvidence {
  id: string;
  category: string;
  origin: string;
  sourcePath: string;
  evidence: string;
}

export interface OptimizationHistorySummary {
  id: string;
  templateCode: TemplateCode;
  rawPromptPreview: string;
  providerName: string;
  modelName: string;
  /** 保存本条优化记录时的版本名称，历史记录可能未保存。 */
  modelVersion?: string;
  mock: boolean;
  latencyMs: number | null;
  createdAt: string;
}

export interface OptimizationHistoryPage {
  records: OptimizationHistorySummary[];
  total: number;
  size: number;
  current: number;
  pages: number;
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
  /** 保存本条优化记录时的版本名称，历史记录可能未保存。 */
  modelVersion?: string;
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

