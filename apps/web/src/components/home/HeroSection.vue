<script setup lang="ts">
import { ArrowRight, ChatDotRound, EditPen, Picture } from '@element-plus/icons-vue';

import WandSparklesIcon from '@/components/brand/WandSparklesIcon.vue';
</script>

<template>
  <section class="hero-section" aria-labelledby="home-hero-title">
    <div class="hero-section__badge">
      <span class="hero-section__badge-dot" aria-hidden="true"></span>
      上下文感知 · 多模态输入
    </div>

    <h1 id="home-hero-title">
      让思路更清晰，<br>
      <span>让表达更清楚。</span>
    </h1>

    <p class="hero-section__description">
      结合项目上下文增强提示词，将图片、代码与文档整理为下游可用的提示词，按需用 Plan 确认关键要求。
      已有文章也能在独立工作台中切换文体、对比差异，润色表达。
    </p>

    <div class="hero-section__actions">
      <RouterLink class="hero-button hero-button--primary" to="/workbench">
        <WandSparklesIcon />
        开始优化提示词
      </RouterLink>
      <RouterLink class="hero-button hero-button--glass" to="/image-to-prompt">
        <Picture aria-hidden="true" />
        素材转提示词
      </RouterLink>
      <RouterLink class="hero-button hero-button--glass" to="/text-polish">
        <EditPen aria-hidden="true" />
        文章祛AI味
      </RouterLink>
    </div>
    <a
      class="hero-section__community"
      href="https://qm.qq.com/q/mc6zzSQlxe"
      target="_blank"
      rel="noopener noreferrer"
    >
      <ChatDotRound class="hero-section__community-icon" aria-hidden="true" />
      <span class="hero-section__community-text">
        <strong>加入QQ群</strong>
        <span>提示词优化工具交流群 · 2163076660</span>
      </span>
      <ArrowRight class="hero-section__community-arrow" aria-hidden="true" />
    </a>
    <a class="hero-section__more" href="#features">了解三项功能</a>
  </section>
</template>

<style scoped>
.hero-section {
  position: relative;
  display: flex;
  min-height: 100vh;
  align-items: center;
  justify-content: center;
  padding: 120px 24px 80px;
  overflow: hidden;
  flex-direction: column;
  text-align: center;
}

.hero-section__badge {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 32px;
  padding: 6px 16px;
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-pill);
  color: var(--accent);
  font-size: 14px;
  letter-spacing: 0.08em;
  background: var(--glass-bg);
  backdrop-filter: blur(16px) saturate(1.5);
  -webkit-backdrop-filter: blur(16px) saturate(1.5);
  animation: fade-in-up 0.6s ease-out;
}

.hero-section__badge-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--accent);
  animation: badge-pulse 2s ease-in-out infinite;
}

h1 {
  max-width: 720px;
  margin: 0 0 24px;
  color: var(--text-primary);
  font-size: clamp(36px, 6vw, 64px);
  font-weight: 700;
  line-height: 1.15;
  letter-spacing: -0.02em;
  animation: fade-in-up 0.6s ease-out 0.1s both;
}

h1 span {
  background: linear-gradient(135deg, var(--accent), var(--accent-sky), var(--accent-cyan-bright));
  background-clip: text;
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
}

.hero-section__description {
  max-width: 640px;
  margin: 0 0 48px;
  color: var(--text-secondary);
  font-size: 18px;
  line-height: 1.7;
  animation: fade-in-up 0.6s ease-out 0.2s both;
}

.hero-section__actions {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  flex-wrap: wrap;
  animation: fade-in-up 0.6s ease-out 0.3s both;
}

.hero-section__community {
  display: inline-flex;
  max-width: 100%;
  min-height: 60px;
  align-items: center;
  gap: 12px;
  margin-top: 24px;
  padding: 10px 16px;
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-md);
  color: var(--text-primary);
  background: var(--glass-bg);
  text-align: left;
  text-decoration: none;
  backdrop-filter: blur(16px) saturate(1.5);
  -webkit-backdrop-filter: blur(16px) saturate(1.5);
  transition: background-color var(--duration-ui) var(--ease-standard);
}

.hero-section__community:hover {
  background: var(--glass-bg-strong);
}

.hero-section__community-icon,
.hero-section__community-arrow {
  width: 20px;
  height: 20px;
  flex: none;
  color: var(--accent);
}

.hero-section__community-text {
  display: grid;
  min-width: 0;
  gap: 2px;
  line-height: 1.4;
}

.hero-section__community-text strong {
  font-size: 14px;
}

.hero-section__community-text span {
  color: var(--text-secondary);
  font-size: 13px;
  overflow-wrap: anywhere;
}

.hero-section__more {
  margin-top: 24px;
  color: var(--text-secondary);
  font-size: 14px;
  text-underline-offset: 4px;
}

.hero-section__more:hover {
  color: var(--accent);
}

.hero-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-height: 48px;
  border: 1px solid transparent;
  border-radius: var(--radius-pill);
  font-size: 16px;
  font-weight: 500;
  text-decoration: none;
  transition:
    transform var(--duration-ui) var(--ease-standard),
    box-shadow var(--duration-ui) var(--ease-standard),
    background-color var(--duration-ui) var(--ease-standard);
}

.hero-button svg {
  width: 18px;
  height: 18px;
}

.hero-button--primary {
  padding: 14px 36px;
  color: #fff;
  background: var(--accent);
  box-shadow: 0 4px 20px color-mix(in srgb, var(--accent) 25%, transparent);
}

.hero-button--glass {
  padding: 10px 20px;
  border-color: var(--glass-border);
  color: var(--text-primary);
  font-size: 14px;
  background: var(--glass-bg);
  backdrop-filter: blur(16px) saturate(1.5);
  -webkit-backdrop-filter: blur(16px) saturate(1.5);
}

.hero-button:hover {
  transform: translateY(-2px);
}

.hero-button--primary:hover {
  box-shadow: 0 6px 28px color-mix(in srgb, var(--accent) 35%, transparent);
}

.hero-button--glass:hover {
  background: var(--glass-bg-strong);
}

.hero-button:active {
  transform: scale(0.97);
}

@keyframes badge-pulse {
  0%,
  100% {
    opacity: 1;
  }

  50% {
    opacity: 0.4;
  }
}

@media (max-width: 900px) {
  h1 {
    font-size: 32px;
  }

  .hero-section__description {
    font-size: 16px;
  }
}

@media (max-width: 520px) {
  .hero-section {
    min-height: auto;
    justify-content: flex-start;
    padding: calc(88px + env(safe-area-inset-top, 0px)) 18px 48px;
    overflow: visible;
  }

  .hero-section__actions {
    width: 100%;
  }

  .hero-button {
    width: 100%;
    min-height: 48px;
  }

  .hero-section__community {
    width: 100%;
  }
}
</style>
