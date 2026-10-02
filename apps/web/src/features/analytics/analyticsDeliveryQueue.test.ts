import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ANALYTICS_PENDING_PREFIX, createAnalyticsDeliveryQueue, createAnalyticsEventId } from './analyticsDeliveryQueue';
import type { AnalyticsClientEvent } from '@/types/api';

class MemoryStorage implements Storage {
  readonly values = new Map<string, string>();
  get length(): number { return this.values.size; }
  clear(): void { this.values.clear(); }
  getItem(key: string): string | null { return this.values.get(key) ?? null; }
  key(index: number): string | null { return [...this.values.keys()][index] ?? null; }
  removeItem(key: string): void { this.values.delete(key); }
  setItem(key: string, value: string): void { this.values.set(key, value); }
}

const firstAccount = '00000000-0000-4000-8000-000000000101';
const secondAccount = '00000000-0000-4000-8000-000000000102';
const sessionId = '00000000-0000-4000-8000-000000000103';

describe('analytics durable browser delivery', () => {
  const queues: ReturnType<typeof createAnalyticsDeliveryQueue>[] = [];
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => {
    queues.forEach((queue) => queue.dispose());
    queues.length = 0;
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  const fixture = (storage = new MemoryStorage()) => {
    const send = vi.fn<(event: AnalyticsClientEvent, signal: AbortSignal) => Promise<void>>().mockResolvedValue(undefined);
    const warning = vi.fn();
    const changed = vi.fn();
    let sequence = 0;
    let online = true;
    let loginSessionId: string | null = sessionId;
    const create = () => {
      const queue = createAnalyticsDeliveryQueue({
        storage, send, warning, changed, online: () => online,
        now: () => new Date('2026-10-02T00:00:00Z'),
        uuid: () => `00000000-0000-4000-8000-${String(++sequence).padStart(12, '0')}`,
        loginSessionId: () => loginSessionId,
        status: (error) => typeof error === 'number' ? error : undefined,
      });
      queues.push(queue);
      return queue;
    };
    return { create, storage, send, warning, changed,
      setOnline: (value: boolean) => { online = value; },
      setSession: (value: string | null) => { loginSessionId = value; },
    };
  };

  it('persists before sending and retries the same ID/time after an uncertain response', async () => {
    const state = fixture();
    state.send.mockRejectedValueOnce(503);
    const queue = state.create();
    queue.activateAccount(firstAccount);
    queue.enqueue('APP_VISIT');
    expect(state.storage.length).toBe(1);
    await vi.advanceTimersByTimeAsync(0);
    expect(state.storage.length).toBe(1);
    await vi.advanceTimersByTimeAsync(999);
    expect(state.send).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(state.send).toHaveBeenCalledTimes(2);
    expect(state.send.mock.calls[0]![0]).toEqual(state.send.mock.calls[1]![0]);
    expect(state.storage.length).toBe(0);
  });

  it('restores offline pending events after reload without inventing a new login location', async () => {
    const state = fixture();
    state.setOnline(false);
    state.setSession(null);
    const firstPage = state.create();
    firstPage.activateAccount(firstAccount);
    firstPage.enqueue('RESULT_EXPORTED');
    await vi.advanceTimersByTimeAsync(0);
    expect(state.send).not.toHaveBeenCalled();
    firstPage.dispose();
    state.setOnline(true);
    state.setSession(sessionId);
    const nextPage = state.create();
    nextPage.activateAccount(firstAccount);
    await vi.advanceTimersByTimeAsync(0);
    expect(state.send).toHaveBeenCalledOnce();
    expect(state.send.mock.calls[0]![0].expectedLoginSessionId).toBeNull();
    expect(state.send.mock.calls[0]![0].occurredAt).toBe('2026-10-02T00:00:00.000Z');
    expect(state.storage.length).toBe(0);
  });

  it('preserves old-account events across logout and never sends them as a new account', async () => {
    const state = fixture();
    state.setOnline(false);
    const queue = state.create();
    queue.activateAccount(firstAccount);
    queue.enqueue('APP_VISIT');
    queue.activateAccount(undefined);
    queue.enqueue('APP_VISIT');
    queue.activateAccount(secondAccount);
    queue.enqueue('RESULT_EXPORTED');
    state.setOnline(true);
    queue.resume();
    await vi.advanceTimersByTimeAsync(0);
    expect(state.send.mock.calls.map(([event]) => event.expectedUserId)).toEqual([secondAccount]);
    expect(state.storage.length).toBe(1);
    queue.activateAccount(firstAccount);
    await vi.advanceTimersByTimeAsync(0);
    expect(state.send.mock.calls[1]![0].expectedUserId).toBe(firstAccount);
    expect(state.storage.length).toBe(0);
  });

  it('pauses identity conflicts without deleting the rejected event or retrying on every online event', async () => {
    const state = fixture();
    state.send.mockRejectedValueOnce(409);
    const queue = state.create();
    queue.activateAccount(firstAccount);
    queue.enqueue('APP_VISIT');
    await vi.advanceTimersByTimeAsync(0);
    queue.resume();
    await vi.advanceTimersByTimeAsync(120_000);
    expect(state.send).toHaveBeenCalledOnce();
    expect(state.storage.length).toBe(1);
    queue.activateAccount(undefined);
    queue.activateAccount(firstAccount);
    await vi.advanceTimersByTimeAsync(0);
    expect(state.send).toHaveBeenCalledTimes(2);
    expect(state.storage.length).toBe(0);
  });

  it('retains events after many failures and caps backoff rather than discarding them', async () => {
    const state = fixture();
    state.send.mockRejectedValue(503);
    const queue = state.create();
    queue.activateAccount(firstAccount);
    queue.enqueue('APP_VISIT');
    await vi.advanceTimersByTimeAsync(300_000);
    expect(state.storage.length).toBe(1);
    expect(state.send.mock.calls.length).toBeGreaterThan(6);
    expect(state.send.mock.calls.length).toBeLessThan(14);
    expect(new Set(state.send.mock.calls.map(([event]) => event.eventId)).size).toBe(1);
  });

  it('keeps separately stored tab events and uses memory fallback with an explicit storage failure', async () => {
    const state = fixture();
    state.setOnline(false);
    const firstTab = state.create();
    const secondTab = state.create();
    firstTab.activateAccount(firstAccount);
    secondTab.activateAccount(firstAccount);
    firstTab.enqueue('APP_VISIT');
    secondTab.enqueue('RESULT_EXPORTED');
    expect(state.storage.length).toBe(2);
    vi.spyOn(state.storage, 'setItem').mockImplementation(() => { throw new Error('quota'); });
    firstTab.enqueue('RESULT_EXPORTED');
    expect(state.warning).toHaveBeenCalledWith('STORAGE_UNAVAILABLE');
    expect(state.changed).toHaveBeenLastCalledWith(3);
    state.setOnline(true);
    firstTab.resume();
    await vi.advanceTimersByTimeAsync(200);
    // 两个标签页可以同时发送同一持久化 ID；不丢唯一事件，重复计数由后端幂等边界负责。
    expect(new Set(state.send.mock.calls.map(([event]) => event.eventId)).size).toBe(3);
    expect(state.storage.length).toBe(0);
  });

  it('does not tight-loop when acknowledgement cannot remove the persisted key', async () => {
    const state = fixture();
    vi.spyOn(state.storage, 'removeItem').mockImplementation(() => { throw new Error('read only'); });
    const queue = state.create();
    queue.activateAccount(firstAccount);
    queue.enqueue('APP_VISIT');
    await vi.advanceTimersByTimeAsync(120_000);
    expect(state.send).toHaveBeenCalledOnce();
    expect(state.storage.length).toBe(1);
  });

  it('does not submit corrupt or forged persisted events', async () => {
    const state = fixture();
    state.storage.setItem(`${ANALYTICS_PENDING_PREFIX}${firstAccount}:invalid`, JSON.stringify({
      eventId: 'invalid', eventType: 'LOGIN', expectedUserId: secondAccount, occurredAt: 'not-a-date',
    }));
    const queue = state.create();
    queue.activateAccount(firstAccount);
    await vi.advanceTimersByTimeAsync(0);
    expect(state.send).not.toHaveBeenCalled();
    expect(state.storage.length).toBe(1);
    expect(state.warning).toHaveBeenCalledWith('INVALID_PENDING_EVENT');
  });

  it('generates a valid event UUID when randomUUID is unavailable in a browser', () => {
    vi.stubGlobal('crypto', { getRandomValues: (bytes: Uint8Array) => bytes.fill(42) });
    expect(createAnalyticsEventId()).toBe('2a2a2a2a-2a2a-4a2a-aa2a-2a2a2a2a2a2a');
  });
});
