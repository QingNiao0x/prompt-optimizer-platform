<script setup lang="ts">
import { onMounted, ref } from 'vue';

import BrandMark from '@/components/brand/BrandMark.vue';
import AccountMenu from '@/components/layout/AccountMenu.vue';
import { useAuthStore } from '@/stores/auth';

const auth = useAuthStore();
onMounted(() => { void auth.initialize().catch(() => undefined); });

interface Emits {
  (event: 'login'): void;
  (event: 'register'): void;
}

const emit = defineEmits<Emits>();
const menuOpen = ref(false);

const closeMenu = (): void => {
  menuOpen.value = false;
};
</script>

<template>
  <nav class="landing-nav" aria-label="首页导航">
    <RouterLink class="landing-nav__brand" to="/" aria-label="PromptOptimizer 首页">
      <BrandMark />
      <span>
        <strong>PromptOptimizer</strong>
        <small>PROMPT CONTEXT STUDIO</small>
      </span>
    </RouterLink>

    <div class="landing-nav__links">
      <a href="#features" @click="closeMenu">功能特性</a>
      <a href="#pricing" @click="closeMenu">定价</a>
    </div>

    <div class="landing-nav__actions">
      <AccountMenu />
      <button
        v-if="!auth.isAuthenticated"
        class="landing-nav__auth-btn landing-nav__auth-btn--ghost"
        type="button"
        @click="emit('login')"
      >
        登录
      </button>
      <button
        v-if="!auth.isAuthenticated"
        class="landing-nav__auth-btn landing-nav__auth-btn--accent"
        type="button"
        @click="emit('register')"
      >
        注册
      </button>
      <button
        class="landing-nav__menu"
        type="button"
        :aria-expanded="menuOpen"
        aria-controls="landing-nav-menu"
        aria-label="打开导航菜单"
        @click="menuOpen = !menuOpen"
      >
        <span></span>
        <span></span>
        <span></span>
      </button>
    </div>
  </nav>

  <div
    v-if="menuOpen"
    id="landing-nav-menu"
    class="landing-nav__drawer"
  >
    <a href="#features" @click="closeMenu">功能特性</a>
    <a href="#pricing" @click="closeMenu">定价</a>
  </div>
</template>

<style scoped>
.landing-nav {
  position: fixed;
  inset: 0 0 auto;
  z-index: 100;
  display: grid;
  height: 64px;
  grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr);
  align-items: center;
  padding: 0 24px;
  border-bottom: 1px solid var(--glass-border-subtle);
  background: var(--glass-bg);
  backdrop-filter: blur(16px) saturate(1.5);
  -webkit-backdrop-filter: blur(16px) saturate(1.5);
}

.landing-nav::before {
  position: absolute;
  inset: 0 0 auto;
  height: 1px;
  content: '';
  background: var(--glass-highlight);
}

.landing-nav__brand {
  display: inline-flex;
  align-items: center;
  justify-self: start;
  gap: 10px;
  min-width: 0;
  color: var(--text-primary);
  text-decoration: none;
}

.landing-nav__brand > span:last-child {
  display: grid;
}

.landing-nav strong {
  font-size: 16px;
  font-weight: 600;
  line-height: 1.25;
}

.landing-nav small {
  color: var(--text-muted);
  font-size: 11px;
  letter-spacing: 0.15em;
  line-height: 1.25;
}

.landing-nav__links {
  display: flex;
  align-items: center;
  justify-self: center;
  gap: 4px;
  padding: 4px;
  border: 1px solid var(--glass-border-subtle);
  border-radius: var(--radius-pill);
  background: var(--glass-bg-subtle);
}

.landing-nav__links a {
  min-height: 32px;
  padding: 6px 18px;
  border-radius: var(--radius-pill);
  color: var(--text-secondary);
  font-size: 14px;
  font-weight: 500;
  text-decoration: none;
  transition:
    color var(--duration-ui) var(--ease-standard),
    background-color var(--duration-ui) var(--ease-standard);
}

.landing-nav__links a:hover {
  color: var(--text-primary);
  background: var(--glass-bg-strong);
}

.landing-nav__actions {
  display: flex;
  align-items: center;
  justify-self: end;
  gap: 8px;
}

.landing-nav__auth-btn {
  min-height: 36px;
  padding: 0 16px;
  border-radius: var(--radius-pill);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition:
    color var(--duration-ui) var(--ease-standard),
    background-color var(--duration-ui) var(--ease-standard),
    border-color var(--duration-ui) var(--ease-standard),
    box-shadow var(--duration-ui) var(--ease-standard),
    transform var(--duration-ui) var(--ease-standard);
}

.landing-nav__auth-btn:active {
  transform: scale(0.97);
}

.landing-nav__auth-btn--ghost {
  border: 1px solid var(--glass-border);
  color: var(--text-primary);
  background: var(--glass-bg-strong);
  backdrop-filter: blur(16px) saturate(1.5);
  -webkit-backdrop-filter: blur(16px) saturate(1.5);
}

.landing-nav__auth-btn--ghost:hover {
  border-color: color-mix(in srgb, var(--glass-border) 60%, white);
  box-shadow: var(--glass-shadow);
  transform: translateY(-1px);
}

.landing-nav__auth-btn--accent {
  border: 1px solid var(--accent-border);
  color: var(--accent);
  background: var(--accent-soft);
}

.landing-nav__auth-btn--accent:hover {
  color: #fff;
  background: var(--accent);
  box-shadow: 0 6px 18px color-mix(in srgb, var(--accent) 28%, transparent);
  transform: translateY(-1px);
}

.landing-nav__menu {
  display: none;
  width: 40px;
  height: 40px;
  flex-direction: column;
  justify-content: center;
  gap: 5px;
  padding: 0 10px;
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-sm);
  background: var(--glass-bg);
  cursor: pointer;
}

.landing-nav__menu span {
  display: block;
  height: 2px;
  background: var(--text-primary);
}

.landing-nav__drawer {
  display: none;
}

@media (hover: hover) and (pointer: fine) {
  .landing-nav__auth-btn--ghost:hover,
  .landing-nav__auth-btn--accent:hover {
    transform: translateY(-1px);
  }
}

@media (max-width: 900px) {
  .landing-nav__links {
    display: none;
  }

  .landing-nav__menu,
  .landing-nav__drawer {
    display: flex;
  }

  .landing-nav__drawer {
    position: fixed;
    top: 64px;
    right: 16px;
    z-index: 99;
    width: min(220px, calc(100vw - 32px));
    flex-direction: column;
    padding: 8px;
    border: 1px solid var(--glass-border);
    border-radius: var(--radius-md);
    background: var(--glass-bg-strong);
    box-shadow: var(--glass-shadow);
    backdrop-filter: blur(16px) saturate(1.5);
    -webkit-backdrop-filter: blur(16px) saturate(1.5);
  }

  .landing-nav__drawer a {
    min-height: 44px;
    padding: 10px 12px;
    border-radius: var(--radius-sm);
    color: var(--text-primary);
    text-decoration: none;
  }
}

@media (max-width: 520px) {
  .landing-nav {
    height: calc(56px + env(safe-area-inset-top, 0px));
    padding: env(safe-area-inset-top, 0px) 16px 0;
  }

  .landing-nav small {
    display: none;
  }

  .landing-nav__auth-btn {
    min-height: 34px;
    padding: 0 12px;
    font-size: 12px;
  }

  .landing-nav__drawer {
    top: calc(56px + env(safe-area-inset-top, 0px));
  }
}
</style>
