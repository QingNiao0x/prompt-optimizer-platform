<script setup lang="ts">
import { ref } from 'vue';
import NewLoanPage from './views/NewLoanPage.vue';
import EditLoanPage from './views/EditLoanPage.vue';
import DepositPage from './views/DepositPage.vue';
const activeView = ref<'new' | 'edit' | 'deposit'>('new');
</script>

<template>
  <div class="app-shell">
    <aside class="sidebar"><p class="brand">KIT DESK</p><h2>社区器材借用台</h2><nav aria-label="业务入口"><button :class="{ active: activeView === 'new' }" @click="activeView = 'new'">新建借用</button><button :class="{ active: activeView === 'edit' }" @click="activeView = 'edit'">修改借用</button><button :class="{ active: activeView === 'deposit' }" @click="activeView = 'deposit'">押金预览</button></nav><p>合成演示数据<br>不连接任何外部服务</p></aside>
    <main><NewLoanPage v-if="activeView === 'new'" /><EditLoanPage v-else-if="activeView === 'edit'" /><DepositPage v-else /></main>
  </div>
</template>
