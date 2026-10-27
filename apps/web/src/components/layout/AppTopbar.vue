<script setup lang="ts">
import { Clock, Close, DataAnalysis, HomeFilled, MagicStick, Menu, Picture, EditPen, Setting, Tools } from '@element-plus/icons-vue';
import { computed, ref, watch } from 'vue';
import { ElButton, type ButtonInstance } from 'element-plus';
import { useRoute } from 'vue-router';

import BrandMark from '@/components/brand/BrandMark.vue';
import AccountMenu from '@/components/layout/AccountMenu.vue';
import { formatModelVersion } from '@/features/models/modelVersion';
import { useAuthStore } from '@/stores/auth';
import { useOptimizationStore } from '@/stores/optimization';
import { useTextPolishStore } from '@/stores/textPolish';
import { useImagePromptStore } from '@/stores/imagePrompt';
import { useLocaleStore } from '@/stores/locale';

const auth = useAuthStore();
const language = useLocaleStore();
const route = useRoute();
const optimization = useOptimizationStore();
const imagePrompt = useImagePromptStore();
const textPolish = useTextPolishStore();
const menuOpen = ref(false);
const menuButton = ref<ButtonInstance>();

const closeNavigation = (): void => {
  menuOpen.value = false;
  const button = menuButton.value?.$el;
  if (button instanceof HTMLElement) button.focus();
};

// 共用顶栏也服务统计与独立工具；路由变化后收起菜单，避免遮住目标页面。
watch(() => route.fullPath, () => { menuOpen.value = false; });
// 顶栏与选择器共用展示短名；管理员名称和调用路由不变。
const selectedModelVersion = computed(() => route.name === 'text-polish'
  ? formatModelVersion(textPolish.selectedModel?.displayName, language.t('noModel'))
  : route.name === 'image-to-prompt'
  ? formatModelVersion(imagePrompt.selectedModel?.displayName, language.t('noModel'))
  : formatModelVersion(optimization.availableModels.find(
  (model) => model.id === optimization.selectedModelId,
)?.displayName, language.t('platformModel')));

const navigation = computed(() => [
  { to: '/', label: language.t('home'), icon: HomeFilled },
  { to: '/workbench', label: language.t('enhancement'), icon: MagicStick },
  { to: '/image-to-prompt', label: language.t('imagePrompt'), icon: Picture },
  { to: '/text-polish', label: language.t('textPolish'), icon: EditPen },
  { to: '/history', label: language.t('history'), icon: Clock },
  { to: '/settings', label: language.t('settings'), icon: Setting },
]);
</script>

<template>
  <header class="topbar" @keydown.esc="closeNavigation">
    <RouterLink class="brand" to="/" :aria-label="language.t('brandHome')">
      <BrandMark compact />
      <span class="brand-copy">
        <strong>PromptOptimizer</strong>
        <small>Prompt context studio</small>
      </span>
    </RouterLink>

    <nav id="app-main-navigation" class="main-nav" :class="{ 'main-nav--open': menuOpen }" :aria-label="language.t('mainNavigation')" @click="menuOpen = false">
      <RouterLink
        v-for="item in navigation"
        :key="item.to"
        :to="item.to"
        :aria-label="item.label"
        class="nav-link"
      >
        <component :is="item.icon" aria-hidden="true" />
        <span>{{ item.label }}</span>
      </RouterLink>
      <RouterLink v-if="auth.user?.platformAdmin" class="nav-link" to="/admin/models">
        <Tools aria-hidden="true" />
        <span>{{ language.t('models') }}</span>
      </RouterLink>
      <RouterLink v-if="auth.user?.platformAdmin" class="nav-link" to="/admin/analytics">
        <DataAnalysis aria-hidden="true" />
        <span>{{ language.t('analytics') }}</span>
      </RouterLink>
    </nav>

    <div class="topbar-tools">
      <ElButton
        ref="menuButton"
        class="navigation-toggle"
        :icon="menuOpen ? Close : Menu"
        :aria-label="language.t('featureMenu')"
        aria-controls="app-main-navigation"
        :aria-expanded="menuOpen"
        @click="menuOpen = !menuOpen"
      >
        {{ language.t(menuOpen ? 'collapse' : 'menu') }}
      </ElButton>
      <AccountMenu />
      <ElButton v-if="!auth.isAuthenticated" type="primary" size="small" @click="$router.push({ path: '/login', query: { mode: 'register', redirect: route.path } })">{{ language.t('registerLogin') }}</ElButton>
      <!-- 各工具展示本任务选择的模型，使用独立状态，避免误示其他模块的模型。 -->
      <div v-if="(route.name !== 'image-to-prompt' || imagePrompt.selectedModel) && (route.name !== 'text-polish' || textPolish.selectedModel)" class="provider-status" :title="language.locale === 'en' ? `Model: ${selectedModelVersion} (maintained by the platform administrator)` : `模型版本：${selectedModelVersion}（由平台管理员维护）`">
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
  /* 等宽两侧保留品牌与账号区，导航始终以整个视口为中心。 */
  grid-template-columns: minmax(0, 1fr) max-content minmax(0, 1fr);
  column-gap: 16px;
  align-items: center;
  min-height: 64px;
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
  min-width: 0;
  max-width: 100%;
  color: var(--text-primary);
  text-decoration: none;
}

.brand > :first-child {
  flex-shrink: 0;
}

.brand-copy {
  display: grid;
  min-width: 0;
  gap: 1px;
}

.brand-copy strong {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
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
  min-width: 0;
  align-items: center;
  justify-content: center;
  justify-self: center;
  gap: 4px;
}

.nav-link {
  display: inline-flex;
  flex-shrink: 0;
  align-items: center;
  min-height: 44px;
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
  flex-shrink: 0;
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
  min-width: 0;
}

.navigation-toggle {
  display: none;
}

.provider-status {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  min-width: 0;
  max-width: 140px;
  color: var(--text-muted);
  font-family: var(--font-mono);
  font-size: 12px;
}

.provider-status > span:last-child {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
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

/* 八个管理员入口与两侧最长账号/模型区不足以并排时，导航独占居中一行，避免挤压或覆盖。 */
@media (max-width: 1599px) {
  .topbar {
    grid-template-columns: minmax(0, 1fr) auto;
    grid-template-areas: 'brand tools' 'navigation navigation';
    height: auto;
    gap: 8px 12px;
    padding: calc(8px + env(safe-area-inset-top, 0px)) 16px 8px;
  }

  .brand {
    grid-area: brand;
    min-width: 0;
    max-width: 100%;
    overflow: hidden;
  }

  .main-nav {
    grid-area: navigation;
    width: 100%;
    flex-wrap: wrap;
  }

  .topbar-tools {
    grid-area: tools;
  }
}

/* 手机和平板使用可展开的完整文字菜单，避免把八个入口挤成难以辨认的图标。 */
@media (max-width: 1024px) {
  .provider-status {
    display: none;
  }

  .topbar {
    padding: calc(8px + env(safe-area-inset-top, 0px)) 12px 8px;
  }

  .topbar-tools {
    gap: 8px;
  }

  .navigation-toggle {
    display: inline-flex;
    min-height: 44px;
    margin-left: 0;
  }

  .main-nav {
    display: none;
    grid-template-columns: repeat(4, minmax(0, 1fr));
    gap: 6px;
    overflow: visible;
    padding-block: 4px;
  }

  .main-nav--open {
    display: grid;
  }

  .nav-link {
    min-width: 0;
    min-height: 44px;
    padding: 10px 12px;
    border-radius: var(--radius-sm);
    line-height: 1.5;
  }

  .nav-link span {
    white-space: normal;
    overflow-wrap: anywhere;
  }

  .nav-link svg {
    width: 18px;
    height: 18px;
  }
}

@media (max-width: 640px) {
  .brand-copy small {
    display: none;
  }

  .main-nav {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .topbar-tools :deep(.account-menu button) {
    min-height: 44px;
  }
}
</style>
