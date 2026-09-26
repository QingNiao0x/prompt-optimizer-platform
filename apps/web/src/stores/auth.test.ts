import { createPinia, setActivePinia } from 'pinia';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AxiosError, AxiosHeaders } from 'axios';
import { useAuthStore } from './auth';
import { getCurrentUser, initializeCsrf, login, logout, register } from '@/services/authApi';
import type { ApiResponse, AuthenticatedUser } from '@/types/api';

vi.mock('@/services/authApi', () => ({
  getCurrentUser: vi.fn(), initializeCsrf: vi.fn(), login: vi.fn(), logout: vi.fn(), register: vi.fn(),
}));

const response: ApiResponse<AuthenticatedUser> = {
  requestId: 'test', data: {
    userId: 'user-a', tenantId: 'tenant-a', workspaceId: 'workspace-a', email: 'a@example.com', displayName: 'A', platformAdmin: false,
  },
};
const unauthorized = (): AxiosError => new AxiosError('unauthorized', undefined, undefined, undefined, {
  status: 401, statusText: 'Unauthorized', data: {}, headers: {}, config: { headers: new AxiosHeaders() },
});

describe('auth store', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    setActivePinia(createPinia());
    vi.mocked(getCurrentUser).mockResolvedValue(response);
    vi.mocked(login).mockResolvedValue(response);
    vi.mocked(register).mockResolvedValue(response);
  });

  it('requires a server identity and initializes CSRF', async () => {
    const auth = useAuthStore();
    expect(auth.isAuthenticated).toBe(false);
    await auth.initialize();
    expect(auth.user?.userId).toBe('user-a');
    expect(initializeCsrf).toHaveBeenCalledOnce();
    await auth.initialize();
    expect(getCurrentUser).toHaveBeenCalledOnce();
  });

  it('treats 401 as anonymous but allows retry after network failure', async () => {
    const auth = useAuthStore();
    vi.mocked(getCurrentUser).mockRejectedValueOnce(new Error('network'));
    await expect(auth.initialize()).rejects.toThrow('network');
    expect(auth.initialized).toBe(false);
    vi.mocked(getCurrentUser).mockRejectedValueOnce(unauthorized());
    await auth.initialize();
    expect(auth.initialized).toBe(true);
    expect(auth.isAuthenticated).toBe(false);
  });

  it('does not pretend logout succeeded when the server could not invalidate the session', async () => {
    const auth = useAuthStore();
    await auth.login({ identifier: 'a@example.com', password: 'test-only-password1', captcha: 'ABCD' });
    vi.mocked(logout).mockRejectedValueOnce(new Error('offline'));
    await expect(auth.logout()).rejects.toThrow('offline');
    expect(auth.isAuthenticated).toBe(true);
    vi.mocked(logout).mockRejectedValueOnce(unauthorized());
    await auth.logout();
    expect(auth.isAuthenticated).toBe(false);
  });

  it('stores the authenticated user after email registration', async () => {
    const auth = useAuthStore();
    await auth.register({
      email: 'a@example.com',
      verificationCode: '123456',
      password: 'test-password-123',
    });
    expect(register).toHaveBeenCalledWith({
      email: 'a@example.com',
      verificationCode: '123456',
      password: 'test-password-123',
    });
    expect(auth.user?.userId).toBe('user-a');
    expect(auth.isAuthenticated).toBe(true);
  });
});
