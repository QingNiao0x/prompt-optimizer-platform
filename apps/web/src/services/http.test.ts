import { AxiosError } from 'axios';
import { describe, expect, it } from 'vitest';

import { getApiErrorMessage, getApiErrorRequestId } from './http';

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
