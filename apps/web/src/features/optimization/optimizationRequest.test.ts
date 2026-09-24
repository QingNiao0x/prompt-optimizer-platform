import { describe, expect, it } from 'vitest';

import {
  buildOptimizationPlanRequest,
  buildOptimizationRequest,
  buildPlanningContextRequest,
  buildRefinedContextQuery,
} from './optimizationRequest';

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

  it('should leave provider and model routing to the platform', () => {
    const request = buildOptimizationRequest({
      rawPrompt: '分析需求',
      customDescription: '',
      files: [],
      templateCode: 'AUTO',
      includePermissionBoundaries: true,
      includeExamples: false,
    });

    expect(request).not.toHaveProperty('model');
    expect(JSON.stringify(request)).not.toMatch(/apiKey|endpointUrl|providerConfig/i);
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
        planId: 'd53d3b67-62b2-4505-89dd-4ca88f837391',
        planningContext: {
          contextId: 'ea9d3453-5bd7-487b-bafb-5ef608dfd895',
          version: `sha256:${'a'.repeat(64)}`,
        },
        answers: [{
          questionId: 'research-region',
          question: '这项研究具体覆盖哪个地区？',
          answer: '广东省',
        }],
      },
    });

    expect(request.planConfirmation?.answers[0]?.answer).toBe('广东省');
    expect(request.planConfirmation?.planId).toBe('d53d3b67-62b2-4505-89dd-4ca88f837391');
  });

  it('should keep project files out of the planning request and pass only its reference', () => {
    const planningContext = {
      contextId: 'ea9d3453-5bd7-487b-bafb-5ef608dfd895',
      version: `sha256:${'b'.repeat(64)}`,
    };
    expect(buildOptimizationPlanRequest('  分析死亡率  ', '  公共卫生研究  ')).toEqual({
      rawPrompt: '分析死亡率',
      contextDescription: '公共卫生研究',
      conversationHistory: [],
      planningContext: null,
    });
    expect(buildOptimizationPlanRequest(
      '分析死亡率',
      '公共卫生研究',
      planningContext,
    )).toEqual({
      rawPrompt: '分析死亡率',
      contextDescription: '公共卫生研究',
      conversationHistory: [],
      planningContext,
    });
  });

  it('should build the planning-context request before asking questions', () => {
    expect(buildPlanningContextRequest(
      '  修复登录问题  ',
      '  Spring Boot 服务  ',
      [{ path: 'pom.xml', content: '<project />', language: 'xml' }],
    )).toEqual({
      rawPrompt: '修复登录问题',
      context: {
        customDescription: 'Spring Boot 服务',
        files: [{ path: 'pom.xml', content: '<project />', language: 'xml' }],
      },
      permissionPolicy: {
        protectedPaths: [],
        requireConfirmationFor: [],
      },
    });
  });

  it('should include confirmed answers in the second context retrieval query', () => {
    expect(buildRefinedContextQuery('分析死亡率', {
      answers: [
        {
          questionId: 'research-region',
          question: '研究地区？',
          answer: '广东省',
        },
        {
          questionId: 'research-tool',
          question: '分析工具？',
          answer: 'R',
        },
      ],
    })).toBe('分析死亡率\n研究地区？\n广东省\n分析工具？\nR');
  });
});
