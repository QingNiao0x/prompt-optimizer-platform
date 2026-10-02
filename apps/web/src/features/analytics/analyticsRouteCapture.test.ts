import { createPinia, setActivePinia } from 'pinia';
import { createMemoryHistory, createRouter } from 'vue-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { installAnalyticsRouteCapture } from './analyticsRouteCapture';
import { useAuthStore } from '@/stores/auth';
import { getCurrentUser, initializeCsrf } from '@/services/authApi';
import type { ApiResponse, AuthenticatedUser } from '@/types/api';

const enqueue = vi.hoisted(() => vi.fn());
vi.mock('@/stores/analyticsDelivery', () => ({ useAnalyticsDeliveryStore: () => ({ enqueue }) }));
vi.mock('@/services/authApi', () => ({
  getCurrentUser: vi.fn(), initializeCsrf: vi.fn(), login: vi.fn(), register: vi.fn(), logout: vi.fn(),
}));
const user: AuthenticatedUser = {
  userId: '00000000-0000-4000-8000-000000000101', tenantId: 'tenant', workspaceId: 'workspace',
  email: 'fixture@example.invalid', displayName: 'Fixture', platformAdmin: false,
};

describe('analytics route capture', () => {
  const cleanups: (() => void)[] = [];
  beforeEach(() => { vi.resetAllMocks(); });
  afterEach(() => { cleanups.splice(0).forEach((cleanup) => cleanup()); });
  const fixture = () => {
    const pinia = createPinia();
    setActivePinia(pinia);
    const router = createRouter({
      history: createMemoryHistory(),
      routes: ['home', 'login', 'workbench'].map((name) => ({ name, path: name === 'home' ? '/' : `/${name}`, component: {} })),
    });
    cleanups.push(installAnalyticsRouteCapture(router, pinia));
    return { auth: useAuthStore(pinia), router };
  };

  it('backfills one home visit only after identity and CSRF restoration complete', async () => {
    const { auth, router } = fixture();
    await router.push('/');
    expect(enqueue).not.toHaveBeenCalled();
    let csrfReady: (() => void) | undefined;
    vi.mocked(getCurrentUser).mockResolvedValue({ requestId: 'fixture', data: user });
    vi.mocked(initializeCsrf).mockImplementation(() => new Promise((resolve) => {
      csrfReady = () => resolve({ requestId: 'fixture', data: { headerName: '', parameterName: '', token: '' } });
    }));
    const initialization = auth.initialize();
    await Promise.resolve();
    expect(enqueue).not.toHaveBeenCalled();
    csrfReady?.();
    await initialization;
    expect(enqueue).toHaveBeenCalledExactlyOnceWith('APP_VISIT');
    await auth.initialize();
    await router.push('/');
    expect(enqueue).toHaveBeenCalledOnce();
    await router.push('/workbench');
    expect(enqueue).toHaveBeenCalledTimes(2);
  });

  it('never counts an anonymous or login page, and captures only the current route after delayed identity restoration', async () => {
    const { auth, router } = fixture();
    await router.push('/');
    let restored: ((response: ApiResponse<AuthenticatedUser>) => void) | undefined;
    vi.mocked(getCurrentUser).mockImplementation(() => new Promise((resolve) => { restored = resolve; }));
    const initialization = auth.initialize();
    await router.push('/login');
    restored?.({ requestId: 'fixture', data: user });
    await initialization;
    expect(enqueue).not.toHaveBeenCalled();
    await router.push('/');
    expect(enqueue).toHaveBeenCalledExactlyOnceWith('APP_VISIT');
    auth.user = undefined;
    await router.push('/workbench');
    expect(enqueue).toHaveBeenCalledOnce();
  });
});
