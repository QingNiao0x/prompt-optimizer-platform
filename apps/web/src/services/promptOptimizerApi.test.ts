import { describe, expect, it, vi } from 'vitest';

vi.mock('./http', () => ({
  httpClient: {
    post: vi.fn(),
  },
}));

import { httpClient } from './http';
import {
  createOptimizationPlan,
  optimizePrompt,
  preparePlanningContext,
} from './promptOptimizerApi';

describe('optimizePrompt', () => {
  it('should call the backend optimization endpoint without an accidental suffix', async () => {
    const responseData = {
      requestId: 'request-123',
      data: {},
    };
    vi.mocked(httpClient.post).mockResolvedValue({ data: responseData } as never);

    await optimizePrompt({
      rawPrompt: '增加登录功能',
      context: { customDescription: '', files: [] },
      enhancement: {
        templateCode: 'AUTO',
        includeConversationHistory: false,
        includePermissionBoundaries: true,
        includeExamples: false,
      },
      conversationHistory: [],
      permissionPolicy: {
        protectedPaths: [],
        requireConfirmationFor: [],
      },
    });

    expect(httpClient.post).toHaveBeenCalledWith(
      '/api/v1/optimizations',
      expect.any(Object),
    );
  });
});

describe('createOptimizationPlan', () => {
  it('should call the planning endpoint before final optimization', async () => {
    vi.mocked(httpClient.post).mockResolvedValue({
      data: { requestId: 'plan-request', data: { questions: [] } },
    } as never);

    await createOptimizationPlan({
      rawPrompt: '分析某地区心脑血管疾病死亡率',
      contextDescription: '公共卫生研究',
      conversationHistory: [],
    });

    expect(httpClient.post).toHaveBeenCalledWith(
      '/api/v1/optimizations/plan',
      expect.objectContaining({ rawPrompt: '分析某地区心脑血管疾病死亡率' }),
    );
  });
});

describe('preparePlanningContext', () => {
  it('should analyze selected context before creating a context-aware plan', async () => {
    vi.mocked(httpClient.post).mockResolvedValue({
      data: { requestId: 'context-request', data: { contextId: 'context-123' } },
    } as never);

    await preparePlanningContext({
      rawPrompt: '给用户模块添加登录功能',
      context: {
        customDescription: 'Spring Boot 用户服务',
        files: [{ path: 'pom.xml', content: '<project />', language: 'xml' }],
      },
      permissionPolicy: {
        protectedPaths: [],
        requireConfirmationFor: [],
      },
    });

    expect(httpClient.post).toHaveBeenCalledWith(
      '/api/v1/context/planning',
      expect.objectContaining({ rawPrompt: '给用户模块添加登录功能' }),
    );
  });
});
