import { describe, expect, it } from 'vitest';

import { buildOptimizationPlanRequest, buildOptimizationRequest } from './optimizationRequest';

describe('buildOptimizationRequest', () => {
  it('should normalize editable text without changing source content', () => {
    const request = buildOptimizationRequest({
      rawPrompt: '  增加登录功能  ',
      customDescription: '  Spring Boot 用户服务  ',
      files: [
        {
          path: '  src/AuthController.java  ',
          language: '  java  ',
          content: 'class AuthController {\n  // 保留原始缩进\n}',
        },
      ],
      templateCode: 'FEATURE_DEVELOPMENT',
      includePermissionBoundaries: true,
      includeExamples: false,
    });

    expect(request.rawPrompt).toBe('增加登录功能');
    expect(request.context.customDescription).toBe('Spring Boot 用户服务');
    expect(request.context.files[0]).toEqual({
      path: 'src/AuthController.java',
      language: 'java',
      content: 'class AuthController {\n  // 保留原始缩进\n}',
    });
    expect(request.enhancement).toEqual({
      templateCode: 'FEATURE_DEVELOPMENT',
      includeConversationHistory: false,
      includePermissionBoundaries: true,
      includeExamples: false,
    });
  });

  it('should send empty conversation and permission collections for the current MVP', () => {
    const request = buildOptimizationRequest({
      rawPrompt: '修复问题',
      customDescription: '',
      files: [],
      templateCode: 'AUTO',
      includePermissionBoundaries: false,
      includeExamples: true,
    });

    expect(request.conversationHistory).toEqual([]);
    expect(request.permissionPolicy.protectedPaths).toEqual([]);
    expect(request.permissionPolicy.requireConfirmationFor).toEqual([]);
  });

  it('should preserve a temporary document reference without embedding its source content', () => {
    const request = buildOptimizationRequest({
      rawPrompt: '根据论文改进摘要',
      customDescription: '',
      files: [{
        path: '论文.docx',
        language: 'docx',
        content: '',
        documentId: 'document-123',
        sizeBytes: 12_345_678,
      }],
      templateCode: 'AUTO',
      includePermissionBoundaries: false,
      includeExamples: false,
    });

    expect(request.context.files[0]).toEqual({
      path: '论文.docx',
      language: 'docx',
      content: '',
      documentId: 'document-123',
      sizeBytes: 12_345_678,
    });
  });

  it('should send confirmed plan answers with the final request', () => {
    const request = buildOptimizationRequest({
      rawPrompt: '分析某地区死亡率',
      customDescription: '公共卫生研究',
      files: [],
      templateCode: 'RESEARCH_ANALYSIS',
      includePermissionBoundaries: true,
      includeExamples: false,
      planConfirmation: {
        answers: [{
          questionId: 'research-region',
          question: '这项研究具体覆盖哪个地区？',
          answer: '广东省',
        }],
      },
    });

    expect(request.planConfirmation?.answers[0]?.answer).toBe('广东省');
  });

  it('should keep project files out of the planning request', () => {
    expect(buildOptimizationPlanRequest('  分析死亡率  ', '  公共卫生研究  ')).toEqual({
      rawPrompt: '分析死亡率',
      contextDescription: '公共卫生研究',
      conversationHistory: [],
    });
  });
});
