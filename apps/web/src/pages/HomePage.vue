<script setup lang="ts">
import { ref } from 'vue';

import FeatureCards from '@/components/home/FeatureCards.vue';
import HeroSection from '@/components/home/HeroSection.vue';
import LandingNav from '@/components/home/LandingNav.vue';
import LoginModal from '@/components/home/LoginModal.vue';
import HomeValueProposition from '@/components/home/HomeValueProposition.vue';
import type { AuthModalMode } from '@/components/home/LoginModal.vue';

const authOpen = ref(false);
const authMode = ref<AuthModalMode>('login');

const openAuth = (mode: AuthModalMode): void => {
  authMode.value = mode;
  authOpen.value = true;
};
</script>

<template>
  <div class="home-page">
    <LandingNav @login="openAuth('login')" @register="openAuth('register')" />
    <HeroSection />
    <HomeValueProposition />
    <FeatureCards />

    <section id="pricing" class="home-pricing" aria-labelledby="pricing-title">
      <h2 id="pricing-title">定价</h2>
      <p>当前提供本地工作台能力，付费方案稍后公布。</p>
    </section>

    <footer class="home-footer">
      <p>© 2026 PromptOptimizer · Prompt Context Studio</p>
      <nav aria-label="页脚链接">
        <a href="#features">功能特性</a>
        <a href="#pricing">定价</a>
        <RouterLink to="/workbench">工作台</RouterLink>
      </nav>
    </footer>

    <LoginModal v-model="authOpen" :mode="authMode" />
  </div>
</template>

<style scoped>
.home-page {
  min-height: 100vh;
  background: transparent;
}

.home-pricing {
  max-width: 720px;
  margin: 0 auto;
  padding: 0 24px 96px;
  text-align: center;
}

.home-pricing h2 {
  margin: 0 0 8px;
  color: var(--text-primary);
  font-size: 32px;
}

.home-pricing p {
  margin: 0;
  color: var(--text-secondary);
  font-size: 16px;
}

.home-footer {
  display: grid;
  justify-items: center;
  gap: 12px;
  padding: 40px 24px;
  border-top: 1px solid var(--glass-border-subtle);
  color: var(--text-muted);
  font-size: 14px;
  text-align: center;
}

.home-footer p {
  margin: 0;
}

.home-footer nav {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 16px;
}

.home-footer a {
  color: var(--text-secondary);
  text-decoration: none;
}

.home-footer a:hover {
  color: var(--accent);
}

@media (max-width: 520px) {
  .home-pricing {
    padding-inline: 18px;
  }

  .home-pricing h2 {
    font-size: 27px;
  }
}
</style>
