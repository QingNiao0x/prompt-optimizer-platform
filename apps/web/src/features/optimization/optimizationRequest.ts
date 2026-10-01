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
  modelId?: string;
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
  modelId: draft.modelId || null,
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
  modelId?: string,
): OptimizationPlanRequest => ({
  rawPrompt: rawPrompt.trim(),
  modelId: modelId || null,
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
 * 二次召回以原始需求和已选答案为准；问题中的未选候选项不参与检索。
 */
export const buildRefinedContextQuery = (
  rawPrompt: string,
  confirmation: PlanConfirmation,
): string => [
  rawPrompt.trim(),
  ...confirmation.answers
    .filter((answer) => !/^(暂不确定|尚未确定|待定|不知道|不清楚|unknown|tbd)[。.!！]?$|未决定|稍后确认/i.test(answer.answer.trim()))
    .map((answer) => {
      const id = answer.questionId.toLowerCase();
      const topic = answer.question.match(/资料对“([^”]{2,40})”/)?.[1]
        ?? answer.question.match(/(研究地区|地区范围|数据来源|数据格式|输出格式|交付格式|交付内容|交付物|输出内容|输出方式|分析工具|分析方法|验收标准|统计口径|审批阈值|认证方式|登录方式|技术选型|技术栈|版本|范围|时限|规则|格式|地区|工具|口径|阈值)/)?.[0]
        ?? (id.includes('region') ? '地区' : id.includes('tool') ? '工具'
          : id.includes('auth') || id.includes('login') ? '认证方式' : '本次选择');
      return `${topic}：${answer.answer.trim()}`;
    }),
].filter(Boolean).join('\n');
