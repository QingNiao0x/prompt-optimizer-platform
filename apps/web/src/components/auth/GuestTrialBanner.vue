<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElAlert, ElButton } from 'element-plus';
import { useAuthStore } from '@/stores/auth';
import { useTrialStore } from '@/stores/trial';
import { trialFeatureInfo, type TrialFeature } from '@/types/trial';

const props = defineProps<{ feature: TrialFeature }>();
const auth = useAuthStore(), trial = useTrialStore(), router = useRouter(), route = useRoute();
const info = computed(() => trialFeatureInfo[props.feature]);
const exhausted = computed(() => trial.initialized && !trial.error && trial.status?.available
  && trial.remaining(props.feature) === 0);
const title = computed(() => trial.error ? '暂时无法确认免费体验额度'
  : !trial.initialized ? '正在确认免费体验额度…'
  : !trial.status?.available ? '免费体验暂不可用，请注册或登录后使用'
  : `${info.value.label}免费体验：剩余 ${trial.remaining(props.feature)} / ${trial.quota(props.feature)?.limit ?? info.value.limit} 次`);
const enter = (register: boolean): void => {
  void router.push({ path: '/login', query: { mode: register ? 'register' : 'login', redirect: route.path } });
};
const refresh = (): void => { if (!auth.isAuthenticated) void trial.refresh(); };
onMounted(() => { refresh(); window.addEventListener('focus', refresh); });
onBeforeUnmount(() => window.removeEventListener('focus', refresh));
</script>

<template>
  <ElAlert v-if="!auth.isAuthenticated" class="guest-trial-banner" :title="title"
    :type="exhausted || trial.error ? 'warning' : 'info'" :closable="false" show-icon>
    <p v-if="exhausted">免费体验次数已用完，请注册或登录后继续使用。</p>
    <p v-else-if="trial.error">{{ trial.error }}</p>
    <p v-else>次数不按天重置，服务端受理的生成尝试计次；刷新或新建任务不会补充额度。访客结果仅临时保留，历史记录需注册登录后使用。</p>
    <div class="guest-trial-actions">
      <ElButton type="primary" size="small" @click="enter(true)">立即注册</ElButton>
      <ElButton size="small" @click="enter(false)">已有账号，登录</ElButton>
      <ElButton v-if="trial.error" size="small" :loading="trial.loading" @click="refresh">重试获取额度</ElButton>
    </div>
  </ElAlert>
</template>

<style scoped>
.guest-trial-banner { margin-bottom: 16px; }
.guest-trial-banner p { margin: 6px 0 10px; line-height: 1.6; }
.guest-trial-actions { display: flex; flex-wrap: wrap; gap: 8px; }
.guest-trial-actions :deep(.el-button + .el-button) { margin-left: 0; }
</style>
