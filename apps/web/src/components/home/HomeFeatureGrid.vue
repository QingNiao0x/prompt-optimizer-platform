<script setup lang="ts">
import { Clock, Document, EditPen } from '@element-plus/icons-vue';
import type { Component } from 'vue';

interface FeatureDefinition {
  title: string;
  description: string;
  icon: Component;
  tone: 'accent' | 'success' | 'warning';
}

const features: readonly FeatureDefinition[] = [
  {
    title: '项目上下文感知',
    description: '上传代码文件、文档或项目描述，AI 自动理解你的技术栈、架构和业务逻辑，生成贴合实际的提示词。',
    icon: Document,
    tone: 'accent',
  },
  {
    title: '结构化输出',
    description: '优化结果按背景、任务、约束、格式等维度结构化呈现，清晰可读，直接复制到任何 AI 平台使用。',
    icon: EditPen,
    tone: 'success',
  },
  {
    title: '一键增强',
    description: '输入原始想法后一键增强，AI 自动补全缺失维度、优化表述结构，支持多轮迭代直到满意为止。',
    icon: Clock,
    tone: 'warning',
  },
];
</script>

<template>
  <section id="features" class="home-features" aria-labelledby="features-title">
    <header class="home-features__header">
      <h2 id="features-title">为什么选择 PromptOptimizer</h2>
      <p>从项目上下文到结构化提示词，一站式优化你的 AI 工作流</p>
    </header>

    <div class="home-features__grid">
      <article v-for="feature in features" :key="feature.title" class="home-feature-card">
        <span class="home-feature-card__icon" :class="`home-feature-card__icon--${feature.tone}`">
          <component :is="feature.icon" aria-hidden="true" />
        </span>
        <h3>{{ feature.title }}</h3>
        <p>{{ feature.description }}</p>
      </article>
    </div>
  </section>
</template>

<style scoped>
.home-features {
  padding: 80px 24px 120px;
}

.home-features__header {
  margin-bottom: 64px;
  text-align: center;
}

.home-features__header h2 {
  margin: 0 0 12px;
  color: var(--text-primary);
  font-size: 32px;
  font-weight: 600;
}

.home-features__header p {
  margin: 0;
  color: var(--text-secondary);
  font-size: 16px;
}

.home-features__grid {
  display: grid;
  max-width: 1000px;
  margin: 0 auto;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 20px;
}

.home-feature-card {
  position: relative;
  overflow: hidden;
  padding: 32px;
  border: 1px solid var(--glass-border);
  border-radius: 18px;
  background: var(--glass-bg);
  box-shadow: var(--glass-shadow);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
  transition: transform 250ms cubic-bezier(0.4, 0, 0.2, 1), border-color 250ms cubic-bezier(0.4, 0, 0.2, 1), box-shadow 250ms cubic-bezier(0.4, 0, 0.2, 1);
}

.home-feature-card::before {
  position: absolute;
  inset: 0 0 auto;
  height: 1px;
  content: '';
  background: linear-gradient(90deg, transparent, rgba(255, 255, 255, 0.5), transparent);
}

.home-feature-card:hover {
  border-color: color-mix(in srgb, var(--glass-border) 72%, white);
  box-shadow: var(--glass-shadow-hover);
  transform: translateY(-4px);
}

.home-feature-card__icon {
  display: grid;
  width: 44px;
  height: 44px;
  margin-bottom: 20px;
  border-radius: 14px;
  place-items: center;
}

.home-feature-card__icon svg {
  width: 22px;
  height: 22px;
}

.home-feature-card__icon--accent {
  color: var(--accent);
  background: var(--accent-soft);
}

.home-feature-card__icon--success {
  color: var(--success);
  background: color-mix(in srgb, var(--success) 10%, transparent);
}

.home-feature-card__icon--warning {
  color: var(--warning);
  background: color-mix(in srgb, var(--warning) 10%, transparent);
}

.home-feature-card h3 {
  margin: 0 0 8px;
  color: var(--text-primary);
  font-size: 17px;
  font-weight: 600;
}

.home-feature-card p {
  margin: 0;
  color: var(--text-secondary);
  font-size: 14px;
  line-height: 1.6;
}

@media (max-width: 900px) {
  .home-features__grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 520px) {
  .home-features {
    padding: 56px 18px 88px;
  }

  .home-features__header {
    margin-bottom: 36px;
  }

  .home-features__header h2 {
    font-size: 27px;
  }
}
</style>
