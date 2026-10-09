<script setup lang="ts">
import { computed } from 'vue';

import BrandMark from '@/components/brand/BrandMark.vue';
import { ICP_LICENSE_NUMBER, LEGAL_LAST_UPDATED, MIIT_BEIAN_URL } from '@/config/legal';

interface Props {
  /** 条款标题，同时用于页面主标题。 */
  title: string;
  /** 最近修订日期；不传时取统一配置，条款实质变更时同步更新。 */
  updatedAt?: string;
}

const props = defineProps<Props>();

const displayUpdatedAt = computed(() => props.updatedAt ?? LEGAL_LAST_UPDATED);
</script>

<template>
  <div class="legal-page">
    <header class="legal-page__bar">
      <RouterLink class="legal-page__brand" to="/" aria-label="PromptOptimizer 首页">
        <BrandMark compact />
        <span>PromptOptimizer</span>
      </RouterLink>
      <RouterLink class="legal-page__back" to="/">返回首页</RouterLink>
    </header>

    <main class="legal-page__main">
      <article class="legal-doc glass-panel">
        <h1>{{ title }}</h1>
        <p class="legal-doc__meta">最近修订：{{ displayUpdatedAt }}</p>
        <slot />
      </article>
    </main>

    <footer class="legal-page__footer">
      <p>© 2026 PromptOptimizer · Prompt Context Studio</p>
      <nav aria-label="页脚链接">
        <RouterLink to="/terms">用户协议</RouterLink>
        <RouterLink to="/privacy">隐私政策</RouterLink>
      </nav>
      <!-- 备案号由管局下发后写入 src/config/legal.ts；为空时不渲染，避免展示无效编号。 -->
      <p v-if="ICP_LICENSE_NUMBER">
        <a :href="MIIT_BEIAN_URL" target="_blank" rel="noopener noreferrer">{{ ICP_LICENSE_NUMBER }}</a>
      </p>
    </footer>
  </div>
</template>

<style scoped>
.legal-page {
  display: flex;
  min-height: 100vh;
  flex-direction: column;
}

.legal-page__bar {
  position: sticky;
  top: 0;
  z-index: 10;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 24px;
  border-bottom: 1px solid var(--glass-border-subtle);
  background: var(--glass-bg);
  backdrop-filter: blur(16px) saturate(1.5);
  -webkit-backdrop-filter: blur(16px) saturate(1.5);
}

.legal-page__brand {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  color: var(--text-primary);
  font-size: 15px;
  font-weight: 600;
  text-decoration: none;
}

.legal-page__back {
  color: var(--text-secondary);
  font-size: 14px;
  text-decoration: none;
}

.legal-page__back:hover {
  color: var(--accent);
}

.legal-page__main {
  flex: 1;
  padding: 32px 24px 56px;
}

.legal-doc {
  max-width: 880px;
  margin: 0 auto;
  padding: 40px 44px 48px;
}

.legal-doc h1 {
  margin: 0 0 6px;
  color: var(--text-primary);
  font-size: 30px;
}

.legal-doc__meta {
  margin: 0 0 28px;
  color: var(--text-muted);
  font-size: 13px;
}

.legal-doc :deep(h2) {
  margin: 32px 0 10px;
  color: var(--text-primary);
  font-size: 18px;
}

.legal-doc :deep(p) {
  margin: 0 0 12px;
  color: var(--text-secondary);
  font-size: 14px;
  line-height: 1.8;
}

.legal-doc :deep(ul) {
  margin: 0 0 12px;
  padding-left: 22px;
  color: var(--text-secondary);
  font-size: 14px;
  line-height: 1.8;
}

.legal-doc :deep(li) {
  margin-bottom: 6px;
}

.legal-doc :deep(strong) {
  color: var(--text-primary);
}

.legal-doc :deep(a) {
  color: var(--accent);
}

.legal-page__footer {
  display: grid;
  justify-items: center;
  gap: 10px;
  padding: 28px 24px 40px;
  border-top: 1px solid var(--glass-border-subtle);
  color: var(--text-muted);
  font-size: 13px;
  text-align: center;
}

.legal-page__footer p {
  margin: 0;
}

.legal-page__footer nav {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 16px;
}

.legal-page__footer a {
  color: var(--text-secondary);
  text-decoration: none;
}

.legal-page__footer a:hover {
  color: var(--accent);
}

@media (max-width: 640px) {
  .legal-page__main {
    padding: 20px 14px 40px;
  }

  .legal-doc {
    padding: 26px 20px 32px;
  }

  .legal-doc h1 {
    font-size: 24px;
  }
}
</style>
