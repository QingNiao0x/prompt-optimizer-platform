<script setup lang="ts">
import { computed, onScopeDispose, ref, watch } from 'vue';
import { useAuthStore } from '@/stores/auth';
import { getApiErrorMessage } from '@/services/http';
import { requestRegistrationCode } from '@/services/authApi';
import { validateRegistrationAccount } from '@/features/auth/registrationAccount';

export type AuthModalMode = 'login' | 'register';
type AuthView = 'qr' | 'password' | 'register';

interface Props {
  modelValue: boolean;
  mode: AuthModalMode;
}

interface Emits {
  (event: 'update:modelValue', value: boolean): void;
}

const props = defineProps<Props>();
const emit = defineEmits<Emits>();

const view = ref<AuthView>(props.mode === 'register' ? 'register' : 'password');
const auth = useAuthStore();
const submitting = ref(false);
const errorMessage = ref('');
const statusMessage = ref('');
const account = ref('');
const password = ref('');
const confirmPassword = ref('');
const showPassword = ref(false);
const showConfirmPassword = ref(false);
const verificationCode = ref('');
const agreementAccepted = ref(false);
const requestingCode = ref(false);
const resendAfterSeconds = ref(0);
const verificationRecipient = ref('');
const accountInput = ref<HTMLInputElement>();
let resendTimer: number | undefined;

const registrationAccount = computed(() => validateRegistrationAccount(account.value));
const registrationBlockReason = computed((): string => {
  if (registrationAccount.value.kind === 'empty') {
    return '请先填写邮箱地址；手机号短信注册将在接入短信服务后开放。';
  }
  if (registrationAccount.value.kind === 'invalid') {
    return registrationAccount.value.message;
  }
  if (registrationAccount.value.kind === 'phone') {
    return registrationAccount.value.message;
  }
  if (verificationRecipient.value !== registrationAccount.value.normalized) {
    return '请先向当前邮箱获取验证码。';
  }
  if (!/^\d{6}$/.test(verificationCode.value)) {
    return '请输入邮件中的 6 位验证码。';
  }
  if (password.value.length < 8) {
    return '密码至少需要 8 个字符。';
  }
  if (password.value !== confirmPassword.value) {
    return '两次输入的密码不一致。';
  }
  if (!agreementAccepted.value) {
    return '请先同意用户协议与隐私政策。';
  }
  return '';
});
const canSubmitRegistration = computed((): boolean => registrationBlockReason.value === '');
const codeButtonLabel = computed((): string => {
  if (requestingCode.value) {
    return '发送中…';
  }
  if (resendAfterSeconds.value > 0) {
    return `${resendAfterSeconds.value} 秒后重发`;
  }
  return registrationAccount.value.kind === 'phone' ? '短信注册待开通' : '获取验证码';
});

const title = computed((): string => {
  if (view.value === 'register') {
    return '创建账号';
  }
  return view.value === 'qr' ? '扫码登录' : '账号登录';
});

const close = (): void => {
  stopResendTimer();
  password.value = '';
  confirmPassword.value = '';
  showPassword.value = false;
  showConfirmPassword.value = false;
  emit('update:modelValue', false);
};

const resetFields = (): void => {
  stopResendTimer();
  account.value = '';
  password.value = '';
  confirmPassword.value = '';
  showPassword.value = false;
  showConfirmPassword.value = false;
  verificationCode.value = '';
  verificationRecipient.value = '';
  agreementAccepted.value = false;
  errorMessage.value = '';
  statusMessage.value = '';
};

function stopResendTimer(): void {
  if (resendTimer !== undefined) {
    window.clearInterval(resendTimer);
    resendTimer = undefined;
  }
  resendAfterSeconds.value = 0;
}

const startResendTimer = (seconds: number): void => {
  stopResendTimer();
  resendAfterSeconds.value = Math.max(1, Math.ceil(seconds));
  resendTimer = window.setInterval(() => {
    resendAfterSeconds.value -= 1;
    if (resendAfterSeconds.value <= 0) {
      stopResendTimer();
    }
  }, 1_000);
};

const syncView = (): void => {
  view.value = props.mode === 'register' ? 'register' : 'password';
  resetFields();
};

watch(() => props.mode, syncView);
watch(() => props.modelValue, (open) => {
  if (open) {
    syncView();
  }
});
watch(account, () => {
  if (
    view.value === 'register'
    && verificationRecipient.value
    && registrationAccount.value.normalized !== verificationRecipient.value
  ) {
    stopResendTimer();
    verificationCode.value = '';
    verificationRecipient.value = '';
    statusMessage.value = '';
  }
});

onScopeDispose(stopResendTimer);

const handleRequestCode = async (): Promise<void> => {
  if (requestingCode.value || resendAfterSeconds.value > 0) {
    return;
  }
  if (registrationAccount.value.kind !== 'email') {
    errorMessage.value = registrationAccount.value.message;
    accountInput.value?.focus();
    return;
  }
  requestingCode.value = true;
  errorMessage.value = '';
  statusMessage.value = '';
  try {
    const response = await requestRegistrationCode({ email: registrationAccount.value.normalized });
    verificationRecipient.value = registrationAccount.value.normalized;
    startResendTimer(response.data.resendAfterSeconds);
    statusMessage.value = `验证码已发送，${Math.ceil(response.data.expiresInSeconds / 60)} 分钟内有效。`;
  } catch (error: unknown) {
    errorMessage.value = getApiErrorMessage(error);
  } finally {
    requestingCode.value = false;
  }
};

const handleSubmit = async (event: Event): Promise<void> => {
  event.preventDefault();
  if (submitting.value || view.value === 'qr') {
    return;
  }
  if (view.value === 'register') {
    if (!canSubmitRegistration.value) {
      errorMessage.value = '请填写有效验证码、至少 8 位且一致的密码，并同意用户协议与隐私政策。';
      return;
    }
  }
  submitting.value = true;
  errorMessage.value = '';
  try {
    if (view.value === 'register') {
      await auth.register({
        email: registrationAccount.value.normalized,
        verificationCode: verificationCode.value,
        password: password.value,
      });
    } else {
      await auth.login({ identifier: account.value.trim(), password: password.value });
    }
    // 新身份始终从干净的应用内存开始，不复用另一账号的计划和文件。
    window.location.replace('/workbench');
  } catch (error: unknown) {
    errorMessage.value = getApiErrorMessage(error);
  } finally {
    if (view.value === 'password') {
      password.value = '';
    }
    submitting.value = false;
  }
};
</script>

<template>
  <Teleport to="body">
    <div
      v-if="modelValue"
      class="login-modal"
      role="dialog"
      aria-modal="true"
      aria-labelledby="login-modal-title"
    >
      <button
        class="login-modal__mask"
        type="button"
        aria-label="关闭登录弹窗"
        @click="close"
      ></button>

      <section
        class="login-modal__panel"
        :class="{ 'login-modal__panel--register': view === 'register' }"
        role="document"
      >
        <header class="login-modal__header">
          <div>
            <p class="login-modal__eyebrow">PromptOptimizer</p>
            <h2 id="login-modal-title">{{ title }}</h2>
          </div>
          <button class="login-modal__close" type="button" aria-label="关闭" @click="close">
            ×
          </button>
        </header>

        <div v-if="view === 'qr'" class="login-modal__qr">
          <div class="login-modal__qr-frame" aria-hidden="true">
            <svg viewBox="0 0 120 120" fill="none">
              <rect width="120" height="120" rx="8" fill="white" />
              <rect x="10" y="10" width="28" height="28" stroke="currentColor" stroke-width="6" />
              <rect x="82" y="10" width="28" height="28" stroke="currentColor" stroke-width="6" />
              <rect x="10" y="82" width="28" height="28" stroke="currentColor" stroke-width="6" />
              <rect x="18" y="18" width="12" height="12" fill="currentColor" />
              <rect x="90" y="18" width="12" height="12" fill="currentColor" />
              <rect x="18" y="90" width="12" height="12" fill="currentColor" />
              <rect x="52" y="16" width="8" height="8" fill="currentColor" />
              <rect x="68" y="16" width="8" height="8" fill="currentColor" />
              <rect x="52" y="32" width="8" height="8" fill="currentColor" />
              <rect x="84" y="52" width="8" height="8" fill="currentColor" />
              <rect x="52" y="52" width="24" height="24" fill="currentColor" />
              <rect x="84" y="68" width="8" height="8" fill="currentColor" />
              <rect x="52" y="84" width="8" height="8" fill="currentColor" />
              <rect x="68" y="100" width="8" height="8" fill="currentColor" />
              <rect x="100" y="84" width="8" height="8" fill="currentColor" />
              <rect x="36" y="52" width="8" height="8" fill="currentColor" />
            </svg>
          </div>
          <p>打开微信扫一扫，扫码登录</p>
          <small>当前为界面占位，不会发起登录请求。</small>
        </div>

        <div
          v-else
          class="login-modal__account-layout"
          :class="{ 'login-modal__account-layout--register': view === 'register' }"
        >
          <form class="login-modal__form" @submit="handleSubmit">
            <label>
              {{ view === 'register' ? '邮箱/手机号' : '邮箱或用户名' }}
              <input
                ref="accountInput"
                v-model="account"
                type="text"
                required
                maxlength="320"
                name="account"
                autocomplete="username"
                :placeholder="view === 'register' ? '请输入邮箱地址或手机号' : '请输入邮箱或管理员用户名'"
              >
              <small
                v-if="view === 'register'"
                class="login-modal__field-hint"
                :class="{
                  'login-modal__field-hint--error': registrationAccount.kind === 'invalid',
                  'login-modal__field-hint--notice': registrationAccount.kind === 'phone',
                }"
              >
                {{ registrationAccount.kind === 'empty'
                  ? '支持邮箱和手机号格式；当前先开放邮箱验证码注册。'
                  : registrationAccount.message }}
              </small>
            </label>
            <label v-if="view === 'register'">
              {{ registrationAccount.kind === 'phone' ? '短信验证码' : '邮箱验证码' }}
              <span class="login-modal__code-row">
                <input
                  v-model="verificationCode"
                  type="text"
                  required
                  inputmode="numeric"
                  pattern="[0-9]{6}"
                  maxlength="6"
                  name="verificationCode"
                  autocomplete="one-time-code"
                  placeholder="请输入 6 位验证码"
                >
                <button
                  type="button"
                  :disabled="requestingCode || resendAfterSeconds > 0 || registrationAccount.kind !== 'email'"
                  @click="handleRequestCode"
                >
                  {{ codeButtonLabel }}
                </button>
              </span>
            </label>
            <label>
              密码
              <span class="login-modal__password-field">
                <input
                  v-model="password"
                  :type="view === 'register' && showPassword ? 'text' : 'password'"
                  required
                  :minlength="view === 'register' ? 8 : undefined"
                  maxlength="200"
                  name="password"
                  :autocomplete="view === 'register' ? 'new-password' : 'current-password'"
                  placeholder="请输入密码"
                >
                <button
                  v-if="view === 'register'"
                  type="button"
                  :aria-label="showPassword ? '隐藏密码' : '显示密码'"
                  :title="showPassword ? '隐藏密码' : '显示密码'"
                  @click="showPassword = !showPassword"
                >
                  <svg v-if="showPassword" viewBox="0 0 24 24" aria-hidden="true">
                    <path d="M3 3l18 18M10.6 10.7a2 2 0 0 0 2.7 2.7M9.9 4.2A10.8 10.8 0 0 1 12 4c5.5 0 9 5.2 9 5.2a14 14 0 0 1-2.2 2.8M6.2 6.2A15.8 15.8 0 0 0 3 9.2s3.5 5.2 9 5.2c1 0 2-.2 2.8-.5" />
                  </svg>
                  <svg v-else viewBox="0 0 24 24" aria-hidden="true">
                    <path d="M3 12s3.5-5.2 9-5.2 9 5.2 9 5.2-3.5 5.2-9 5.2S3 12 3 12Z" />
                    <circle cx="12" cy="12" r="2.5" />
                  </svg>
                </button>
              </span>
              <small
                v-if="view === 'register'"
                class="login-modal__password-hint"
                :class="{ 'login-modal__password-hint--error': password.length > 0 && password.length < 8 }"
                aria-live="polite"
              >
                已输入 {{ password.length }} 个字符，至少需要 8 个字符。
              </small>
            </label>
            <label v-if="view === 'register'">
              确认密码
              <span class="login-modal__password-field">
                <input
                  v-model="confirmPassword"
                  :type="showConfirmPassword ? 'text' : 'password'"
                  required
                  maxlength="200"
                  name="confirmPassword"
                  autocomplete="new-password"
                  placeholder="请再次输入密码"
                >
                <button
                  type="button"
                  :aria-label="showConfirmPassword ? '隐藏确认密码' : '显示确认密码'"
                  :title="showConfirmPassword ? '隐藏确认密码' : '显示确认密码'"
                  @click="showConfirmPassword = !showConfirmPassword"
                >
                  <svg v-if="showConfirmPassword" viewBox="0 0 24 24" aria-hidden="true">
                    <path d="M3 3l18 18M10.6 10.7a2 2 0 0 0 2.7 2.7M9.9 4.2A10.8 10.8 0 0 1 12 4c5.5 0 9 5.2 9 5.2a14 14 0 0 1-2.2 2.8M6.2 6.2A15.8 15.8 0 0 0 3 9.2s3.5 5.2 9 5.2c1 0 2-.2 2.8-.5" />
                  </svg>
                  <svg v-else viewBox="0 0 24 24" aria-hidden="true">
                    <path d="M3 12s3.5-5.2 9-5.2 9 5.2 9 5.2-3.5 5.2-9 5.2S3 12 3 12Z" />
                    <circle cx="12" cy="12" r="2.5" />
                  </svg>
                </button>
              </span>
              <small
                v-if="confirmPassword && password !== confirmPassword"
                class="login-modal__password-hint login-modal__password-hint--error"
                aria-live="polite"
              >
                两次输入的密码不一致。
              </small>
            </label>
            <label v-if="view === 'register'" class="login-modal__agreement">
              <input v-model="agreementAccepted" type="checkbox" required>
              <span>
                我已阅读并同意
                <strong>用户协议</strong>
                与
                <strong>隐私政策</strong>
              </span>
            </label>
            <p v-if="errorMessage" role="alert">{{ errorMessage }}</p>
            <p v-if="statusMessage" class="login-modal__status" role="status">{{ statusMessage }}</p>
            <button
              class="login-modal__submit"
              type="submit"
              :disabled="submitting || (view === 'register' && !canSubmitRegistration)"
            >
              {{ submitting ? (view === 'register' ? '创建中…' : '登录中…') : view === 'register' ? '创建账号' : '登录' }}
            </button>
            <small
              v-if="view === 'register' && registrationBlockReason"
              class="login-modal__submit-hint"
              aria-live="polite"
            >
              创建账号前：{{ registrationBlockReason }}
            </small>
            <small v-if="view === 'register'" id="register-preview-note">
              验证码 5 分钟内有效，60 秒后可重发；密码至少 8 个字符。
            </small>
            <small v-else>使用注册邮箱或管理员用户名及密码登录。</small>
          </form>

          <aside v-if="view === 'register'" class="login-modal__wechat" aria-label="微信扫码登录">
            <p class="login-modal__wechat-title">
              <span class="login-modal__wechat-mark" aria-hidden="true">
                <i></i><i></i>
              </span>
              微信扫码登录
            </p>
            <div class="login-modal__qr-frame" aria-hidden="true">
              <svg viewBox="0 0 120 120" fill="none">
                <rect width="120" height="120" rx="8" fill="white" />
                <rect x="10" y="10" width="28" height="28" stroke="currentColor" stroke-width="6" />
                <rect x="82" y="10" width="28" height="28" stroke="currentColor" stroke-width="6" />
                <rect x="10" y="82" width="28" height="28" stroke="currentColor" stroke-width="6" />
                <rect x="18" y="18" width="12" height="12" fill="currentColor" />
                <rect x="90" y="18" width="12" height="12" fill="currentColor" />
                <rect x="18" y="90" width="12" height="12" fill="currentColor" />
                <rect x="52" y="16" width="8" height="8" fill="currentColor" />
                <rect x="68" y="16" width="8" height="8" fill="currentColor" />
                <rect x="52" y="32" width="8" height="8" fill="currentColor" />
                <rect x="84" y="52" width="8" height="8" fill="currentColor" />
                <rect x="52" y="52" width="24" height="24" fill="currentColor" />
                <rect x="84" y="68" width="8" height="8" fill="currentColor" />
                <rect x="52" y="84" width="8" height="8" fill="currentColor" />
                <rect x="68" y="100" width="8" height="8" fill="currentColor" />
                <rect x="100" y="84" width="8" height="8" fill="currentColor" />
                <rect x="36" y="52" width="8" height="8" fill="currentColor" />
              </svg>
            </div>
            <p>打开微信扫一扫</p>
            <small>扫码能力将在微信开放平台接入后启用</small>
          </aside>
        </div>

        <footer class="login-modal__footer">
          <button
            v-if="view === 'qr'"
            type="button"
            @click="view = 'password'"
          >
            使用账号密码登录
          </button>
          <button
            v-else-if="view === 'password'"
            type="button"
            @click="view = 'qr'"
          >
            查看扫码登录（暂未开通）
          </button>
          <button
            v-else
            type="button"
            @click="view = 'password'"
          >
            已有账号？使用邮箱或用户名密码登录
          </button>
        </footer>
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.login-modal {
  position: fixed;
  inset: 0;
  z-index: 240;
  display: grid;
  place-items: center;
  padding: 24px;
}

.login-modal__mask {
  position: absolute;
  inset: 0;
  border: 0;
  background: rgba(0, 0, 0, 0.1);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  cursor: pointer;
}

.login-modal__panel {
  position: relative;
  z-index: 1;
  display: grid;
  width: min(100%, 420px);
  gap: 20px;
  padding: 24px;
  overflow: hidden;
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-lg);
  background: var(--glass-bg-strong);
  box-shadow: var(--glass-shadow-hover);
  backdrop-filter: blur(16px) saturate(1.5);
  -webkit-backdrop-filter: blur(16px) saturate(1.5);
  animation: fade-in-up 0.6s ease-out;
}

.login-modal__panel--register {
  width: min(100%, 760px);
}

.login-modal__panel::before {
  position: absolute;
  inset: 0 0 auto;
  height: 1px;
  content: '';
  background: var(--glass-highlight);
}

.login-modal__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.login-modal__eyebrow {
  margin: 0 0 4px;
  color: var(--accent);
  font-size: 13px;
  letter-spacing: 0.08em;
}

h2 {
  margin: 0;
  color: var(--text-primary);
  font-size: 22px;
  font-weight: 700;
}

.login-modal__close {
  width: 36px;
  height: 36px;
  border: 0;
  border-radius: var(--radius-pill);
  color: var(--text-secondary);
  font-size: 22px;
  line-height: 1;
  background: var(--glass-bg);
  cursor: pointer;
}

.login-modal__qr {
  display: grid;
  justify-items: center;
  gap: 12px;
  text-align: center;
}

.login-modal__qr-frame {
  display: grid;
  width: 168px;
  height: 168px;
  place-items: center;
  padding: 12px;
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-md);
  color: #111315;
  background: var(--glass-bg);
}

.login-modal__qr-frame svg {
  width: 100%;
  height: 100%;
}

.login-modal__qr p,
.login-modal__form small,
.login-modal__qr small {
  margin: 0;
  color: var(--text-secondary);
  font-size: 14px;
}

.login-modal__qr small,
.login-modal__form small {
  color: var(--text-muted);
  font-size: 13px;
}

.login-modal__form {
  display: grid;
  gap: 12px;
}

.login-modal__account-layout--register {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 240px;
  gap: 28px;
  align-items: stretch;
}

.login-modal__form label {
  display: grid;
  gap: 6px;
  color: var(--text-primary);
  font-size: 14px;
  font-weight: 600;
}

.login-modal__form input {
  min-height: 44px;
  padding: 10px 12px;
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-sm);
  color: var(--text-primary);
  background: var(--glass-bg);
}

.login-modal__form input:focus-visible {
  border-color: var(--accent);
  outline: 3px solid var(--accent-soft);
}

.login-modal__password-field {
  position: relative;
  display: block;
}

.login-modal__password-field input {
  width: 100%;
  padding-right: 44px;
}

.login-modal__password-field button {
  position: absolute;
  top: 50%;
  right: 8px;
  display: grid;
  width: 32px;
  height: 32px;
  padding: 0;
  place-items: center;
  border: 0;
  border-radius: var(--radius-sm);
  color: var(--text-secondary);
  background: transparent;
  transform: translateY(-50%);
  cursor: pointer;
}

.login-modal__password-field button:hover,
.login-modal__password-field button:focus-visible {
  color: var(--accent);
  background: var(--accent-soft);
  outline: none;
}

.login-modal__password-field svg {
  width: 19px;
  height: 19px;
  fill: none;
  stroke: currentColor;
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-width: 1.8;
}

.login-modal__form .login-modal__password-hint {
  color: var(--text-muted);
  font-size: 13px;
  font-weight: 400;
}

.login-modal__form .login-modal__password-hint--error {
  color: var(--danger, #d14343);
}

.login-modal__code-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 8px;
}

.login-modal__code-row button {
  min-width: 112px;
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-sm);
  color: var(--accent);
  font-weight: 600;
  background: var(--glass-bg);
  cursor: pointer;
}

.login-modal__code-row button:disabled {
  color: var(--text-muted);
  cursor: not-allowed;
}

.login-modal__form > p {
  margin: 0;
  color: var(--danger, #d14343);
  font-size: 13px;
}

.login-modal__form > .login-modal__status {
  color: var(--text-secondary);
}

.login-modal__form .login-modal__field-hint,
.login-modal__form .login-modal__submit-hint {
  color: var(--text-muted);
  font-size: 13px;
  font-weight: 400;
  line-height: 1.6;
}

.login-modal__form .login-modal__field-hint--error {
  color: var(--danger, #d14343);
}

.login-modal__form .login-modal__field-hint--notice {
  color: var(--text-secondary);
}

.login-modal__form .login-modal__agreement {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  color: var(--text-secondary);
  font-size: 13px;
  font-weight: 400;
  line-height: 1.6;
}

.login-modal__agreement input {
  width: 16px;
  min-height: 16px;
  margin-top: 2px;
  padding: 0;
  accent-color: var(--accent);
}

.login-modal__agreement strong {
  color: var(--accent);
  font-weight: 600;
}

.login-modal__submit {
  min-height: 44px;
  border: 0;
  border-radius: var(--radius-pill);
  color: #fff;
  font-size: 15px;
  font-weight: 600;
  background: var(--accent);
  cursor: pointer;
}

.login-modal__submit:disabled {
  cursor: not-allowed;
  opacity: 0.78;
}

.login-modal__wechat {
  display: grid;
  align-content: center;
  justify-items: center;
  gap: 10px;
  min-height: 320px;
  padding: 24px 20px;
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-md);
  color: var(--text-primary);
  text-align: center;
  background: var(--glass-bg-subtle);
}

.login-modal__wechat p,
.login-modal__wechat small {
  margin: 0;
}

.login-modal__wechat-title {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  font-weight: 650;
}

.login-modal__wechat-mark {
  position: relative;
  width: 21px;
  height: 16px;
}

.login-modal__wechat-mark i {
  position: absolute;
  width: 13px;
  height: 11px;
  border-radius: 55%;
  background: #16c65b;
}

.login-modal__wechat-mark i:first-child {
  top: 0;
  left: 0;
}

.login-modal__wechat-mark i:last-child {
  right: 0;
  bottom: 0;
  background: #0faf4d;
}

.login-modal__wechat > p:not(.login-modal__wechat-title) {
  color: var(--text-secondary);
  font-size: 14px;
}

.login-modal__wechat small {
  color: var(--text-muted);
  font-size: 12px;
  line-height: 1.6;
}

.login-modal__footer {
  display: flex;
  justify-content: center;
}

.login-modal__footer button {
  border: 0;
  color: var(--accent);
  font-size: 14px;
  background: transparent;
  cursor: pointer;
}

@media (max-width: 520px) {
  .login-modal {
    padding: 16px;
    align-items: end;
  }

  .login-modal__panel {
    width: 100%;
  }
}

@media (max-width: 720px) {
  .login-modal__panel--register {
    max-height: calc(100dvh - 32px);
    overflow-y: auto;
  }

  .login-modal__account-layout--register {
    grid-template-columns: 1fr;
  }

  .login-modal__wechat {
    min-height: auto;
  }
}
</style>
