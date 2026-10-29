<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { ElAlert, ElButton, ElForm, ElFormItem, ElInput, ElMessage, ElMessageBox, ElOption, ElSelect, ElSkeleton } from 'element-plus';

import { validRegistrationPassword } from '@/features/auth/registrationAccount';
import { bindSettingsEmail, changeSettingsPassword, deactivateSettingsAccount, logoutAllSettingsSessions, requestSettingsEmailCode, saveSettingsUsername } from '@/services/systemSettingsApi';
import { getApiErrorMessage } from '@/services/http';
import { useAuthStore } from '@/stores/auth';
import { useLocaleStore } from '@/stores/locale';
import { useSystemSettingsStore } from '@/stores/systemSettings';
import type { InterfaceLocale, SettingsEmailChallenge } from '@/types/systemSettings';

const auth = useAuthStore();
const language = useLocaleStore();
const settings = useSystemSettingsStore();
const t = language.t;
const displayName = ref('');
const email = ref('');
const emailPassword = ref('');
const verificationCode = ref('');
const username = ref('');
const usernamePassword = ref('');
const passwordChangeCurrent = ref('');
const newPassword = ref('');
const confirmNewPassword = ref('');
const securityPassword = ref('');
const confirmation = ref('');
const busy = ref('');
const errorMessage = ref('');
const loading = ref(false);
const now = ref(Date.now());
const resendAt = ref(0);
const challenge = ref<(SettingsEmailChallenge & { email: string; expiresAt: number })>();
let active = true;
let identityRevision = 0;
let clock: ReturnType<typeof setInterval> | undefined;

const normalizedEmail = computed(() => email.value.trim().toLowerCase());
const validEmail = computed(() => /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(normalizedEmail.value) && normalizedEmail.value.length <= 254);
const validUsername = computed(() => /^[a-zA-Z0-9][a-zA-Z0-9._-]{2,31}$/.test(username.value.trim()));
const cooldown = computed(() => Math.max(0, Math.ceil((resendAt.value - now.value) / 1000)));
const validChallenge = computed(() => challenge.value && challenge.value.email === normalizedEmail.value && challenge.value.expiresAt > now.value);
const hasPassword = (value: string): boolean => value.length > 0;
const current = (revision: number): boolean => active && revision === identityRevision && auth.isAuthenticated;

watch(() => settings.view, (value, previous) => {
  if (!value) return;
  // 语言与昵称共用服务端投影；保存一项设置不能覆盖另一表单尚未提交的草稿。
  if (!previous || displayName.value === previous.displayName || busy.value === 'profile') displayName.value = value.displayName;
  if (!previous || normalizedEmail.value === (previous.email ?? '')) email.value = value.email ?? '';
  if (!previous || username.value === (previous.username ?? '')) username.value = value.username ?? '';
}, { immediate: true });

// 挑战始终属于本次输入的邮箱；改目标立即移除旧授权，不因改目标重置已发生的发信冷却。
watch(normalizedEmail, () => { challenge.value = undefined; verificationCode.value = ''; });
watch(() => auth.user?.userId, () => {
  identityRevision += 1;
  challenge.value = undefined;
  emailPassword.value = usernamePassword.value = securityPassword.value = verificationCode.value = confirmation.value = '';
  passwordChangeCurrent.value = newPassword.value = confirmNewPassword.value = '';
}, { flush: 'sync' });

const load = async (): Promise<void> => {
  loading.value = true;
  errorMessage.value = '';
  const revision = identityRevision;
  try { await settings.load(); }
  catch (error: unknown) { if (current(revision)) errorMessage.value = getApiErrorMessage(error); }
  finally { if (active) loading.value = false; }
};

/** 同一面板串行执行变更；错误保留输入，迟到响应不能影响新的登录身份。 */
const perform = async (operation: string, task: (revision: number) => Promise<void>): Promise<void> => {
  if (busy.value || !auth.isAuthenticated) return;
  busy.value = operation;
  errorMessage.value = '';
  const revision = identityRevision;
  try { await task(revision); }
  catch (error: unknown) {
    if (current(revision)) {
      errorMessage.value = getApiErrorMessage(error);
      ElMessage.error(errorMessage.value);
    }
  } finally { if (active) busy.value = ''; }
};

const saveName = async (): Promise<void> => {
  const name = displayName.value.trim();
  if (!name || Array.from(name).length > 80) { errorMessage.value = t('invalidName'); return; }
  await perform('profile', async revision => {
    await settings.updateDisplayName(name);
    if (!current(revision)) return;
    // 重新读取服务器认证投影，不在浏览器拼接角色或替换身份；保存成功后的刷新失败单独提示。
    try { await auth.refreshUser(); }
    catch { if (current(revision)) ElMessage.warning(t('profileRefreshFailed')); return; }
    if (current(revision)) ElMessage.success(t('saved'));
  });
};

const saveLocale = async (value: InterfaceLocale): Promise<void> => {
  if (value !== 'en' && value !== 'zh-CN') return;
  await perform('locale', async revision => {
    await settings.updateLocale(value);
    if (current(revision)) ElMessage.success(t('saved'));
  });
};

const sendEmail = async (): Promise<void> => {
  if (!validEmail.value) { errorMessage.value = t('invalidEmail'); return; }
  if (!hasPassword(emailPassword.value)) { errorMessage.value = t('passwordRequired'); return; }
  if (cooldown.value || !settings.view?.emailBindingAvailable) return;
  const target = normalizedEmail.value;
  challenge.value = undefined;
  verificationCode.value = '';
  await perform('email-send', async revision => {
    const response = await requestSettingsEmailCode({ email: target, currentPassword: emailPassword.value });
    if (!current(revision)) return;
    now.value = Date.now();
    resendAt.value = now.value + Math.max(0, response.resendAfterSeconds) * 1000;
    if (normalizedEmail.value !== target) return;
    challenge.value = { ...response, email: target, expiresAt: now.value + response.expiresInSeconds * 1000 };
    ElMessage.success(t('emailSent'));
  });
};

/** 服务端已撤销全部会话后清空当前身份并完整离开页面，避免残留其他工具的私有内存。 */
const finishSecurityAction = (revision: number, destination: string, message: string): void => {
  if (!current(revision)) return;
  auth.clearLocalSession();
  ElMessage.success(message);
  window.location.replace(destination);
};

const bindEmail = async (): Promise<void> => {
  if (!validEmail.value) { errorMessage.value = t('invalidEmail'); return; }
  if (!hasPassword(emailPassword.value)) { errorMessage.value = t('passwordRequired'); return; }
  if (!validChallenge.value || !challenge.value || !/^\d{6}$/.test(verificationCode.value.trim())) { errorMessage.value = t('codeRequired'); return; }
  const payload = { email: normalizedEmail.value, currentPassword: emailPassword.value, challengeId: challenge.value.challengeId, verificationCode: verificationCode.value.trim() };
  await perform('email', async revision => {
    await bindSettingsEmail(payload);
    finishSecurityAction(revision, '/login?reason=security', t('relogin'));
  });
};

const saveUsername = async (): Promise<void> => {
  if (!validUsername.value) { errorMessage.value = t('invalidUsername'); return; }
  if (!hasPassword(usernamePassword.value)) { errorMessage.value = t('passwordRequired'); return; }
  const payload = { username: username.value.trim(), currentPassword: usernamePassword.value };
  await perform('username', async revision => {
    await saveSettingsUsername(payload);
    finishSecurityAction(revision, '/login?reason=security', t('relogin'));
  });
};

/** 复用注册密码规则；确认字段只在浏览器校验，不作为额外凭据提交。 */
const changePassword = async (): Promise<void> => {
  if (!hasPassword(passwordChangeCurrent.value)) { errorMessage.value = t('passwordRequired'); return; }
  if (!validRegistrationPassword(newPassword.value)) { errorMessage.value = t('invalidNewPassword'); return; }
  if (newPassword.value !== confirmNewPassword.value) { errorMessage.value = t('passwordMismatch'); return; }
  const payload = { currentPassword: passwordChangeCurrent.value, newPassword: newPassword.value };
  await perform('password', async revision => {
    await changeSettingsPassword(payload);
    finishSecurityAction(revision, '/login?reason=security', t('relogin'));
  });
};

const confirmSecurityAction = async (deactivate: boolean): Promise<void> => {
  if (busy.value || !hasPassword(securityPassword.value)) { errorMessage.value = t('passwordRequired'); return; }
  if (deactivate && (confirmation.value !== 'DELETE' || !settings.view?.accountDeletionAvailable)) return;
  const revision = identityRevision;
  try {
    await ElMessageBox.confirm(t(deactivate ? 'deactivateConfirm' : 'logoutConfirm'), t(deactivate ? 'deactivateTitle' : 'logoutTitle'), {
      type: 'warning', confirmButtonText: t(deactivate ? 'deactivate' : 'logoutAll'), cancelButtonText: t('cancel'),
      confirmButtonClass: deactivate ? 'el-button--danger' : '', distinguishCancelAndClose: true,
    });
  } catch { return; }
  // 确认框打开期间可能退出/切换身份；不把旧确认用于新账号。
  if (!current(revision) || (deactivate && confirmation.value !== 'DELETE')) return;
  await perform(deactivate ? 'deactivate' : 'logout-all', async expected => {
    if (deactivate) await deactivateSettingsAccount(securityPassword.value);
    else await logoutAllSettingsSessions(securityPassword.value);
    finishSecurityAction(expected, deactivate ? '/?account=deactivated' : '/login?reason=security', t(deactivate ? 'deactivated' : 'signedOut'));
  });
};

onMounted(() => {
  clock = setInterval(() => { now.value = Date.now(); }, 1000);
  void load();
});
onBeforeUnmount(() => {
  active = false;
  identityRevision += 1;
  if (clock) clearInterval(clock);
  emailPassword.value = usernamePassword.value = securityPassword.value = verificationCode.value = '';
  passwordChangeCurrent.value = newPassword.value = confirmNewPassword.value = '';
});
</script>

<template>
  <div class="system-settings" role="region" :aria-label="t('systemSettings')">
    <section class="system-card" aria-labelledby="general-settings-heading">
      <h2 id="general-settings-heading">{{ t('general') }}</h2>
      <p class="hint">{{ t('languageScope') }}</p>
      <label class="field-label" for="interface-language">{{ t('language') }}</label>
      <ElSelect id="interface-language" :model-value="language.locale" :aria-label="t('language')" :disabled="!settings.view || Boolean(busy)" class="language-select" @change="saveLocale">
        <ElOption label="中文" value="zh-CN" />
        <ElOption label="English" value="en" />
      </ElSelect>
    </section>

    <section class="system-card" aria-labelledby="account-settings-heading">
      <h2 id="account-settings-heading">{{ t('account') }}</h2>
      <ElSkeleton v-if="loading && !settings.view" animated :rows="3" :aria-label="t('loading')" />
      <div v-if="errorMessage" class="settings-error" role="alert">
        <ElAlert :title="errorMessage" type="error" :closable="false" show-icon />
        <ElButton v-if="!settings.view" :loading="loading" @click="load">{{ t('retry') }}</ElButton>
      </div>
      <div v-if="settings.view" class="account-grid">
        <ElForm class="account-form" data-testid="system-profile-form" label-position="top" :aria-label="t('displayName')" @submit.prevent="saveName">
          <ElFormItem :label="t('displayName')" for="settings-display-name">
            <ElInput id="settings-display-name" v-model="displayName" autocomplete="nickname" maxlength="80" :disabled="Boolean(busy)" />
          </ElFormItem>
          <p class="hint">{{ t('displayNameHint') }}</p>
          <ElButton type="primary" native-type="submit" :loading="busy === 'profile'" :disabled="Boolean(busy) || !displayName.trim()">{{ t('saveDisplayName') }}</ElButton>
        </ElForm>

        <ElForm class="account-form" data-testid="system-email-form" label-position="top" :aria-label="t('email')" @submit.prevent="bindEmail">
          <h3>{{ t('email') }}</h3>
          <p class="hint">{{ t('emailHint') }}</p>
          <ElAlert v-if="!settings.view.emailBindingAvailable" type="info" :closable="false" :title="t('emailUnavailable')" />
          <ElFormItem :label="t('emailAddress')" for="settings-email">
            <ElInput id="settings-email" v-model="email" type="email" autocomplete="email" maxlength="254" :disabled="Boolean(busy) || !settings.view.emailBindingAvailable" />
          </ElFormItem>
          <ElFormItem :label="t('currentPassword')" for="settings-email-password">
            <ElInput id="settings-email-password" v-model="emailPassword" :aria-label="t('emailPassword')" type="password" autocomplete="current-password" show-password :disabled="Boolean(busy) || !settings.view.emailBindingAvailable" />
          </ElFormItem>
          <ElFormItem :label="t('emailCode')" for="settings-email-code">
            <div class="code-row">
              <ElInput id="settings-email-code" v-model="verificationCode" inputmode="numeric" autocomplete="one-time-code" maxlength="6" :disabled="Boolean(busy) || !settings.view.emailBindingAvailable" />
              <ElButton :loading="busy === 'email-send'" :disabled="Boolean(busy) || !validEmail || !hasPassword(emailPassword) || cooldown > 0 || !settings.view.emailBindingAvailable" @click="sendEmail">{{ cooldown ? `${cooldown}s` : t('sendEmailCode') }}</ElButton>
            </div>
          </ElFormItem>
          <p v-if="challenge && !validChallenge" class="hint" role="status">{{ t('emailExpired') }}</p>
          <ElButton type="primary" native-type="submit" :loading="busy === 'email'" :disabled="Boolean(busy) || !validChallenge || !/^\d{6}$/.test(verificationCode.trim()) || !hasPassword(emailPassword)">{{ t('bindEmail') }}</ElButton>
        </ElForm>

        <ElForm class="account-form" data-testid="system-username-form" label-position="top" :aria-label="t('username')" @submit.prevent="saveUsername">
          <h3>{{ t('username') }}</h3>
          <p class="hint">{{ t('usernameHint') }}</p>
          <ElFormItem :label="t('username')" for="settings-username">
            <ElInput id="settings-username" v-model="username" autocomplete="username" maxlength="32" :disabled="Boolean(busy)" />
          </ElFormItem>
          <ElFormItem :label="t('currentPassword')" for="settings-username-password">
            <ElInput id="settings-username-password" v-model="usernamePassword" :aria-label="t('usernamePassword')" type="password" autocomplete="current-password" show-password :disabled="Boolean(busy)" />
          </ElFormItem>
          <ElButton type="primary" native-type="submit" :loading="busy === 'username'" :disabled="Boolean(busy) || !validUsername || !hasPassword(usernamePassword)">{{ t('saveUsername') }}</ElButton>
        </ElForm>

        <ElForm class="account-form" data-testid="system-password-form" label-position="top" :aria-label="t('changePassword')" @submit.prevent="changePassword">
          <h3>{{ t('changePassword') }}</h3>
          <p class="hint">{{ t('passwordChangeHint') }}</p>
          <ElFormItem :label="t('currentPassword')" for="settings-password-current">
            <ElInput id="settings-password-current" v-model="passwordChangeCurrent" :aria-label="t('passwordChangeCurrent')" type="password" autocomplete="current-password" show-password :disabled="Boolean(busy)" />
          </ElFormItem>
          <ElFormItem :label="t('newPassword')" for="settings-password-new">
            <ElInput id="settings-password-new" v-model="newPassword" type="password" autocomplete="new-password" show-password :disabled="Boolean(busy)" />
          </ElFormItem>
          <p v-if="newPassword && !validRegistrationPassword(newPassword)" class="hint" role="status">{{ t('invalidNewPassword') }}</p>
          <ElFormItem :label="t('confirmNewPassword')" for="settings-password-confirm">
            <ElInput id="settings-password-confirm" v-model="confirmNewPassword" type="password" autocomplete="new-password" show-password :disabled="Boolean(busy)" />
          </ElFormItem>
          <p v-if="confirmNewPassword && newPassword !== confirmNewPassword" class="hint" role="status">{{ t('passwordMismatch') }}</p>
          <ElButton type="primary" native-type="submit" :loading="busy === 'password'" :disabled="Boolean(busy) || !hasPassword(passwordChangeCurrent) || !validRegistrationPassword(newPassword) || newPassword !== confirmNewPassword">{{ t('changePassword') }}</ElButton>
        </ElForm>
      </div>
    </section>

    <section v-if="settings.view" class="system-card security-card" aria-labelledby="security-settings-heading">
      <h2 id="security-settings-heading">{{ t('security') }}</h2>
      <ElForm data-testid="system-security-form" label-position="top" :aria-label="t('security')" @submit.prevent>
        <ElFormItem :label="t('currentPassword')" for="settings-security-password">
          <ElInput id="settings-security-password" v-model="securityPassword" class="security-password" :aria-label="t('securityPassword')" type="password" autocomplete="current-password" show-password :disabled="Boolean(busy)" />
        </ElFormItem>
        <div class="security-action">
          <p class="hint">{{ t('logoutAllHint') }}</p>
          <ElButton :loading="busy === 'logout-all'" :disabled="Boolean(busy) || !hasPassword(securityPassword)" @click="confirmSecurityAction(false)">{{ t('logoutAll') }}</ElButton>
        </div>
        <div class="deactivation-action">
          <h3>{{ t('deactivate') }}</h3>
          <p class="hint">{{ t('deactivateHint') }}</p>
          <ElAlert v-if="!settings.view.accountDeletionAvailable" :title="t('deletionUnavailable')" :closable="false" type="warning" />
          <ElFormItem :label="t('confirmation')" for="settings-deactivation-confirmation">
            <ElInput id="settings-deactivation-confirmation" v-model="confirmation" autocomplete="off" placeholder="DELETE" class="security-password" :disabled="Boolean(busy) || !settings.view.accountDeletionAvailable" />
          </ElFormItem>
          <p class="hint">{{ t('confirmationHint') }}</p>
          <ElButton type="danger" plain :loading="busy === 'deactivate'" :disabled="Boolean(busy) || !hasPassword(securityPassword) || confirmation !== 'DELETE' || !settings.view.accountDeletionAvailable" @click="confirmSecurityAction(true)">{{ t('deactivate') }}</ElButton>
        </div>
      </ElForm>
    </section>

    <section class="system-card" aria-labelledby="compliance-settings-heading">
      <h2 id="compliance-settings-heading">{{ t('agreements') }}</h2>
      <p class="hint">{{ t('agreementsHint') }} <span v-if="language.locale === 'en'">{{ t('legalLanguage') }}</span></p>
      <div class="agreement-links">
        <RouterLink to="/terms">{{ t('terms') }}</RouterLink>
        <RouterLink to="/privacy">{{ t('privacy') }}</RouterLink>
      </div>
    </section>
  </div>
</template>

<style scoped>
.system-settings { display: grid; gap: 18px; margin-bottom: 18px; min-width: 0; }
.system-card { min-width: 0; padding: 24px; border: 1px solid var(--glass-border); border-radius: var(--radius-md); background: var(--glass-bg-strong); box-shadow: var(--glass-shadow); backdrop-filter: blur(16px) saturate(1.2); }
h2 { margin: 0 0 12px; color: var(--text-primary); font-size: 21px; }
h3 { margin: 0 0 10px; color: var(--text-primary); font-size: 16px; }
.hint { margin: 0 0 14px; color: var(--text-secondary); font-size: 13px; line-height: 1.7; overflow-wrap: anywhere; }
.field-label { display: block; margin: 0 0 8px; color: var(--text-primary); font-size: 14px; }
.language-select { width: min(100%, 260px); }
.account-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 24px; }
.account-form { min-width: 0; padding-top: 18px; border-top: 1px solid var(--glass-border-subtle); }
.account-form:first-child { grid-column: 1 / -1; padding-top: 0; border: 0; }
.account-form:first-child :deep(.el-input) { max-width: 420px; }
.settings-error { display: grid; gap: 12px; margin-bottom: 16px; }
.settings-error > .el-button { justify-self: start; }
.code-row { display: flex; width: 100%; gap: 8px; min-width: 0; }
.code-row .el-input { min-width: 0; flex: 1; }
.code-row .el-button { flex-shrink: 0; margin: 0; }
.security-password { max-width: 420px; }
.security-action { display: flex; gap: 20px; align-items: center; justify-content: space-between; padding-bottom: 20px; }
.security-action .hint { margin: 0; }
.deactivation-action { padding-top: 22px; border-top: 1px solid color-mix(in srgb, var(--el-color-danger) 35%, var(--glass-border)); }
.deactivation-action h3 { color: var(--el-color-danger); }
.agreement-links { display: flex; flex-wrap: wrap; gap: 16px; }
.agreement-links a { display: inline-flex; align-items: center; min-height: 44px; color: var(--accent); font-size: 14px; }
.agreement-links a:focus-visible { outline: 2px solid var(--accent); outline-offset: 3px; }
:deep(.el-form-item__label) { color: var(--text-primary); }
:deep(.el-button) { min-height: 40px; }
@media (max-width: 760px) {
  .account-grid { grid-template-columns: minmax(0, 1fr); }
  .system-card { padding: 18px; }
  .security-action { align-items: flex-start; flex-direction: column; gap: 12px; }
}
@media (max-width: 420px) {
  .system-card { padding: 16px; }
  .code-row { flex-direction: column; }
  .code-row .el-button { align-self: stretch; }
  :deep(.el-button) { max-width: 100%; min-height: 44px; white-space: normal; line-height: 1.5; }
}
</style>
