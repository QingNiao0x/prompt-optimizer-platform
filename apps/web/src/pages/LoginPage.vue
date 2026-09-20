<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router';
import LoginModal from '@/components/home/LoginModal.vue';

const route = useRoute();
const router = useRouter();
</script>

<template>
  <section aria-label="登录提示">
    <p v-if="route.query.expired">登录已失效，请重新登录。未提交的内容不会自动发送。</p>
    <p v-if="route.query.unavailable">暂时无法验证登录状态，请检查后端服务后重试。</p>
    <LoginModal :model-value="true" mode="login" @update:model-value="router.replace('/')" />
  </section>
</template>
