<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ElAlert, ElButton, ElForm, ElFormItem, ElInput, ElMessage } from 'element-plus';
import ImageCaptcha from './ImageCaptcha.vue';
import { useAuthStore } from '@/stores/auth';
import { normalizeMainlandPhone } from '@/features/auth/registrationAccount';
import { useSmsChallenge } from '@/features/auth/useSmsChallenge';
import { requestSmsChallenge } from '@/services/authApi';
import { getApiErrorMessage } from '@/services/http';

const props = defineProps<{ mode: 'sms' | 'phone-password' }>();
const emit = defineEmits<{ success: [] }>();
const auth = useAuthStore();
const input = ref('');
const phone = computed(() => normalizeMainlandPhone(input.value));
const password = ref('');
const captcha = ref('');
const code = ref('');
const busy = ref(false);
const error = ref('');
const captchaImage = ref<InstanceType<typeof ImageCaptcha>>();
const { challengeId, sending, remaining, valid, send, reset } = useSmsChallenge(phone);
const isPassword = computed(() => props.mode === 'phone-password');
const allowed = computed(() => !!phone.value && (isPassword.value
  ? password.value.length >= 8 && captcha.value.trim().length === 4
  : valid.value && /^[0-9]{6}$/.test(code.value)));
watch(phone, () => { code.value = ''; error.value = ''; });
watch(() => props.mode, () => { reset(); password.value = ''; code.value = ''; error.value = ''; });

/** 仅由用户点击触发发送，不自动重试；用途由当前界面固定。 */
const requestCode = async (): Promise<void> => {
  if (!phone.value) return;
  const recipient = phone.value;
  error.value = '';
  try {
    const issued = await send(() => requestSmsChallenge({ phone: recipient,
      purpose: 'LOGIN', captcha: captcha.value }));
    if (issued) ElMessage.success('短信已发送，5 分钟内有效。');
  } catch (failure: unknown) { error.value = getApiErrorMessage(failure); }
  finally { void captchaImage.value?.refresh(); }
};

const submit = async (): Promise<void> => {
  if (!allowed.value || busy.value || !phone.value) return;
  busy.value = true;
  error.value = '';
  try {
    if (isPassword.value) {
      await auth.login({ identifier: phone.value, identityType: 'PHONE', password: password.value, captcha: captcha.value });
    } else {
      const credentials = { phone: phone.value, challengeId: challengeId.value, verificationCode: code.value };
      await auth.loginSms(credentials);
    }
    emit('success');
  } catch (failure: unknown) {
    error.value = getApiErrorMessage(failure);
    if (isPassword.value) void captchaImage.value?.refresh();
  } finally { busy.value = false; }
};
</script>

<template>
  <ElForm class="phone-auth" label-position="top" @submit.prevent="submit">
    <ElAlert v-if="mode === 'sms'" title="仅登录已注册的普通账号；管理员请使用密码登录。" type="info" :closable="false" />
    <ElFormItem label="手机号">
      <ElInput v-model="input" aria-label="手机号" placeholder="中国大陆手机号" maxlength="32" autocomplete="tel" :disabled="busy || sending" />
      <small v-if="input && !phone" class="form-error">请输入有效的中国大陆手机号。</small>
    </ElFormItem>
    <ElFormItem label="图形验证码"><ImageCaptcha ref="captchaImage" v-model="captcha" /></ElFormItem>
    <ElFormItem v-if="!isPassword" label="短信验证码">
      <div class="code-row">
        <ElInput v-model="code" aria-label="短信验证码" maxlength="6" inputmode="numeric" autocomplete="one-time-code" />
        <ElButton :loading="sending" :disabled="busy || !phone || captcha.length !== 4 || remaining > 0" @click="requestCode">
          {{ remaining > 0 ? `${remaining} 秒后重发` : '获取短信验证码' }}
        </ElButton>
      </div>
    </ElFormItem>
    <ElFormItem v-if="isPassword" label="密码">
      <ElInput v-model="password" aria-label="密码" type="password" show-password maxlength="200" autocomplete="current-password" />
    </ElFormItem>
    <ElAlert v-if="error" :title="error" type="error" :closable="false" role="alert" />
    <ElButton type="primary" native-type="submit" :loading="busy" :disabled="!allowed || sending">
      登录
    </ElButton>
  </ElForm>
</template>

<style scoped>
.phone-auth { display: grid; gap: 12px; color: var(--text-primary); }
.phone-auth .el-form-item { margin-bottom: 0; }
.phone-auth small { color: var(--text-muted); line-height: 1.6; }
.phone-auth .form-error { color: var(--el-color-danger); }
.code-row { display: flex; width: 100%; gap: 10px; }
.phone-auth :deep(.el-form-item__content) { width: 100%; }
.phone-auth :deep(.image-captcha) { width: 100%; }
</style>
