import { AxiosError } from 'axios';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { getApiErrorMessage, getApiErrorRequestId, httpClient } from './http';

afterEach(() => vi.unstubAllGlobals());

describe('authentication boundary', () => {
  it.each([
    ['/api/v1/trial/text-polish', false],
    ['/api/v2/optimization-history', true],
  ])('keeps trial failures local and protects formal account paths: %s', async (url, redirect) => {
    const replace = vi.fn();
    vi.stubGlobal('window', { location: { pathname: '/text-polish', replace } });
    // Adapter 直接返回受控401，不建立网络连接，也不自动重放失败提交。
    await expect(httpClient.get(url, { adapter: async config => {
      throw new AxiosError('身份不可用', 'ERR_BAD_REQUEST', config, undefined,
        { status: 401, statusText: 'Unauthorized', headers: {}, config, data: {} });
    } })).rejects.toBeInstanceOf(AxiosError);
    if (redirect) expect(replace).toHaveBeenCalledWith('/login?expired=1');
    else expect(replace).not.toHaveBeenCalled();
  });
});

describe('getApiErrorMessage', () => {
  it('should prefer the safe message returned by the backend', () => {
    const error = new AxiosError('upstream details');
    error.response = {
      data: {
        requestId: 'req-test',
        error: {
          code: 'PROVIDER_RATE_LIMITED',
          message: '模型服务当前请求繁忙，请稍后重试。',
          retryable: true,
          details: {},
        },
      },
      status: 503,
      statusText: 'Service Unavailable',
      headers: {},
      config: error.config!,
    };

    expect(getApiErrorMessage(error)).toBe('模型服务当前请求繁忙，请稍后重试。');
    expect(getApiErrorRequestId(error)).toBe('req-test');
  });

  it('should provide an actionable message for network failures', () => {
    const error = new AxiosError('Network Error', 'ERR_NETWORK');

    expect(getApiErrorMessage(error)).toBe('无法连接后端服务，请确认 API 服务已经启动。');
    expect(getApiErrorRequestId(error)).toBe('');
  });
});
