import { computed, onScopeDispose, ref, watch, type Ref } from 'vue';
import { AxiosError } from 'axios';
import type { ApiErrorPayload, ApiResponse, SmsChallengeStatus } from '@/types/api';

/** 挑战只保存在表单内存；号码改变后丢弃旧授权，迟到响应不能覆盖新收件人。 */
export const useSmsChallenge = (phone: Ref<string | undefined>) => {
  const challengeId = ref('');
  const sending = ref(false);
  const wait = ref(0);
  let version = 0;
  let expiresAt = 0;
  const now = ref(Date.now());
  const timer = window.setInterval(() => { now.value = Date.now(); }, 1_000);
  onScopeDispose(() => { version += 1; window.clearInterval(timer); });
  const remaining = computed(() => Math.max(0, Math.ceil((wait.value - now.value) / 1_000)));
  const valid = computed(() => !!challengeId.value && now.value < expiresAt);
  const reset = (): void => { version += 1; challengeId.value = ''; expiresAt = 0; };
  watch(phone, reset);

  const send = async (request: () => Promise<ApiResponse<SmsChallengeStatus>>): Promise<boolean> => {
    if (sending.value || remaining.value > 0 || !phone.value) return false;
    const revision = ++version;
    sending.value = true;
    // 发码失败/结果不确定时，旧挑战也不继续使用，不在浏览器自动重发。
    challengeId.value = '';
    try {
      const result = (await request()).data;
      now.value = Date.now();
      wait.value = now.value + result.resendAfterSeconds * 1_000;
      if (revision !== version) return false;
      expiresAt = now.value + result.expiresInSeconds * 1_000;
      challengeId.value = result.challengeId;
      return true;
    } catch (error: unknown) {
      if (error instanceof AxiosError) {
        const payload = error.response?.data as ApiErrorPayload | undefined;
        const seconds = Number(payload?.error?.details?.retryAfterSeconds ?? 60);
        wait.value = Date.now() + (Number.isFinite(seconds) && seconds > 0 ? seconds : 60) * 1_000;
      }
      throw error;
    } finally { sending.value = false; }
  };
  return { challengeId, sending, remaining, valid, send, reset };
};
