<script setup lang="ts">
import { ArrowDown, Check, Clock, MagicStick, Setting } from '@element-plus/icons-vue';

import { useUiTheme } from '@/composables/useUiTheme';

const navigation = [
  { to: '/', label: '工作台', icon: MagicStick },
  { to: '/history', label: '历史', icon: Clock },
  { to: '/settings', label: '设置', icon: Setting },
] as const;

const {
  activeThemeId,
  currentTheme,
  isMenuOpen,
  themes,
  closeMenu,
  toggleMenu,
  selectTheme,
} = useUiTheme();
</script>

<template>
  <div class="app-shell">
    <header class="topbar">
      <RouterLink class="brand" to="/" aria-label="返回提示词工作台">
        <span class="brand-mark" aria-hidden="true">
          <span></span>
          <span></span>
          <span></span>
        </span>
        <span>
          <strong>QingNiao<span>0x</span></strong>
          <small>Prompt context studio</small>
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

      <div class="topbar-tools">
        <div
          class="theme-switcher"
          data-theme-switcher
          @keydown.esc="closeMenu"
        >
          <button
            class="theme-trigger"
            type="button"
            aria-label="切换界面风格"
            :aria-expanded="isMenuOpen"
            aria-haspopup="listbox"
            @click="toggleMenu"
          >
            <span class="theme-orbit" :class="`theme-orbit--${activeThemeId}`" aria-hidden="true">
              <span></span>
            </span>
            <span class="theme-trigger-copy">
              <small>UI STYLE</small>
              <strong>{{ currentTheme.shortLabel }}</strong>
            </span>
            <ArrowDown class="theme-trigger-arrow" aria-hidden="true" />
          </button>

          <div
            v-if="isMenuOpen"
            class="theme-menu"
            role="listbox"
            aria-label="选择界面风格"
            @pointerdown.stop
          >
            <div class="theme-menu-heading">
              <span>界面风格</span>
              <small>选择你的工作节奏</small>
            </div>
            <button
              v-for="theme in themes"
              :key="theme.id"
              class="theme-option"
              :class="{ 'is-active': activeThemeId === theme.id }"
              type="button"
              role="option"
              :aria-selected="activeThemeId === theme.id"
              @click="selectTheme(theme.id)"
            >
              <span
                class="theme-option-swatch"
                :class="`theme-option-swatch--${theme.id}`"
                aria-hidden="true"
              ></span>
              <span class="theme-option-copy">
                <strong>{{ theme.label }}</strong>
                <small>{{ theme.description }}</small>
              </span>
              <Check v-if="activeThemeId === theme.id" class="theme-option-check" aria-hidden="true" />
            </button>
          </div>
        </div>

        <div class="provider-status" title="模型由后端安全配置">
          <span class="status-dot" aria-hidden="true"></span>
          <span><b>LOCAL</b> · DeepSeek 默认</span>
        </div>
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
  height: 76px;
  padding: 0 clamp(18px, 4vw, 52px);
  border-bottom: 1px solid var(--line-subtle);
  background: color-mix(in srgb, var(--surface-page) 92%, transparent);
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
  border: 1px solid rgba(111, 124, 255, 0.45);
  border-radius: 12px;
  background: linear-gradient(145deg, #2a2e5c, #151823);
  box-shadow: 0 0 24px rgba(111, 124, 255, 0.18);
}

.brand-mark span {
  position: absolute;
  width: 16px;
  height: 3px;
  border-radius: 999px;
  background: var(--accent-cyan);
  transform: translate(-3px, -3px) rotate(-28deg);
}

.brand-mark span:last-child {
  transform: translate(3px, -3px) rotate(28deg);
}

.brand-mark span:nth-child(3) {
  width: 7px;
  height: 7px;
  transform: translate(10px, -8px);
  background: var(--accent-violet);
}

.brand strong,
.brand small {
  display: block;
}

.brand strong {
  font-family: var(--font-display);
  font-size: 16px;
  letter-spacing: -0.02em;
}

.brand strong span {
  color: var(--accent-cyan);
  font-family: var(--font-mono);
  font-size: 12px;
}

.brand small {
  margin-top: 2px;
  color: var(--ink-muted);
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.06em;
  text-transform: uppercase;
}

.main-nav {
  display: flex;
  gap: 4px;
  padding: 4px;
  border: 1px solid var(--line-subtle);
  border-radius: 11px;
  background: rgba(25, 27, 32, 0.8);
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
  transition: background 160ms ease, color 160ms ease;
}

.nav-link svg {
  width: 15px;
}

.nav-link:hover {
  color: var(--ink-strong);
}

.nav-link.router-link-exact-active {
  color: var(--ink-strong);
  background: rgba(111, 124, 255, 0.14);
  box-shadow: inset 0 0 0 1px rgba(111, 124, 255, 0.16);
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

.topbar-tools {
  position: relative;
  display: flex;
  align-items: center;
  justify-self: end;
  gap: 16px;
}

.theme-switcher {
  position: relative;
}

.theme-trigger {
  display: inline-flex;
  min-width: 126px;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  border: 1px solid var(--line-subtle);
  border-radius: 10px;
  color: var(--ink-muted);
  text-align: left;
  background: rgba(25, 27, 32, 0.72);
  cursor: pointer;
  transition: border-color 160ms ease, background 160ms ease, color 160ms ease;
}

.theme-trigger:hover,
.theme-trigger[aria-expanded='true'] {
  border-color: var(--accent-blue);
  color: var(--ink-strong);
  background: rgba(111, 124, 255, 0.1);
}

.theme-orbit {
  display: grid;
  width: 22px;
  height: 22px;
  flex: 0 0 22px;
  place-items: center;
  border: 1px solid var(--accent-blue);
  border-radius: 7px;
  background: rgba(111, 124, 255, 0.14);
}

.theme-orbit span {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--accent-cyan);
  box-shadow: 0 0 10px var(--accent-cyan);
}

.theme-orbit--neon-control-room {
  border-color: #a38bff;
  background: rgba(163, 139, 255, 0.2);
}

.theme-orbit--neon-control-room span {
  background: #52e4d0;
  box-shadow: 0 0 10px #52e4d0;
}

.theme-orbit--dusk-glass {
  border-color: #b39cff;
  border-radius: 50%;
  background: rgba(179, 156, 255, 0.17);
}

.theme-orbit--dusk-glass span {
  background: #e9b8ff;
  box-shadow: 0 0 10px #e9b8ff;
}

.theme-trigger-copy {
  display: grid;
  min-width: 0;
  gap: 1px;
}

.theme-trigger-copy small {
  color: var(--ink-soft);
  font-family: var(--font-mono);
  font-size: 8px;
  letter-spacing: 0.1em;
  line-height: 1.2;
}

.theme-trigger-copy strong {
  overflow: hidden;
  color: var(--ink-strong);
  font-size: 11px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.theme-trigger-arrow {
  width: 13px;
  margin-left: auto;
  color: var(--ink-soft);
  transition: transform 160ms ease;
}

.theme-trigger[aria-expanded='true'] .theme-trigger-arrow {
  transform: rotate(180deg);
}

.theme-menu {
  position: absolute;
  top: calc(100% + 10px);
  right: 0;
  z-index: 60;
  display: grid;
  width: min(310px, calc(100vw - 32px));
  gap: 4px;
  padding: 8px;
  border: 1px solid var(--line-strong);
  border-radius: 12px;
  background: color-mix(in srgb, var(--surface-elevated) 96%, transparent);
  box-shadow: 0 20px 50px rgba(0, 0, 0, 0.36);
  backdrop-filter: blur(18px);
}

.theme-menu-heading {
  display: grid;
  gap: 2px;
  padding: 6px 9px 8px;
  border-bottom: 1px solid var(--line-subtle);
}

.theme-menu-heading span {
  color: var(--ink-strong);
  font-size: 12px;
  font-weight: 600;
}

.theme-menu-heading small {
  color: var(--ink-soft);
  font-size: 10px;
}

.theme-option {
  display: grid;
  grid-template-columns: 30px minmax(0, 1fr) 16px;
  align-items: center;
  gap: 9px;
  width: 100%;
  padding: 9px;
  border: 1px solid transparent;
  border-radius: 9px;
  color: var(--ink-muted);
  text-align: left;
  background: transparent;
  cursor: pointer;
}

.theme-option:hover,
.theme-option.is-active {
  border-color: color-mix(in srgb, var(--accent-blue) 32%, transparent);
  background: color-mix(in srgb, var(--accent-blue) 10%, transparent);
}

.theme-option-swatch {
  width: 30px;
  height: 30px;
  border: 1px solid var(--line-strong);
  border-radius: 8px;
  background: linear-gradient(145deg, #191b20 0 55%, #6f7cff 56% 65%, #0c0f14 66%);
}

.theme-option-swatch--neon-control-room {
  border-color: #62599b;
  background: linear-gradient(145deg, #111522 0 42%, #a38bff 43% 56%, #52e4d0 57% 67%, #080b10 68%);
}

.theme-option-swatch--dusk-glass {
  border-color: #766b9e;
  background: linear-gradient(145deg, #222536 0 42%, #b39cff 43% 55%, #e9b8ff 56% 68%, #10131c 69%);
}

.theme-option-copy {
  display: grid;
  min-width: 0;
  gap: 2px;
}

.theme-option-copy strong {
  overflow: hidden;
  color: var(--ink-strong);
  font-size: 11px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.theme-option-copy small {
  color: var(--ink-soft);
  font-size: 10px;
  line-height: 1.4;
}

.theme-option-check {
  width: 14px;
  color: var(--accent-cyan);
}

.provider-status b {
  color: var(--accent-cyan);
  font-weight: 500;
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
  padding: 38px 0 64px;
}

@media (max-width: 820px) {
  .topbar {
    grid-template-columns: minmax(0, 1fr) auto auto;
    height: 64px;
    padding: 0 18px;
  }

  .brand small,
  .provider-status {
    display: none;
  }

  .topbar-tools {
    gap: 0;
  }

  .theme-trigger {
    min-width: 38px;
    justify-content: center;
    padding: 7px;
  }

  .theme-trigger-copy,
  .theme-trigger-arrow {
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
