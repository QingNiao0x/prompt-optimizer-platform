<script setup lang="ts">
import { Clock, MagicStick, Setting } from '@element-plus/icons-vue';

const navigation = [
  { to: '/', label: '工作台', icon: MagicStick },
  { to: '/history', label: '历史', icon: Clock },
  { to: '/settings', label: '设置', icon: Setting },
] as const;
</script>

<template>
  <div class="app-shell">
    <header class="topbar">
      <RouterLink class="brand" to="/" aria-label="返回提示词工作台">
        <span class="brand-mark" aria-hidden="true">
          <span></span>
          <span></span>
        </span>
        <span>
          <strong>Prompt Optimizer</strong>
          <small>Developer context studio</small>
        </span>
      </RouterLink>

      <nav class="main-nav" aria-label="主导航">
        <RouterLink
          v-for="item in navigation"
          :key="item.to"
          :to="item.to"
          class="nav-link"
        >
          <component :is="item.icon" aria-hidden="true" />
          <span>{{ item.label }}</span>
        </RouterLink>
      </nav>

      <div class="provider-status" title="模型由后端安全配置">
        <span class="status-dot" aria-hidden="true"></span>
        <span>DeepSeek 默认</span>
      </div>
    </header>

    <main class="app-main">
      <RouterView />
    </main>
  </div>
</template>

<style scoped>
.app-shell {
  min-height: 100vh;
}

.topbar {
  position: sticky;
  top: 0;
  z-index: 30;
  display: grid;
  grid-template-columns: minmax(260px, 1fr) auto minmax(220px, 1fr);
  align-items: center;
  height: 72px;
  padding: 0 32px;
  border-bottom: 1px solid var(--line-subtle);
  background: color-mix(in srgb, var(--surface-page) 88%, transparent);
  backdrop-filter: blur(18px);
}

.brand {
  display: inline-flex;
  align-items: center;
  gap: 12px;
  width: fit-content;
  color: var(--ink-strong);
  text-decoration: none;
}

.brand-mark {
  position: relative;
  display: grid;
  width: 36px;
  height: 36px;
  place-items: center;
  overflow: hidden;
  border: 1px solid var(--ink-strong);
  border-radius: 11px;
  background: var(--ink-strong);
}

.brand-mark span {
  position: absolute;
  width: 17px;
  height: 2px;
  border-radius: 999px;
  background: var(--accent-cyan);
  transform: rotate(-32deg);
}

.brand-mark span:last-child {
  transform: rotate(32deg);
}

.brand strong,
.brand small {
  display: block;
}

.brand strong {
  font-family: var(--font-display);
  font-size: 15px;
  letter-spacing: -0.02em;
}

.brand small {
  margin-top: 2px;
  color: var(--ink-muted);
  font-family: var(--font-mono);
  font-size: 9px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.main-nav {
  display: flex;
  gap: 4px;
  padding: 4px;
  border: 1px solid var(--line-subtle);
  border-radius: 13px;
  background: var(--surface-panel);
}

.nav-link {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 8px 13px;
  border-radius: 9px;
  color: var(--ink-muted);
  font-size: 13px;
  text-decoration: none;
  transition: background 160ms ease, color 160ms ease, transform 160ms ease;
}

.nav-link svg {
  width: 15px;
}

.nav-link:hover {
  color: var(--ink-strong);
}

.nav-link.router-link-exact-active {
  color: var(--ink-strong);
  background: var(--surface-elevated);
  box-shadow: var(--shadow-soft);
}

.provider-status {
  display: inline-flex;
  justify-self: end;
  align-items: center;
  gap: 8px;
  color: var(--ink-muted);
  font-family: var(--font-mono);
  font-size: 11px;
}

.status-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--success);
  box-shadow: 0 0 0 4px color-mix(in srgb, var(--success) 14%, transparent);
}

.app-main {
  width: min(1540px, calc(100% - 48px));
  margin: 0 auto;
  padding: 36px 0 56px;
}

@media (max-width: 820px) {
  .topbar {
    grid-template-columns: 1fr auto;
    height: 64px;
    padding: 0 18px;
  }

  .brand small,
  .provider-status {
    display: none;
  }

  .main-nav {
    border: 0;
    background: transparent;
  }

  .nav-link {
    padding: 8px;
  }

  .nav-link span {
    display: none;
  }

  .app-main {
    width: min(100% - 28px, 720px);
    padding-top: 24px;
  }
}
</style>
