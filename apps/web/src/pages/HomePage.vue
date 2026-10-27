<script setup lang="ts">
import { ref } from 'vue';

import FeatureCards from '@/components/home/FeatureCards.vue';
import HeroSection from '@/components/home/HeroSection.vue';
import LandingNav from '@/components/home/LandingNav.vue';
import LoginModal from '@/components/home/LoginModal.vue';
import HomeValueProposition from '@/components/home/HomeValueProposition.vue';
import { ICP_LICENSE_NUMBER, MIIT_BEIAN_URL } from '@/config/legal';
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

    <!--
      备案期间临时隐藏「定价」版块：个人性质 ICP 备案要求网站不得展示产品、价格、商品等经营性内容，
      即使内容标注“完全免费”，“定价”这一标题与栏目结构仍会被审核方视为商业化意图信号，可能导致整改或注销备案号。
      备案通过且确需商业化展示时，再连同 .home-pricing 样式及页脚、导航中的入口一并取消注释。
    -->
    <!--
    <section id="pricing" class="home-pricing" aria-labelledby="pricing-title">
      <h2 id="pricing-title">定价</h2>
      <p>目前完全免费!</p>
    </section>
    -->

    <footer class="home-footer">
      <p>© 2026 PromptOptimizer · Prompt Context Studio</p>
      <nav aria-label="页脚链接">
        <a href="#features">功能特性</a>
        <!-- 备案期间隐藏「定价」入口，与首页版块一起恢复 -->
        <!-- <a href="#pricing">定价</a> -->
        <RouterLink to="/workbench">提示词优化</RouterLink>
        <RouterLink to="/terms">用户协议</RouterLink>
        <RouterLink to="/privacy">隐私政策</RouterLink>
      </nav>
      <!-- 编号本身链到工信部备案管理系统，放在页脚底部中央，供公众核对。 -->
      <p v-if="ICP_LICENSE_NUMBER">
        <a :href="MIIT_BEIAN_URL" target="_blank" rel="noopener noreferrer">{{ ICP_LICENSE_NUMBER }}</a>
      </p>
    </footer>

    <LoginModal v-model="authOpen" :mode="authMode" />
  </div>
</template>

<style scoped>
.home-page {
  min-height: 100vh;
  background: transparent;
}

/* 备案期间随「定价」版块一同隐藏；恢复版块时取消注释 */
/*
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
*/

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
  /* 备案期间随「定价」版块一同隐藏；恢复版块时取消注释 */
  /*
  .home-pricing {
    padding-inline: 18px;
  }

  .home-pricing h2 {
    font-size: 27px;
  }
  */
}
</style>
