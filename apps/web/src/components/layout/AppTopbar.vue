<script setup lang="ts">
import { Clock, DataAnalysis, HomeFilled, MagicStick, Setting, Tools } from '@element-plus/icons-vue';
import { computed } from 'vue';

import BrandMark from '@/components/brand/BrandMark.vue';
import AccountMenu from '@/components/layout/AccountMenu.vue';
import { useAuthStore } from '@/stores/auth';
import { useOptimizationStore } from '@/stores/optimization';

const auth = useAuthStore();
const optimization = useOptimizationStore();
// 顶栏与选择器共用管理员维护的版本名称，不从路由 ID 推断版本。
const selectedModelVersion = computed(() => optimization.availableModels.find(
  (model) => model.id === optimization.selectedModelId,
)?.displayName ?? '平台模型');

const navigation = [
  { to: '/', label: '首页', icon: HomeFilled },
  { to: '/workbench', label: '工作台', icon: MagicStick },
  { to: '/history', label: '历史', icon: Clock },
  { to: '/settings', label: '设置', icon: Setting },
] as const;
</script>

<template>
  <header class="topbar">
    <RouterLink class="brand" to="/" aria-label="返回 PromptOptimizer 首页">
      <BrandMark compact />
      <span class="brand-copy">
        <strong>PromptOptimizer</strong>
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
      <RouterLink v-if="auth.user?.platformAdmin" class="nav-link" to="/admin/models">
        <Tools aria-hidden="true" />
        <span>模型管理</span>
      </RouterLink>
      <RouterLink v-if="auth.user?.platformAdmin" class="nav-link" to="/admin/analytics">
        <DataAnalysis aria-hidden="true" />
        <span>统计日志</span>
      </RouterLink>
    </nav>

    <div class="topbar-tools">
      <AccountMenu />
      <div class="provider-status" :title="`模型版本：${selectedModelVersion}（由平台管理员维护）`">
        <span class="status-dot" aria-hidden="true"></span>
        <span>{{ selectedModelVersion }}</span>
      </div>
    </div>
  </header>
</template>

<style scoped>
.topbar {
  position: sticky;
  top: 0;
  z-index: 60;
  display: grid;
  grid-template-columns: minmax(230px, 1fr) auto minmax(180px, 1fr);
  align-items: center;
  height: 56px;
  padding: 0 24px;
  border-bottom: 1px solid var(--glass-border-subtle);
  background: var(--glass-bg);
  backdrop-filter: blur(16px) saturate(1.5);
  -webkit-backdrop-filter: blur(16px) saturate(1.5);
}

.brand {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  width: fit-content;
  color: var(--text-primary);
  text-decoration: none;
}

.brand-copy {
  display: grid;
  gap: 1px;
}

.brand-copy strong {
  color: var(--text-primary);
  font-size: 14px;
  font-weight: 600;
  line-height: 1.2;
}

.brand-copy small {
  color: var(--text-muted);
  font-size: 12px;
  letter-spacing: 0.8px;
  line-height: 1.2;
  text-transform: uppercase;
}

.main-nav {
  display: flex;
  align-items: center;
  gap: 4px;
}

.nav-link {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 7px 16px;
  border-radius: var(--radius-pill);
  color: var(--text-secondary);
  font-size: 14px;
  text-decoration: none;
  transition:
    color var(--duration-ui) var(--ease-standard),
    background-color var(--duration-ui) var(--ease-standard);
}

.nav-link svg {
  width: 14px;
  height: 14px;
}

.nav-link:hover,
.nav-link.router-link-exact-active {
  color: var(--text-primary);
  background: var(--glass-bg-strong);
}

.topbar-tools {
  display: flex;
  align-items: center;
  justify-self: end;
  gap: 12px;
}

.provider-status {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  color: var(--text-muted);
  font-family: var(--font-mono);
  font-size: 12px;
}

.provider-status b {
  color: var(--success);
  font-weight: 500;
}


.status-dot {
  flex: 0 0 auto;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--success);
  box-shadow: 0 0 0 4px color-mix(in srgb, var(--success) 12%, transparent);
}

@media (max-width: 900px) {
  .topbar {
    grid-template-columns: minmax(0, 1fr) auto auto;
    padding: 0 16px;
  }
}

@media (max-width: 640px) {
  .brand-copy small,
  .provider-status,
  .nav-link span {
    display: none;
  }

  .topbar {
    grid-template-columns: auto minmax(0, 1fr) auto;
    height: calc(52px + env(safe-area-inset-top, 0px));
    padding: env(safe-area-inset-top, 0px) 12px 0;
  }

  .topbar-tools {
    display: flex;
    gap: 0;
  }

  .main-nav {
    justify-self: center;
    gap: 2px;
  }

  .nav-link {
    min-width: 44px;
    min-height: 44px;
    justify-content: center;
    padding: 8px;
  }

  .nav-link svg {
    width: 18px;
    height: 18px;
  }
}

@media (max-width: 380px) {
  .brand-copy {
    display: none;
  }
}
</style>
