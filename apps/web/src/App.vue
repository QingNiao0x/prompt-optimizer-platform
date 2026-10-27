<script setup lang="ts">
import { computed, watch } from 'vue';
import { ElConfigProvider } from 'element-plus';
import { useRoute } from 'vue-router';

import ThemeToggle from '@/components/layout/ThemeToggle.vue';
import WorkbenchTopBar from '@/components/layout/WorkbenchTopBar.vue';
import { useUiTheme } from '@/composables/useUiTheme';
import { useAuthStore } from '@/stores/auth';
import { useLocaleStore } from '@/stores/locale';
import { useSystemSettingsStore } from '@/stores/systemSettings';

const route = useRoute();
const auth = useAuthStore();
const language = useLocaleStore();
const settings = useSystemSettingsStore();
// 登录恢复后同步账号语言；设置查询失败不阻断原有登录和工具功能。
watch(() => auth.user?.userId, userId => {
  if (userId) void settings.load().catch(() => undefined);
}, { immediate: true });
watch([() => route.fullPath, () => language.locale], () => {
  document.title = language.pageTitle(route.name, typeof route.meta.title === 'string' ? route.meta.title : 'Prompt Optimizer');
}, { immediate: true, flush: 'post' });
const isWorkbench = computed(() => route.name === 'workbench' || route.name === 'image-to-prompt' || route.name === 'text-polish');
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
  <ElConfigProvider :locale="language.elementLocale">
  <div class="app-shell">
    <WorkbenchTopBar v-if="!isStandalone" />
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
  </ElConfigProvider>
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

@media (max-width: 640px) {
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
