<script setup lang="ts">
import { Collection, MagicStick } from '@element-plus/icons-vue';
</script>

<template>
  <section class="home-hero" aria-labelledby="home-hero-title">
    <div class="home-hero__badge">
      <span class="home-hero__badge-dot" aria-hidden="true"></span>
      Context-Aware Prompt Engineering
    </div>

    <h1 id="home-hero-title">
      让模型先理解你的工程，<br>
      <span>再理解你的要求。</span>
    </h1>

    <p class="home-hero__description">
      上传项目上下文，输入你的想法，一键生成结构化、高质量的 AI 提示词。
      告别反复调试，让每一次对话都精准高效。
    </p>

    <div class="home-hero__actions">
      <RouterLink class="home-button home-button--primary" to="/workbench">
        <MagicStick aria-hidden="true" />
        开始增强提示词
      </RouterLink>
      <a class="home-button home-button--glass" href="#features">
        功能特性
        <Collection aria-hidden="true" />
      </a>
    </div>
  </section>
</template>

<style scoped>
.home-hero {
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

.home-hero__badge {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 32px;
  padding: 6px 16px;
  border: 1px solid var(--glass-border);
  border-radius: 9999px;
  color: var(--accent);
  font-size: 13px;
  background: var(--glass-bg);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
  animation: fade-in-up 600ms ease-out;
}

.home-hero__badge-dot {
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
  animation: fade-in-up 600ms ease-out 100ms both;
}

h1 span {
  background: linear-gradient(135deg, var(--accent), #60a5fa, #38bdf8);
  background-clip: text;
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
}

.home-hero__description {
  max-width: 520px;
  margin: 0 0 48px;
  color: var(--text-secondary);
  font-size: 18px;
  line-height: 1.7;
  animation: fade-in-up 600ms ease-out 200ms both;
}

.home-hero__actions {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  flex-wrap: wrap;
  animation: fade-in-up 600ms ease-out 300ms both;
}

.home-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-height: 48px;
  border: 1px solid transparent;
  border-radius: 9999px;
  font-size: 16px;
  font-weight: 500;
  text-decoration: none;
  transition: transform 250ms cubic-bezier(0.4, 0, 0.2, 1), box-shadow 250ms cubic-bezier(0.4, 0, 0.2, 1), background 250ms cubic-bezier(0.4, 0, 0.2, 1);
}

.home-button svg {
  width: 18px;
  height: 18px;
}

.home-button--primary {
  padding: 14px 36px;
  color: #fff;
  background: var(--accent);
  box-shadow: 0 4px 20px color-mix(in srgb, var(--accent) 25%, transparent);
}

.home-button--glass {
  padding: 10px 20px;
  border-color: var(--glass-border);
  color: var(--text-primary);
  font-size: 14px;
  background: var(--glass-bg);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
}

.home-button:hover {
  transform: translateY(-1px);
}

.home-button--primary:hover {
  box-shadow: 0 6px 28px color-mix(in srgb, var(--accent) 35%, transparent);
}

.home-button--glass:hover {
  background: var(--glass-bg-strong);
}

.home-button:active {
  transform: scale(0.97);
}

@keyframes fade-in-up {
  from {
    opacity: 0;
    transform: translateY(20px);
  }

  to {
    opacity: 1;
    transform: translateY(0);
  }
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

  .home-hero__description {
    font-size: 16px;
  }
}

@media (max-width: 520px) {
  .home-hero {
    min-height: auto;
    justify-content: flex-start;
    padding: calc(88px + env(safe-area-inset-top, 0px)) 18px 48px;
    overflow: visible;
  }

  .home-hero__actions {
    width: 100%;
  }

  .home-button {
    width: 100%;
    min-height: 48px;
  }
}
</style>
