<script setup lang="ts">
import { computed, onScopeDispose, ref, watch } from 'vue';
import { ElAlert, ElButton, ElForm, ElFormItem, ElInput, ElMessage } from 'element-plus';
import { useAuthStore } from '@/stores/auth';
import { normalizeMainlandPhone } from '@/features/auth/registrationAccount';
import { useSmsChallenge } from '@/features/auth/useSmsChallenge';
import { requestPhoneBinding } from '@/services/authApi';
import { getApiErrorMessage } from '@/services/http';
import ImageCaptcha from './ImageCaptcha.vue';

/** 注册引导按发码、核验分步显示；设置页保留原有完整表单。 */
const props = withDefaults(defineProps<{ sequential?: boolean }>(), { sequential: false });
const emit = defineEmits<{ success: [] }>();
const auth = useAuthStore();
const input = ref('');
const phone = computed(() => normalizeMainlandPhone(input.value));
const password = ref('');
const code = ref('');
const captcha = ref('');
const error = ref('');
const busy = ref(false);
const captchaImage = ref<InstanceType<typeof ImageCaptcha>>();
const { challengeId, remaining, sending, valid, send, reset } = useSmsChallenge(phone);
const available = computed(() => Boolean(auth.user) && auth.capabilities.phoneBinding && !auth.user?.phoneBound);
const canSend = computed(() => available.value && Boolean(phone.value) && password.value.length >= 8
  && captcha.value.trim().length === 4 && remaining.value === 0 && !sending.value && !busy.value);
const canBind = computed(() => available.value && valid.value && /^[0-9]{6}$/.test(code.value)
  && password.value.length >= 8 && !sending.value && !busy.value);
const collectingCode = computed(() => !props.sequential || Boolean(challengeId.value));
let revision = 0;
let disposed = false;

watch(phone, () => { code.value = ''; error.value = ''; });
// 切换账户或离开表单后，迟到的发送/绑定结果不能激活另一账户的界面。
watch(() => auth.user?.userId, () => {
  revision += 1;
  reset();
  input.value = ''; password.value = ''; code.value = ''; captcha.value = ''; error.value = '';
}, { flush: 'sync' });
onScopeDispose(() => {
  disposed = true;
  password.value = ''; code.value = ''; input.value = ''; captcha.value = '';
});

/** 发码使用已登录绑定接口；REGISTER 挑战不能用于给邮箱账号增加身份。 */
const requestCode = async (): Promise<void> => {
  if (!canSend.value || !phone.value) return;
  const currentRevision = revision;
  const payload = { phone: phone.value, currentPassword: password.value, captcha: captcha.value.trim() };
  error.value = '';
  try {
    const issued = await send(() => requestPhoneBinding(payload));
    if (!disposed && currentRevision === revision && issued) ElMessage.success('绑定验证码已发送，5 分钟内有效。');
  } catch (failure: unknown) {
    if (!disposed && currentRevision === revision) error.value = getApiErrorMessage(failure);
  } finally {
    if (!disposed && currentRevision === revision) void captchaImage.value?.refresh();
  }
};

/** 只提交挑战和重新认证凭据；身份归属、唯一性与事务消费均由后端决定。 */
const bind = async (): Promise<void> => {
  if (!canBind.value || !phone.value) return;
  const currentRevision = revision;
  const owner = auth.user?.userId;
  busy.value = true;
  error.value = '';
  try {
    await auth.bindPhone({ phone: phone.value, challengeId: challengeId.value,
      verificationCode: code.value, currentPassword: password.value });
    // 资料响应会让父组件移除已完成表单；仍应提示本账户成功，但绝不能提示另一身份的迟到结果。
    if (currentRevision !== revision || owner !== auth.user?.userId || !auth.user?.phoneBound) return;
    reset(); password.value = ''; code.value = ''; input.value = ''; captcha.value = '';
    ElMessage.success('手机号绑定成功，原有账户和历史记录保持不变。');
    emit('success');
  } catch (failure: unknown) {
    if (currentRevision === revision) error.value = getApiErrorMessage(failure);
  } finally { busy.value = false; }
};

/** 首步提交只发验证码，获得挑战后才允许进入绑定事务。 */
const submit = async (): Promise<void> => {
  if (props.sequential && !collectingCode.value) await requestCode();
  else await bind();
};
</script>

<template>
  <ElForm label-position="top" class="binding-form" @submit.prevent="submit">
    <ElAlert title="仅支持首次绑定，不会合并账号或转移其他账号的数据。" type="info" :closable="false" />
    <ElFormItem label="当前密码">
      <ElInput v-model="password" aria-label="当前密码" type="password" show-password autocomplete="current-password"
        maxlength="200" :disabled="sending || busy" />
    </ElFormItem>
    <ElFormItem label="待绑定手机号">
      <ElInput v-model="input" aria-label="待绑定手机号" placeholder="中国大陆手机号" autocomplete="tel"
        maxlength="32" :disabled="sending || busy" />
      <small v-if="input && !phone">请输入有效的中国大陆手机号。</small>
    </ElFormItem>
    <ElFormItem label="图形验证码"><ImageCaptcha ref="captchaImage" v-model="captcha" /></ElFormItem>
    <ElFormItem v-if="collectingCode" label="短信验证码">
      <div class="binding-code">
        <ElInput v-model="code" aria-label="短信验证码" autocomplete="one-time-code" inputmode="numeric"
          maxlength="6" :disabled="sending || busy" />
        <ElButton :loading="sending" :disabled="!canSend" @click="requestCode">
          {{ remaining > 0 ? `${remaining} 秒后重发` : '获取短信验证码' }}
        </ElButton>
      </div>
      <small v-if="challengeId && !valid" role="status">短信验证码已过期，请重新获取。</small>
    </ElFormItem>
    <ElAlert v-if="error" :title="error" type="error" :closable="false" role="alert" />
    <ElButton v-if="sequential && !collectingCode" type="primary" native-type="submit" :loading="sending" :disabled="!canSend">
      {{ remaining > 0 ? `${remaining} 秒后重发` : '发送短信验证码' }}
    </ElButton>
    <ElButton v-else type="primary" native-type="submit" :loading="busy" :disabled="!canBind">确认绑定</ElButton>
  </ElForm>
</template>

<style scoped>
.binding-form { width: 100%; max-width: 520px; display: grid; gap: 14px; margin-top: 20px; text-align: left; }
.binding-form .el-form-item { margin-bottom: 0; }
.binding-form small { color: var(--text-secondary); line-height: 1.7; }
.binding-form :deep(.image-captcha), .binding-code { width: 100%; display: flex; gap: 10px; }
.binding-code .el-input { min-width: 0; }
</style>
