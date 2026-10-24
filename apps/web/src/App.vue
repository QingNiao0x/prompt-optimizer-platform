<script setup lang="ts">
import { computed } from 'vue';
import { useRoute } from 'vue-router';

import ThemeToggle from '@/components/layout/ThemeToggle.vue';
import WorkbenchTopBar from '@/components/layout/WorkbenchTopBar.vue';
import { useUiTheme } from '@/composables/useUiTheme';

const route = useRoute();
const isWorkbench = computed(() => route.name === 'workbench' || route.name === 'image-to-prompt');
// 统计表格需要完整可用宽度，其余页面继续使用原有居中容器。
const isAnalytics = computed(() => route.name === 'admin-analytics');
// 独立布局页面自带顶栏与页脚，不再叠加应用内 WorkbenchTopBar。
// 用户协议与隐私政策必须能在注册前访问，因此和首页、登录页归为同一类；
// 若沿用应用内顶栏，会同时出现两套导航，且其中的工作台/历史入口对未登录用户无意义。
const isStandalone = computed(
  () => route.name === 'home'
    || route.name === 'login'
    || route.name === 'terms'
    || route.name === 'privacy',
);
useUiTheme();
</script>

<template>
  <div class="app-shell">
    <WorkbenchTopBar v-if="!isStandalone" :class="{ 'topbar--analytics': isAnalytics }" />
    <main
      class="app-main"
      :class="{
        'app-main--workbench': isWorkbench,
        'app-main--analytics': isAnalytics,
        'app-main--home': isStandalone,
      }"
    >
      <RouterView />
    </main>
    <ThemeToggle />
  </div>
</template>

<style scoped>
.app-shell {
  min-height: 100vh;
  background: transparent;
}

.app-main {
  width: min(1180px, calc(100% - 48px));
  margin: 0 auto;
  padding: 34px 0 64px;
}

.app-main--workbench,
.app-main--analytics {
  width: 100%;
  max-width: none;
  padding: 0;
}

.app-main--home {
  width: 100%;
  max-width: none;
  padding: 0;
}

/* 管理员有七个导航入口；窄屏导航独立成行，长标签折行，保留各入口的位置与可用性。 */
@media (max-width: 1100px) {
  .topbar--analytics {
    grid-template-columns: minmax(0, 1fr) auto;
    grid-template-areas: 'brand tools' 'navigation navigation';
    height: auto;
    padding: calc(8px + env(safe-area-inset-top, 0px)) 12px 8px;
    gap: 8px 12px;
  }

  .topbar--analytics :deep(.brand) { grid-area: brand; min-width: 0; max-width: 100%; overflow: hidden; }
  .topbar--analytics :deep(.brand-copy) { display: grid; min-width: 0; }
  .topbar--analytics :deep(.brand-copy strong) { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .topbar--analytics :deep(.topbar-tools) { grid-area: tools; min-width: 0; }
  .topbar--analytics :deep(.main-nav) {
    grid-area: navigation;
    display: grid;
    width: 100%;
    grid-template-columns: repeat(7, minmax(0, 1fr));
    gap: 0;
  }

  .topbar--analytics :deep(.nav-link) { min-width: 0; justify-content: center; }
}

@media (max-width: 640px) {
  .topbar--analytics :deep(.nav-link) { min-height: 48px; flex-direction: column; gap: 4px; padding: 6px 2px; font-size: 11px; }
  .topbar--analytics :deep(.nav-link span) { display: inline; white-space: normal; text-wrap: balance; text-align: center; }

  .app-main {
    width: min(100% - 24px, 720px);
    padding: 20px 0 calc(28px + env(safe-area-inset-bottom, 0px));
  }

  .app-main--workbench,
  .app-main--analytics {
    width: 100%;
    padding: 0;
  }

  .app-main--home {
    width: 100%;
    padding: 0;
  }

}

</style>
