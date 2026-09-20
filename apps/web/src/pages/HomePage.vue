<script setup lang="ts">
import { Clock } from '@element-plus/icons-vue';

import BrandMark from '@/components/brand/BrandMark.vue';
import HomeFeatureGrid from '@/components/home/HomeFeatureGrid.vue';
import HomeHero from '@/components/home/HomeHero.vue';
</script>

<template>
  <div class="home-page">
    <nav class="home-nav" aria-label="首页导航">
      <RouterLink class="home-nav__brand" to="/" aria-label="PromptOptimizer 首页">
        <BrandMark />
        <span>
          <strong>PromptOptimizer</strong>
          <small>Prompt Context Studio</small>
        </span>
      </RouterLink>

      <div class="home-nav__links">
        <a href="#features">功能特性</a>
        <RouterLink to="/workbench">工作台</RouterLink>
      </div>

      <div class="home-nav__actions">
        <RouterLink class="home-nav__history" to="/history">
          <Clock aria-hidden="true" />
          历史
        </RouterLink>
        <RouterLink class="home-nav__start" to="/workbench">免费开始</RouterLink>
      </div>
    </nav>

    <HomeHero />
    <HomeFeatureGrid />

    <footer class="home-footer">
      <p>© 2026 PromptOptimizer · Prompt Context Studio · 让每一次 AI 对话都精准高效</p>
    </footer>
  </div>
</template>

<style scoped>
.home-page {
  min-height: 100vh;
}

.home-nav {
  position: fixed;
  inset: 0 0 auto;
  z-index: 100;
  display: flex;
  height: 64px;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  border-bottom: 1px solid var(--glass-border-subtle);
  background: var(--glass-bg);
  backdrop-filter: blur(16px) saturate(1.5);
  -webkit-backdrop-filter: blur(16px) saturate(1.5);
}

.home-nav__brand {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  color: var(--text-primary);
  text-decoration: none;
}

.home-nav__brand > span:last-child {
  display: grid;
}

.home-nav strong {
  font-size: 16px;
  font-weight: 600;
  line-height: 1.25;
}

.home-nav small {
  color: var(--text-muted);
  font-size: 11px;
  letter-spacing: 0.15em;
  line-height: 1.25;
  text-transform: uppercase;
}

.home-nav__links,
.home-nav__actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.home-nav__links a,
.home-nav__history,
.home-nav__start {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 36px;
  padding: 8px 16px;
  border-radius: 10px;
  color: var(--text-secondary);
  font-size: 14px;
  font-weight: 500;
  text-decoration: none;
  transition: color 250ms cubic-bezier(0.4, 0, 0.2, 1), background 250ms cubic-bezier(0.4, 0, 0.2, 1), transform 250ms cubic-bezier(0.4, 0, 0.2, 1);
}

.home-nav__links a:hover,
.home-nav__history:hover {
  color: var(--text-primary);
  background: var(--glass-bg-strong);
}

.home-nav__history svg {
  width: 15px;
  height: 15px;
}

.home-nav__start {
  min-height: auto;
  padding: 8px 20px;
  border-radius: 9999px;
  color: #fff;
  font-size: 13px;
  background: var(--accent);
  box-shadow: 0 4px 16px color-mix(in srgb, var(--accent) 25%, transparent);
}

.home-nav__start:hover {
  color: #fff;
  box-shadow: 0 6px 20px color-mix(in srgb, var(--accent) 35%, transparent);
  transform: translateY(-1px);
}

.home-footer {
  padding: 40px 24px;
  border-top: 1px solid var(--glass-border-subtle);
  color: var(--text-muted);
  font-size: 13px;
  text-align: center;
}

.home-footer p {
  margin: 0;
}

@media (max-width: 900px) {
  .home-nav__links {
    display: none;
  }
}

@media (max-width: 520px) {
  .home-nav {
    height: calc(56px + env(safe-area-inset-top, 0px));
    padding: env(safe-area-inset-top, 0px) 16px 0;
  }

  .home-nav small,
  .home-nav__history {
    display: none;
  }

  .home-nav__start {
    min-height: 40px;
    padding-inline: 16px;
  }
}
</style>
