<script setup lang="ts">
import { computed } from 'vue';
import { useRoute } from 'vue-router';

import ThemeToggle from '@/components/layout/ThemeToggle.vue';
import WorkbenchTopBar from '@/components/layout/WorkbenchTopBar.vue';
import { useUiTheme } from '@/composables/useUiTheme';

const route = useRoute();
const isWorkbench = computed(() => route.name === 'workbench');
// 统计表格需要完整可用宽度，其余页面继续使用原有居中容器。
const isAnalytics = computed(() => route.name === 'admin-analytics');
const isHome = computed(() => route.name === 'home' || route.name === 'login');
useUiTheme();
</script>

<template>
  <div class="app-shell">
    <WorkbenchTopBar v-if="!isHome" :class="{ 'topbar--analytics': isAnalytics }" />
    <main
      class="app-main"
      :class="{
        'app-main--workbench': isWorkbench,
        'app-main--analytics': isAnalytics,
        'app-main--home': isHome,
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

/* 管理员有六个导航入口，统计页窄屏分两行，避免覆盖品牌与账号操作。 */
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
    grid-template-columns: repeat(6, minmax(0, 1fr));
    gap: 0;
  }

  .topbar--analytics :deep(.nav-link) { min-width: 0; justify-content: center; }
}

@media (max-width: 640px) {
  .topbar--analytics :deep(.nav-link) { min-height: 48px; flex-direction: column; gap: 4px; padding: 6px 2px; font-size: 11px; }
  .topbar--analytics :deep(.nav-link span) { display: inline; white-space: nowrap; }

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
