<script setup lang="ts">
import { computed, onScopeDispose, ref, watch } from 'vue';
import { useAuthStore } from '@/stores/auth';
import { getApiErrorMessage, httpClient } from '@/services/http';
import { requestRegistrationCode, requestSmsChallenge } from '@/services/authApi';
import { normalizeMainlandPhone, validateRegistrationAccount } from '@/features/auth/registrationAccount';
import { useSmsChallenge } from '@/features/auth/useSmsChallenge';
import { ElButton } from 'element-plus';
import PhoneAuthPanel from '@/components/auth/PhoneAuthPanel.vue';
import ImageCaptcha from '@/components/auth/ImageCaptcha.vue';

export type AuthModalMode = 'login' | 'register';
type AuthView = 'qr' | 'password' | 'register' | 'sms' | 'phone-password';

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
const registrationSucceeded = ref(false);
const errorMessage = ref('');
const statusMessage = ref('');
const account = ref('');
const loginAsUsername = ref(false);
const password = ref('');
const confirmPassword = ref('');
const showPassword = ref(false);
const showConfirmPassword = ref(false);
const verificationCode = ref('');
const agreementAccepted = ref(false);
const requestingCode = ref(false);
const resendAfterSeconds = ref(0);
const verificationRecipient = ref('');
const captcha = ref('');
const captchaUrl = ref('');
const registrationCaptcha = ref('');
const registrationCaptchaImage = ref<InstanceType<typeof ImageCaptcha>>();
const accountInput = ref<HTMLInputElement>();
let resendTimer: number | undefined;
let registrationRevision = 0;

const registrationAccount = computed(() => validateRegistrationAccount(account.value));
const loginPhone = computed(() => normalizeMainlandPhone(account.value));
const loginAccountLabel = computed(() => loginAsUsername.value ? '用户名' : '手机号或邮箱');
const loginAccountPlaceholder = computed(() => loginAsUsername.value ? '请输入用户名' : '请输入手机号或邮箱');
const phoneView = computed(() => view.value === 'sms' || view.value === 'phone-password' ? view.value : undefined);
const isSmsRegistration = computed(() => registrationAccount.value.kind === 'phone' && auth.capabilities.phoneRegistration);
const registrationPhone = computed(() => isSmsRegistration.value ? registrationAccount.value.normalized : undefined);
// 短信与邮箱分别保留冷却状态；号码或表单变化会丢弃旧短信授权，但不会退还发送预算。
const {
  challengeId: smsChallengeId,
  sending: sendingSmsCode,
  remaining: smsResendAfterSeconds,
  valid: smsChallengeValid,
  send: sendSmsChallenge,
  reset: resetSmsChallenge,
} = useSmsChallenge(registrationPhone);
const codeSending = computed(() => requestingCode.value || sendingSmsCode.value);
const codeResendAfterSeconds = computed(() => isSmsRegistration.value ? smsResendAfterSeconds.value : resendAfterSeconds.value);
const registrationHint = computed((): string => {
  if (registrationAccount.value.kind === 'empty') {
    return auth.capabilities.phoneRegistration
      ? '可使用邮箱或中国大陆 11 位手机号注册。'
      : '当前仅支持邮箱验证码注册。';
  }
  if (registrationAccount.value.kind === 'phone') {
    return auth.capabilities.phoneRegistration
      ? '手机号格式正确，将通过短信验证码注册。'
      : '短信服务未开启，当前仅支持邮箱注册。';
  }
  return registrationAccount.value.message;
});
const canRequestRegistrationCode = computed((): boolean => {
  if (submitting.value || codeSending.value || codeResendAfterSeconds.value > 0) return false;
  return registrationAccount.value.kind === 'email'
    || (isSmsRegistration.value && registrationCaptcha.value.trim().length === 4);
});
const registrationBlockReason = computed((): string => {
  if (registrationAccount.value.kind === 'empty') {
    return auth.capabilities.phoneRegistration ? '请先填写邮箱地址或中国大陆 11 位手机号。' : '请先填写邮箱地址。';
  }
  if (registrationAccount.value.kind === 'invalid') {
    return registrationAccount.value.message;
  }
  if (registrationAccount.value.kind === 'phone' && !auth.capabilities.phoneRegistration) {
    return registrationHint.value;
  }
  if (codeSending.value) return '请等待验证码发送完成。';
  if (isSmsRegistration.value && !smsChallengeValid.value) {
    return smsChallengeId.value ? '短信验证码已过期，请重新获取。' : '请先获取当前手机号的短信验证码。';
  }
  if (!isSmsRegistration.value && verificationRecipient.value !== registrationAccount.value.normalized) {
    return '请先向当前邮箱获取验证码。';
  }
  if (!/^\d{6}$/.test(verificationCode.value)) {
    return isSmsRegistration.value ? '请输入短信中的 6 位验证码。' : '请输入邮件中的 6 位验证码。';
  }
  if (password.value.length < 8) {
    return '密码至少需要 8 个字符。';
  }
  if (!/\p{L}/u.test(password.value) || !/\d/.test(password.value)) {
    return '密码须同时包含字母和数字。';
  }
  if (new TextEncoder().encode(password.value).length > 72) return '密码 UTF-8 编码不能超过 72 字节。';
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
  if (codeSending.value) {
    return '发送中…';
  }
  if (codeResendAfterSeconds.value > 0) {
    return `${codeResendAfterSeconds.value} 秒后重发`;
  }
  return isSmsRegistration.value ? '获取短信验证码' : '获取邮箱验证码';
});

const title = computed((): string => {
  if (registrationSucceeded.value) {
    return '注册完成';
  }
  if (view.value === 'register') {
    return '创建账号';
  }
  if (view.value === 'sms') return '短信登录';
  if (view.value === 'phone-password') return '手机号密码登录';
  if (view.value === 'qr') return '扫码登录';
  return loginAsUsername.value ? '用户名登录' : '手机号或邮箱登录';
});

const close = (): void => {
  resetRegistrationVerification();
  password.value = '';
  confirmPassword.value = '';
  showPassword.value = false;
  showConfirmPassword.value = false;
  emit('update:modelValue', false);
};

const resetFields = (): void => {
  resetRegistrationVerification();
  account.value = '';
  loginAsUsername.value = false;
  password.value = '';
  confirmPassword.value = '';
  showPassword.value = false;
  showConfirmPassword.value = false;
  agreementAccepted.value = false;
  registrationSucceeded.value = false;
  errorMessage.value = '';
  statusMessage.value = '';
};

/** 账号、视图或弹窗变化后不能继续使用旧验证码，迟到响应也不能重新激活授权。 */
function resetRegistrationVerification(): void {
  registrationRevision += 1;
  stopResendTimer();
  resetSmsChallenge();
  registrationCaptcha.value = '';
  verificationCode.value = '';
  verificationRecipient.value = '';
}

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
watch(view, resetFields);
watch(account, () => { loginAsUsername.value = false; });
watch(() => props.modelValue, (open) => {
  if (open) {
    syncView();
    void auth.loadCapabilities();
  } else {
    resetRegistrationVerification();
  }
}, { immediate: true });
watch(() => registrationAccount.value.kind + ':' + registrationAccount.value.normalized, () => {
  if (view.value === 'register') {
    resetRegistrationVerification();
    statusMessage.value = '';
    errorMessage.value = '';
  }
});

onScopeDispose(stopResendTimer);

const handleRequestCode = async (): Promise<void> => {
  if (!canRequestRegistrationCode.value || view.value !== 'register') return;
  const recipient = registrationAccount.value.normalized;
  const sendingSms = isSmsRegistration.value;
  const revision = registrationRevision;
  verificationCode.value = '';
  verificationRecipient.value = '';
  errorMessage.value = '';
  statusMessage.value = '';
  try {
    if (sendingSms) {
      const issued = await sendSmsChallenge(() => requestSmsChallenge({
        phone: recipient,
        purpose: 'REGISTER',
        captcha: registrationCaptcha.value.trim(),
      }));
      if (issued && revision === registrationRevision) statusMessage.value = '短信验证码已发送，5 分钟内有效。';
    } else {
      requestingCode.value = true;
      const response = await requestRegistrationCode({ email: recipient });
      if (revision !== registrationRevision || !props.modelValue || view.value !== 'register') return;
      verificationRecipient.value = recipient;
      startResendTimer(response.data.resendAfterSeconds);
      statusMessage.value = `邮箱验证码已发送，${Math.ceil(response.data.expiresInSeconds / 60)} 分钟内有效。`;
    }
  } catch (error: unknown) {
    if (revision === registrationRevision) errorMessage.value = getApiErrorMessage(error);
  } finally {
    requestingCode.value = false;
    // 图形验证码由后端一次性消费；短信发送尝试结束后刷新，不要求再次填写才能完成注册。
    if (sendingSms && revision === registrationRevision && props.modelValue) {
      void registrationCaptchaImage.value?.refresh();
    }
  }
};

const refreshCaptcha = async (): Promise<void> => {
  captcha.value = '';
  try {
    const response = await httpClient.get<Blob>('/api/v1/auth/captcha', {
      responseType: 'blob',
      headers: { Accept: 'image/png' },
    });
    if (captchaUrl.value.startsWith('blob:')) {
      URL.revokeObjectURL(captchaUrl.value);
    }
    captchaUrl.value = URL.createObjectURL(response.data);
  } catch (error: unknown) {
    errorMessage.value = getApiErrorMessage(error);
  }
};

watch(
  () => props.modelValue && view.value === 'password',
  (visible) => {
    if (visible) {
      void refreshCaptcha();
    }
  },
  { immediate: true },
);

const handleSubmit = async (event: Event): Promise<void> => {
  event.preventDefault();
  if (submitting.value || view.value === 'qr') {
    return;
  }
  if (view.value === 'register') {
    if (!canSubmitRegistration.value) {
      errorMessage.value = registrationBlockReason.value;
      return;
    }
  }
  submitting.value = true;
  errorMessage.value = '';
  try {
    if (view.value === 'register') {
      if (isSmsRegistration.value) {
        await auth.registerPhone({
          phone: registrationAccount.value.normalized,
          challengeId: smsChallengeId.value,
          verificationCode: verificationCode.value,
          password: password.value,
        });
      } else {
        await auth.register({
          email: registrationAccount.value.normalized,
          verificationCode: verificationCode.value,
          password: password.value,
        });
      }
      registrationSucceeded.value = true;
      return;
    }

    // 后端只在显式 PHONE 时识别手机号；数字用户名允许用户明确切换，不能靠失败后重放登录请求猜测。
    const phone = loginAsUsername.value ? undefined : loginPhone.value;
    await auth.login({
      identifier: phone ?? account.value.trim(),
      identityType: phone ? 'PHONE' : undefined,
      password: password.value,
      captcha: captcha.value.trim(),
    });
    // 新身份始终从干净的应用内存开始，不复用另一账号的计划和文件。
    window.location.replace('/workbench');
  } catch (error: unknown) {
    errorMessage.value = getApiErrorMessage(error);
    if (view.value === 'password') {
      captcha.value = '';
      void refreshCaptcha();
    }
  } finally {
    if (view.value === 'password') {
      password.value = '';
    }
    submitting.value = false;
  }
};

const enterWorkbench = (): void => {
  window.location.replace('/workbench');
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
        :class="{ 'login-modal__panel--register': view === 'register' && !registrationSucceeded }"
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

        <div v-if="registrationSucceeded" class="login-modal__registration-success" role="status">
          <span class="login-modal__success-mark" aria-hidden="true">✓</span>
          <h3>账号创建成功，已自动登录</h3>
          <p v-if="!auth.user?.phoneBound">建议绑定手机号，补充账户联系方式。</p>
          <small v-if="!auth.user?.phoneBound">{{ auth.capabilities.phoneBinding ? '可在“设置 → 账户安全”验证并绑定手机号。' : '当前手机号短信验证和绑定入口尚未开放，暂时无法完成绑定。' }}</small>
          <button class="login-modal__submit" type="button" @click="enterWorkbench">
            进入工作台
          </button>
        </div>
        <div v-else-if="view === 'qr'" class="login-modal__qr">
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

        <PhoneAuthPanel v-else-if="phoneView" :key="phoneView" :mode="phoneView" @success="enterWorkbench" />
        <div
          v-else
          class="login-modal__account-layout"
          :class="{ 'login-modal__account-layout--register': view === 'register' }"
        >
          <form class="login-modal__form" @submit="handleSubmit">
            <label>
              {{ view === 'register' ? (auth.capabilities.phoneRegistration ? '邮箱/手机号' : '邮箱') : loginAccountLabel }}
              <input
                ref="accountInput"
                v-model="account"
                type="text"
                required
                maxlength="320"
                name="account"
                autocomplete="username"
                :aria-label="view === 'register' ? (auth.capabilities.phoneRegistration ? '邮箱/手机号' : '邮箱') : loginAccountLabel"
                :disabled="submitting || (view === 'register' && codeSending)"
                :placeholder="view === 'register' ? (auth.capabilities.phoneRegistration ? '请输入邮箱地址或手机号' : '请输入邮箱地址') : loginAccountPlaceholder"
              >
              <small
                v-if="view === 'register'"
                class="login-modal__field-hint"
                :class="{
                  'login-modal__field-hint--error': registrationAccount.kind === 'invalid',
                  'login-modal__field-hint--notice': registrationAccount.kind === 'phone' && !auth.capabilities.phoneRegistration,
                }"
              >
                {{ registrationHint }}
              </small>
            </label>
            <div v-if="view === 'password' && loginPhone" class="login-modal__field-hint" aria-live="polite">
              {{ loginAsUsername ? '当前按用户名登录。' : '已识别为手机号。' }}
              <ElButton link type="primary" :disabled="submitting" @click="loginAsUsername = !loginAsUsername">
                {{ loginAsUsername ? '使用手机号登录' : '使用用户名登录' }}
              </ElButton>
            </div>
            <!-- 短信发码前需要图形验证码；邮箱注册继续沿用原有验证流程。 -->
            <div v-if="view === 'register' && isSmsRegistration" class="login-modal__captcha-field">
              <span>图形验证码</span>
              <ImageCaptcha ref="registrationCaptchaImage" v-model="registrationCaptcha" />
            </div>
            <label v-if="view === 'register'">
              {{ isSmsRegistration ? '短信验证码' : '邮箱验证码' }}
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
                  :aria-label="isSmsRegistration ? '短信验证码' : '邮箱验证码'"
                  placeholder="请输入 6 位验证码"
                >
                <button
                  type="button"
                  :disabled="!canRequestRegistrationCode"
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
                已输入 {{ password.length }} 个字符，至少 8 位，且须同时包含字母和数字。
              </small>
            </label>
            <label v-if="view === 'password'">
              图形验证码
              <span class="login-modal__code-row">
                <input
                  v-model="captcha"
                  type="text"
                  required
                  maxlength="4"
                  name="captcha"
                  autocomplete="off"
                  placeholder="请输入图中字符"
                >
                <button
                  class="login-modal__captcha"
                  type="button"
                  aria-label="刷新图形验证码"
                  @click="refreshCaptcha"
                >
                  <img v-if="captchaUrl" :src="captchaUrl" alt="">
                </button>
              </span>
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
              验证码 5 分钟内有效，60 秒后可重发；密码至少 8 位，且须同时包含字母和数字。
            </small>
            <small v-else>使用已注册或绑定的手机号、邮箱及密码登录；管理员也可使用用户名。</small>
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

        <footer v-if="!registrationSucceeded" class="login-modal__footer">
          <ElButton v-if="view !== 'register'" link type="primary" @click="view = 'register'">立即注册</ElButton>
          <ElButton v-if="auth.capabilities.smsLogin && view !== 'sms'" link type="primary" @click="view = 'sms'">短信登录</ElButton>
          <ElButton v-if="auth.capabilities.smsLogin && view !== 'phone-password'" link type="primary" @click="view = 'phone-password'">手机号密码登录</ElButton>
          <button
            v-if="view === 'qr'"
            type="button"
            @click="view = 'password'"
          >
            使用手机号或邮箱登录
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
            已有账号？使用手机号或邮箱登录
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

.login-modal__registration-success {
  display: grid;
  justify-items: center;
  gap: 12px;
  text-align: center;
}

.login-modal__success-mark {
  display: grid;
  width: 48px;
  height: 48px;
  place-items: center;
  border-radius: 50%;
  color: #fff;
  font-size: 28px;
  background: #27ae72;
}

.login-modal__registration-success h3,
.login-modal__registration-success p,
.login-modal__registration-success small {
  margin: 0;
}

.login-modal__registration-success h3 {
  color: var(--text-primary);
  font-size: 18px;
}

.login-modal__registration-success p {
  color: var(--text-secondary);
  font-size: 14px;
}

.login-modal__registration-success small {
  color: var(--text-muted);
  font-size: 13px;
  line-height: 1.6;
}

.login-modal__registration-success .login-modal__submit {
  width: 100%;
  margin-top: 8px;
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

.login-modal__form label,
.login-modal__captcha-field {
  display: grid;
  gap: 6px;
  color: var(--text-primary);
  font-size: 14px;
  font-weight: 600;
}

.login-modal__captcha-field :deep(.el-input) {
  min-width: 0;
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

.login-modal__captcha {
  display: grid;
  padding: 0;
  overflow: hidden;
  height: 48px;
  border-radius: var(--radius-sm);
  background: #e8f0fb;
}

.login-modal__captcha img {
  display: block;
  width: 160px;
  height: 48px;
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
  flex-wrap: wrap;
  gap: 12px;
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
