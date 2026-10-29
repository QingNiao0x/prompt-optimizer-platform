<script setup lang="ts">
import { Document, EditPen, Picture } from '@element-plus/icons-vue';
import type { Component } from 'vue';

interface FeatureDefinition {
  title: string;
  description: string;
  icon: Component;
  tone: 'accent' | 'success' | 'warning';
}

const features: readonly FeatureDefinition[] = [
  {
    title: '提示词优化',
    description: '结合你主动提供的代码、文档与背景整理需求，支持自然正文、结构化输出和可选 Plan 确认。',
    icon: Document,
    tone: 'accent',
  },
  {
    title: '素材转提示词',
    description: '审阅图片、代码、文档与文件夹中的可读资料，明确保留与修改要求；直接生成或用 Plan 确认，结果可编辑、复制并保存历史。',
    icon: Picture,
    tone: 'success',
  },
  {
    title: '文章祛AI味',
    description: '独立润色已有文章，提供三档文体、正文差异对比与私有历史，不进入提示词优化的 Plan 流程。',
    icon: EditPen,
    tone: 'warning',
  },
];
</script>

<template>
  <section id="features" class="feature-cards" aria-labelledby="features-title">
    <header class="feature-cards__header">
      <h2 id="features-title">三项工具，按任务选择</h2>
      <p>从需求与参考资料整理提示词，也为已有文章提供独立润色工作台。</p>
    </header>

    <div class="feature-cards__grid">
      <article
        v-for="(feature, index) in features"
        :key="feature.title"
        class="glass-panel glass-panel--float feature-card"
        :style="{ animationDelay: `${0.1 * (index + 1)}s` }"
      >
        <span class="feature-card__icon" :class="`feature-card__icon--${feature.tone}`">
          <component :is="feature.icon" aria-hidden="true" />
        </span>
        <h3>{{ feature.title }}</h3>
        <p>{{ feature.description }}</p>
      </article>
    </div>
  </section>
</template>

<style scoped>
.feature-cards {
  padding: 80px 24px 80px;
}

.feature-cards__header {
  margin-bottom: 64px;
  text-align: center;
}

.feature-cards__header h2 {
  margin: 0 0 12px;
  color: var(--text-primary);
  font-size: 32px;
  font-weight: 600;
}

.feature-cards__header p {
  margin: 0;
  color: var(--text-secondary);
  font-size: 16px;
}

.feature-cards__grid {
  display: grid;
  max-width: 1000px;
  margin: 0 auto;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 20px;
}

.feature-card {
  padding: 32px;
  animation: fade-in-up 0.6s ease-out both;
}

.feature-card__icon {
  display: grid;
  width: 44px;
  height: 44px;
  margin-bottom: 20px;
  border-radius: var(--radius-md);
  place-items: center;
}

.feature-card__icon svg {
  width: 22px;
  height: 22px;
}

.feature-card__icon--accent {
  color: var(--accent);
  background: var(--accent-soft);
}

.feature-card__icon--success {
  color: var(--green);
  background: var(--green-soft);
}

.feature-card__icon--warning {
  color: var(--orange);
  background: var(--orange-soft);
}

.feature-card h3 {
  margin: 0 0 8px;
  color: var(--text-primary);
  font-size: 17px;
  font-weight: 600;
}

.feature-card p {
  margin: 0;
  color: var(--text-secondary);
  font-size: 14px;
  line-height: 1.6;
}

@media (max-width: 900px) {
  .feature-cards__grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 520px) {
  .feature-cards {
    padding: 56px 18px 64px;
  }

  .feature-cards__header {
    margin-bottom: 36px;
  }

  .feature-cards__header h2 {
    font-size: 27px;
  }
}
</style>
