import { createPinia, setActivePinia } from 'pinia';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from './auth';
import { useTrialStore } from './trial';
import { getTrialStatus, initializeTrialSession } from '@/services/trialApi';
import type { TrialStatus } from '@/types/trial';

vi.mock('@/services/trialApi', () => ({ getTrialStatus: vi.fn(), initializeTrialSession: vi.fn() }));

const status = (remaining = 3): TrialStatus => ({ authenticated: false, available: true, quotas: {
  imagePrompt: { limit: 1, used: 0, remaining: 1 },
  enhancement: { limit: 3, used: 3 - remaining, remaining },
  textPolish: { limit: 3, used: 0, remaining: 3 },
} });
const deferred = <T>() => {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>(done => { resolve = done; });
  return { promise, resolve };
};
const login = () => {
  useAuthStore().user = { userId: 'fixture-user', tenantId: 'fixture-tenant', workspaceId: 'fixture-workspace',
    displayName: '测试账号', email: 'fixture@example.test', platformAdmin: false };
};

/** 余额只来自服务端；模拟异步响应以验证跨页面并发和登录切换不会恢复访客额度。 */
describe('guest trial quota state', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    setActivePinia(createPinia());
    vi.mocked(initializeTrialSession).mockResolvedValue();
    vi.mocked(getTrialStatus).mockResolvedValue(status());
  });

  it('fails closed before initialization and keeps the three balances independent', async () => {
    const store = useTrialStore();
    expect(store.canUse('PROMPT_ENHANCEMENT')).toBe(false);
    await store.initialize();
    expect(store.remaining('IMAGE_TO_PROMPT')).toBe(1);
    expect(store.remaining('PROMPT_ENHANCEMENT')).toBe(3);
    expect(store.remaining('TEXT_POLISH')).toBe(3);
    vi.mocked(getTrialStatus).mockResolvedValue(status(0));
    await store.refresh();
    expect(store.canUse('PROMPT_ENHANCEMENT')).toBe(false);
    expect(store.canUse('TEXT_POLISH')).toBe(true);
    await store.initialize();
    expect(initializeTrialSession).toHaveBeenCalledOnce();
    expect(store.remaining('PROMPT_ENHANCEMENT')).toBe(0);
  });

  it('shares an in-flight initialization without creating a second visitor session', async () => {
    const waiting = deferred<TrialStatus>();
    vi.mocked(getTrialStatus).mockReturnValue(waiting.promise);
    const store = useTrialStore();
    const first = store.initialize();
    const second = store.initialize();
    waiting.resolve(status());
    await Promise.all([first, second]);
    expect(initializeTrialSession).toHaveBeenCalledOnce();
    expect(getTrialStatus).toHaveBeenCalledOnce();
  });

  it('does not enable generation when the browser failed to retain the visitor cookie', async () => {
    vi.mocked(getTrialStatus).mockResolvedValue({ ...status(), initialized: false });
    const store = useTrialStore();
    await store.initialize();
    expect(store.canUse('PROMPT_ENHANCEMENT')).toBe(false);
    expect(store.error).toContain('Cookie');
    expect(store.initialized).toBe(false);
  });

  it('does not treat service failure as a fresh allowance and recovers only from server status', async () => {
    const store = useTrialStore();
    await store.initialize();
    vi.mocked(getTrialStatus).mockRejectedValueOnce(new Error('试用服务暂不可用'));
    await store.refresh();
    expect(store.error).not.toBe('');
    expect(store.canUse('PROMPT_ENHANCEMENT')).toBe(false);
    expect(store.remaining('PROMPT_ENHANCEMENT')).toBe(0);
    vi.mocked(getTrialStatus).mockResolvedValue(status(1));
    await store.refresh();
    expect(store.error).toBe('');
    expect(store.remaining('PROMPT_ENHANCEMENT')).toBe(1);
    expect(initializeTrialSession).toHaveBeenCalledOnce();
  });

  it('does not let a pre-generation response replace the post-generation balance', async () => {
    const waiting = deferred<TrialStatus>();
    vi.mocked(getTrialStatus).mockReturnValueOnce(waiting.promise).mockResolvedValueOnce(status(2));
    const store = useTrialStore();
    const initial = store.initialize();
    await vi.waitFor(() => expect(getTrialStatus).toHaveBeenCalledOnce());
    const refreshed = store.refresh();
    waiting.resolve(status(3));
    await Promise.all([initial, refreshed]);
    expect(getTrialStatus).toHaveBeenCalledTimes(2);
    expect(store.remaining('PROMPT_ENHANCEMENT')).toBe(2);
  });

  it('ignores late guest responses after login and does not limit an authenticated user', async () => {
    const waiting = deferred<TrialStatus>();
    vi.mocked(getTrialStatus).mockReturnValue(waiting.promise);
    const store = useTrialStore();
    const request = store.initialize();
    await vi.waitFor(() => expect(getTrialStatus).toHaveBeenCalledOnce());
    login();
    waiting.resolve(status(0));
    await request;
    expect(store.status).toBeUndefined();
    expect(store.canUse('PROMPT_ENHANCEMENT')).toBe(true);
    await store.initialize();
    await store.refresh();
    expect(getTrialStatus).toHaveBeenCalledOnce();
    expect(initializeTrialSession).toHaveBeenCalledOnce();
  });

  it('clears signed-in state on logout and reads the existing visitor balance again', async () => {
    login();
    const store = useTrialStore();
    await store.initialize();
    expect(initializeTrialSession).not.toHaveBeenCalled();
    useAuthStore().user = undefined;
    expect(store.canUse('PROMPT_ENHANCEMENT')).toBe(false);
    vi.mocked(getTrialStatus).mockResolvedValue(status(0));
    await store.initialize();
    expect(store.remaining('PROMPT_ENHANCEMENT')).toBe(0);
    expect(store.canUse('TEXT_POLISH')).toBe(true);
  });
});
