<script setup lang="ts">
import { Collection } from '@element-plus/icons-vue';

import WandSparklesIcon from '@/components/brand/WandSparklesIcon.vue';
</script>

<template>
  <section class="hero-section" aria-labelledby="home-hero-title">
    <div class="hero-section__badge">
      <span class="hero-section__badge-dot" aria-hidden="true"></span>
      CONTEXT-AWARE PROMPT ENGINEERING
    </div>

    <h1 id="home-hero-title">
      让模型先理解你的工程，<br>
      <span>再理解你的要求。</span>
    </h1>

    <p class="hero-section__description">
      上传项目上下文，输入你的想法，一键生成结构化、高质量的 AI 提示词。
      告别反复调试，让每一次对话都精准高效。
    </p>

    <div class="hero-section__actions">
      <RouterLink class="hero-button hero-button--primary" to="/workbench">
        <WandSparklesIcon />
        开始增强提示词
      </RouterLink>
      <a class="hero-button hero-button--glass" href="#features">
        功能特性
        <Collection aria-hidden="true" />
      </a>
    </div>
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
  max-width: 520px;
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
}
</style>
