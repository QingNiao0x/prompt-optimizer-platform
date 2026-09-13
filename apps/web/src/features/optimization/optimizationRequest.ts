import type {
  ContextFileInput,
  OptimizationRequest,
  TemplateCode,
} from '@/types/api';

export interface OptimizationDraft {
  rawPrompt: string;
  customDescription: string;
  files: ContextFileInput[];
  templateCode: TemplateCode;
  includePermissionBoundaries: boolean;
  includeExamples: boolean;
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
});
