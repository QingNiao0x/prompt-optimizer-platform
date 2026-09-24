import type {
  ContextFileInput,
  OptimizationPlanRequest,
  OptimizationRequest,
  PlanConfirmation,
  PlanningContextReference,
  PlanningContextRequest,
  TemplateCode,
} from '@/types/api';

export interface OptimizationDraft {
  rawPrompt: string;
  customDescription: string;
  files: ContextFileInput[];
  templateCode: TemplateCode;
  includePermissionBoundaries: boolean;
  includeExamples: boolean;
  planConfirmation?: PlanConfirmation;
}

/**
 * 在发送前集中规范化用户输入，确保页面组件不会各自拼装不同版本的 API 请求。
 */
export const buildOptimizationRequest = (
  draft: OptimizationDraft,
): OptimizationRequest => ({
  rawPrompt: draft.rawPrompt.trim(),
  context: {
    customDescription: draft.customDescription.trim(),
    files: draft.files.map((file) => ({
      path: file.path.trim(),
      content: file.content,
      language: file.language.trim(),
      ...(file.documentId ? { documentId: file.documentId } : {}),
      ...(file.sizeBytes !== undefined ? { sizeBytes: file.sizeBytes } : {}),
    })),
  },
  enhancement: {
    templateCode: draft.templateCode,
    includeConversationHistory: false,
    includePermissionBoundaries: draft.includePermissionBoundaries,
    includeExamples: draft.includeExamples,
  },
  conversationHistory: [],
  permissionPolicy: {
    protectedPaths: [],
    requireConfirmationFor: [],
  },
  planConfirmation: draft.planConfirmation ?? null,
});

/**
 * 计划阶段只发送需求和用户填写的背景，不携带项目文件正文。
 */
export const buildOptimizationPlanRequest = (
  rawPrompt: string,
  contextDescription: string,
  planningContext?: PlanningContextReference,
): OptimizationPlanRequest => ({
  rawPrompt: rawPrompt.trim(),
  contextDescription: contextDescription.trim(),
  conversationHistory: [],
  planningContext: planningContext ?? null,
});

export const buildPlanningContextRequest = (
  rawPrompt: string,
  customDescription: string,
  files: ContextFileInput[],
): PlanningContextRequest => ({
  rawPrompt: rawPrompt.trim(),
  context: {
    customDescription: customDescription.trim(),
    files,
  },
  permissionPolicy: {
    protectedPaths: [],
    requireConfirmationFor: [],
  },
});

/**
 * 用户确认的业务事实必须参与第二次文件召回，否则 Plan Mode 不会改善最终上下文精度。
 */
export const buildRefinedContextQuery = (
  rawPrompt: string,
  confirmation: PlanConfirmation,
): string => [
  rawPrompt.trim(),
  ...confirmation.answers.map((answer) => `${answer.question}\n${answer.answer}`),
].filter(Boolean).join('\n');
