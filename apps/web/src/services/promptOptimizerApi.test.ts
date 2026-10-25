import { createPinia, setActivePinia } from 'pinia';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '@/stores/auth';

vi.mock('./http', () => ({
  httpClient: {
    get: vi.fn(),
    post: vi.fn(),
  },
}));

import { httpClient } from './http';
import {
  createOptimizationPlan,
  listHistory,
  optimizePrompt,
  preparePlanningContext,
} from './promptOptimizerApi';

beforeEach(() => {
  vi.resetAllMocks();
  setActivePinia(createPinia());
  useAuthStore().user = { userId: 'fixture-user', tenantId: 'fixture-tenant', workspaceId: 'fixture-workspace',
    displayName: '接口测试用户', email: 'fixture@example.test', platformAdmin: false };
});

describe('listHistory', () => {
  it('should send keyword and date range filters with the paged request', async () => {
    vi.mocked(httpClient.get).mockResolvedValue({
      data: { requestId: 'history-request', data: { items: [], totalItems: 0 } },
    } as never);

    await listHistory(1, 10, {
      keyword: '  AI 职业  ',
      dateRange: ['2026-09-01', '2026-09-24'],
    });

    expect(httpClient.get).toHaveBeenCalledWith(
      '/api/v2/optimization-history',
      expect.objectContaining({
        params: {
          current: 1,
          size: 10,
          keyword: 'AI 职业',
          dateRange: '2026-09-01,2026-09-24',
        },
      }),
    );
  });
});

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
      },
      conversationHistory: [],
      permissionPolicy: {
        protectedPaths: [],
        requireConfirmationFor: [],
      },
    });

    expect(httpClient.post).toHaveBeenCalledWith(
      '/api/v2/optimizations',
      expect.any(Object),
      expect.objectContaining({ headers: { 'X-Trial-Request-Id': expect.any(String) } }),
    );
  });

  it('routes only guest enhancement to the trial endpoint with a unique operation id', async () => {
    useAuthStore().user = undefined;
    vi.mocked(httpClient.post).mockResolvedValue({ data: { requestId: 'guest', data: {} } });
    const input = { rawPrompt: '请写一封会议通知邮件。', context: { customDescription: '', files: [] },
      enhancement: { templateCode: 'AUTO' as const, includeConversationHistory: false, includePermissionBoundaries: true },
      conversationHistory: [], permissionPolicy: { protectedPaths: [], requireConfirmationFor: [] } };
    await optimizePrompt(input);
    await optimizePrompt(input);
    const calls = vi.mocked(httpClient.post).mock.calls;
    expect(calls[0]?.[0]).toBe('/api/v1/trial/optimizations');
    const first = calls[0]?.[2]?.headers?.['X-Trial-Request-Id'];
    const second = calls[1]?.[2]?.headers?.['X-Trial-Request-Id'];
    expect(first).toMatch(/^[0-9a-f-]{36}$/);
    expect(first).not.toBe(second);
    vi.mocked(httpClient.get).mockResolvedValue({ data: { data: { items: [], totalItems: 0 } } });
    await listHistory(1, 10);
    expect(httpClient.get).toHaveBeenCalledWith('/api/v2/optimization-history', expect.any(Object));
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
      '/api/v2/optimizations/plan',
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
      '/api/v2/context/planning',
      expect.objectContaining({ rawPrompt: '给用户模块添加登录功能' }),
    );
  });
});
