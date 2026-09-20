<script setup lang="ts">
import { computed, ref, watch } from 'vue';

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

const view = ref<AuthView>('qr');
const account = ref('');
const password = ref('');
const confirmPassword = ref('');

const title = computed((): string => {
  if (view.value === 'register') {
    return '创建账号';
  }
  return view.value === 'qr' ? '扫码登录' : '账号登录';
});

const close = (): void => {
  emit('update:modelValue', false);
};

const resetFields = (): void => {
  account.value = '';
  password.value = '';
  confirmPassword.value = '';
};

const syncView = (): void => {
  view.value = props.mode === 'register' ? 'register' : 'qr';
  resetFields();
};

watch(() => props.mode, syncView);
watch(() => props.modelValue, (open) => {
  if (open) {
    syncView();
  }
});

const handleSubmit = (event: Event): void => {
  event.preventDefault();
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

      <section class="login-modal__panel" role="document">
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

        <form v-else class="login-modal__form" @submit="handleSubmit">
          <label>
            邮箱 / 手机号
            <input
              v-model="account"
              type="text"
              name="account"
              autocomplete="username"
              placeholder="请输入邮箱或手机号"
            >
          </label>
          <label>
            密码
            <input
              v-model="password"
              type="password"
              name="password"
              autocomplete="current-password"
              placeholder="请输入密码"
            >
          </label>
          <label v-if="view === 'register'">
            确认密码
            <input
              v-model="confirmPassword"
              type="password"
              name="confirmPassword"
              autocomplete="new-password"
              placeholder="再次输入密码"
            >
          </label>
          <button class="login-modal__submit" type="submit">
            {{ view === 'register' ? '注册' : '登录' }}
          </button>
          <small>按钮仅用于界面演示，不会提交到后端。</small>
        </form>

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
            返回扫码登录
          </button>
          <button
            v-else
            type="button"
            @click="view = 'qr'"
          >
            返回扫码登录
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
  font-size: 12px;
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
  color: var(--text-primary);
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
  font-size: 12px;
}

.login-modal__form {
  display: grid;
  gap: 12px;
}

.login-modal__form label {
  display: grid;
  gap: 6px;
  color: var(--text-primary);
  font-size: 13px;
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
</style>
