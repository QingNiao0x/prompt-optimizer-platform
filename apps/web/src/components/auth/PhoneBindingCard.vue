<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { ElButton } from 'element-plus';
import { useAuthStore } from '@/stores/auth';
import PhoneBindingForm from './PhoneBindingForm.vue';

const auth = useAuthStore();
const opened = ref(false);
onMounted(() => { void auth.loadCapabilities(); });
</script>

<template>
  <section class="phone-security" aria-labelledby="phone-security-title">
    <h2 id="phone-security-title">账户安全</h2>
    <p v-if="auth.user?.phoneBound">已绑定手机号：{{ auth.user.maskedPhone }}</p>
    <template v-else>
      <p>尚未绑定手机号。建议绑定手机号，完善账户信息；邮箱和手机号将登录同一个账号。</p>
      <ElButton v-if="auth.capabilities.phoneBinding && !opened" type="primary" plain @click="opened = true">绑定手机号</ElButton>
      <small v-if="!auth.capabilities.phoneBinding">当前环境尚未开放短信绑定。</small>
    </template>
    <PhoneBindingForm v-if="opened && auth.capabilities.phoneBinding && !auth.user?.phoneBound" @success="opened = false" />
  </section>
</template>

<style scoped>
.phone-security { padding: 24px; border: 1px solid var(--glass-border); border-radius: var(--radius-md); background: var(--surface-panel); box-shadow: var(--shadow-panel); }
.phone-security h2 { margin: 0 0 12px; }
.phone-security p, .phone-security small { color: var(--text-secondary); line-height: 1.7; }
</style>
