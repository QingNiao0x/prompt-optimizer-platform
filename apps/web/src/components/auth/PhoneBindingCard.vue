<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { ElAlert, ElButton, ElForm, ElFormItem, ElInput, ElMessage } from 'element-plus';
import { useAuthStore } from '@/stores/auth';
import { normalizeMainlandPhone } from '@/features/auth/registrationAccount';
import { useSmsChallenge } from '@/features/auth/useSmsChallenge';
import { requestPhoneBinding } from '@/services/authApi';
import { getApiErrorMessage } from '@/services/http';
import ImageCaptcha from './ImageCaptcha.vue';

const auth = useAuthStore();
const opened = ref(false);
const input = ref('');
const phone = computed(() => normalizeMainlandPhone(input.value));
const password = ref('');
const code = ref('');
const captcha = ref('');
const error = ref('');
const busy = ref(false);
const captchaImage = ref<InstanceType<typeof ImageCaptcha>>();
const { challengeId, remaining, sending, valid, send, reset } = useSmsChallenge(phone);
watch(phone, () => { code.value = ''; error.value = ''; });
onMounted(() => { void auth.loadCapabilities(); });

const requestCode = async (): Promise<void> => {
  if (!phone.value) return;
  const payload = { phone: phone.value, currentPassword: password.value, captcha: captcha.value };
  error.value = '';
  try {
    if (await send(() => requestPhoneBinding(payload))) ElMessage.success('绑定验证码已发送，5 分钟内有效。');
  } catch (failure: unknown) { error.value = getApiErrorMessage(failure); }
  finally { void captchaImage.value?.refresh(); }
};

/** 只传号码和重新认证凭据；目标账户由后端 CurrentActor 决定。 */
const submit = async (): Promise<void> => {
  if (!phone.value || !valid.value || !/^[0-9]{6}$/.test(code.value) || busy.value) return;
  busy.value = true;
  error.value = '';
  try {
    await auth.bindPhone({ phone: phone.value, challengeId: challengeId.value, verificationCode: code.value, currentPassword: password.value });
    reset(); password.value = ''; code.value = ''; input.value = ''; opened.value = false;
    ElMessage.success('手机号绑定成功，原有账户和历史记录保持不变。');
  } catch (failure: unknown) { error.value = getApiErrorMessage(failure); }
  finally { busy.value = false; }
};
</script>

<template>
  <section class="phone-security" aria-labelledby="phone-security-title">
    <h2 id="phone-security-title">账户安全</h2>
    <p v-if="auth.user?.phoneBound">已绑定手机号：{{ auth.user.maskedPhone }}</p>
    <template v-else>
      <p>尚未绑定手机号。建议绑定手机号，完善账户信息；邮箱和手机号将登录同一个账号。</p>
      <ElButton v-if="auth.capabilities.phoneBinding && !opened" type="primary" plain @click="opened = true">绑定手机号</ElButton>
      <small v-if="!auth.capabilities.phoneBinding">当前环境尚未开放短信绑定。</small>
    </template>
    <ElForm v-if="opened && !auth.user?.phoneBound" label-position="top" class="binding-form" @submit.prevent="submit">
      <ElAlert title="仅支持首次绑定，不会合并账号或转移其他账号的数据。" type="info" :closable="false" />
      <ElFormItem label="当前密码">
        <ElInput v-model="password" aria-label="当前密码" type="password" show-password autocomplete="current-password" maxlength="200" />
      </ElFormItem>
      <ElFormItem label="待绑定手机号">
        <ElInput v-model="input" aria-label="待绑定手机号" placeholder="中国大陆手机号" autocomplete="tel" maxlength="32" :disabled="sending || busy" />
        <small v-if="input && !phone">请输入有效的中国大陆手机号。</small>
      </ElFormItem>
      <ElFormItem label="图形验证码"><ImageCaptcha ref="captchaImage" v-model="captcha" /></ElFormItem>
      <ElFormItem label="短信验证码">
        <div class="binding-code">
          <ElInput v-model="code" aria-label="短信验证码" autocomplete="one-time-code" inputmode="numeric" maxlength="6" />
          <ElButton :loading="sending" :disabled="!phone || password.length < 8 || captcha.length !== 4 || remaining > 0 || busy" @click="requestCode">
            {{ remaining > 0 ? `${remaining} 秒后重发` : '获取短信验证码' }}
          </ElButton>
        </div>
      </ElFormItem>
      <ElAlert v-if="error" :title="error" type="error" :closable="false" role="alert" />
      <ElButton type="primary" native-type="submit" :loading="busy" :disabled="!valid || !/^[0-9]{6}$/.test(code) || password.length < 8 || sending">确认绑定</ElButton>
    </ElForm>
  </section>
</template>

<style scoped>
.phone-security { padding: 24px; border: 1px solid var(--glass-border); border-radius: var(--radius-md); background: var(--surface-panel); box-shadow: var(--shadow-panel); }
.phone-security h2 { margin: 0 0 12px; }
.phone-security p, .phone-security small { color: var(--text-secondary); line-height: 1.7; }
.binding-form { max-width: 520px; display: grid; gap: 14px; margin-top: 20px; }
.binding-form .el-form-item { margin-bottom: 0; }
.binding-form :deep(.image-captcha), .binding-code { width: 100%; display: flex; gap: 10px; }
</style>
