import { describe, expect, it } from 'vitest';

import { buildOptimizationRequest } from './optimizationRequest';

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
});
