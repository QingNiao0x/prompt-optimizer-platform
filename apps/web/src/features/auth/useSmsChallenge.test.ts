import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { effectScope, nextTick, ref } from 'vue';
import { useSmsChallenge } from './useSmsChallenge';
import type { ApiResponse, SmsChallengeStatus } from '@/types/api';

describe('SMS challenge isolation', () => {
  beforeEach(() => { vi.useFakeTimers(); vi.stubGlobal('window', globalThis); });
  afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); });
  it('expires after five minutes and enforces the resend interval', async () => {
    const scope = effectScope();
    const phone = ref<string | undefined>('+8613800000000');
    const state = scope.run(() => useSmsChallenge(phone))!;
    const request = vi.fn(async () => ({ requestId: 'test', data: { challengeId: 'one', expiresInSeconds: 300, resendAfterSeconds: 60 } }));
    await state.send(request);
    expect(state.valid.value).toBe(true);
    await state.send(request); expect(request).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(59_000); expect(state.remaining.value).toBe(1);
    await vi.advanceTimersByTimeAsync(1_000); expect(state.remaining.value).toBe(0);
    await vi.advanceTimersByTimeAsync(240_000); expect(state.valid.value).toBe(false);
    scope.stop();
  });
  it('discards an old recipient response after the phone changes', async () => {
    const scope = effectScope(); const phone = ref<string | undefined>('+8613800000000');
    const state = scope.run(() => useSmsChallenge(phone))!;
    let resolve: (value: ApiResponse<SmsChallengeStatus>) => void = () => undefined;
    const pending = state.send(() => new Promise(done => { resolve = done; }));
    phone.value = '+8613800000001'; await nextTick();
    resolve({ requestId: 'test', data: { challengeId: 'old', expiresInSeconds: 300, resendAfterSeconds: 60 } });
    expect(await pending).toBe(false); expect(state.challengeId.value).toBe('');
    scope.stop();
  });
});
