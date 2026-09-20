<script setup lang="ts">
import { computed } from 'vue';
import { useRoute } from 'vue-router';

import ThemeToggle from '@/components/layout/ThemeToggle.vue';
import WorkbenchTopBar from '@/components/layout/WorkbenchTopBar.vue';
import { useUiTheme } from '@/composables/useUiTheme';

const route = useRoute();
const isWorkbench = computed(() => route.name === 'workbench');
const isHome = computed(() => route.name === 'home' || route.name === 'login');
useUiTheme();
</script>

<template>
  <div class="app-shell">
    <WorkbenchTopBar v-if="!isHome" />
    <main
      class="app-main"
      :class="{
        'app-main--workbench': isWorkbench,
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

.app-main--workbench {
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

  .app-main--workbench {
    width: 100%;
    padding: 0;
  }

  .app-main--home {
    width: 100%;
    padding: 0;
  }

}

</style>
