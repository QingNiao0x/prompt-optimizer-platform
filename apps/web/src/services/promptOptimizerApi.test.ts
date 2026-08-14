import { describe, expect, it, vi } from 'vitest';

vi.mock('./http', () => ({
  httpClient: {
    post: vi.fn(),
  },
}));

import { httpClient } from './http';
import { optimizePrompt } from './promptOptimizerApi';

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
