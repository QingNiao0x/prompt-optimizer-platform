<script setup lang="ts">
import { onMounted, onScopeDispose, ref } from 'vue';
import { ElButton, ElInput, ElMessage } from 'element-plus';
import { getApiErrorMessage, httpClient } from '@/services/http';

/** 手机流程复用原有图形验证码端点；不解析图片答案，也不在浏览器存储验证码。 */
const answer = defineModel<string>({ default: '' });
const url = ref('');
const loading = ref(false);
let disposed = false;
const refresh = async (): Promise<void> => {
  if (loading.value) return;
  loading.value = true;
  answer.value = '';
  try {
    const response = await httpClient.get<Blob>('/api/v1/auth/captcha', { responseType: 'blob', headers: { Accept: 'image/png' } });
    if (disposed) return;
    if (url.value) URL.revokeObjectURL(url.value);
    url.value = URL.createObjectURL(response.data);
  } catch (error: unknown) { if (!disposed) ElMessage.error(getApiErrorMessage(error)); }
  finally { loading.value = false; }
};
onMounted(() => { void refresh(); });
onScopeDispose(() => { disposed = true; if (url.value) URL.revokeObjectURL(url.value); });
defineExpose({ refresh });
</script>

<template>
  <div class="image-captcha">
    <ElInput v-model="answer" aria-label="图形验证码" placeholder="图中字符" maxlength="4" autocomplete="off" />
    <ElButton :loading="loading" aria-label="刷新图形验证码" @click="refresh">
      <img v-if="url" :src="url" alt="图形验证码，点击刷新" />
      <span v-else>刷新图片</span>
    </ElButton>
  </div>
</template>

<style scoped>
.image-captcha { display: flex; gap: 10px; align-items: center; }
.image-captcha .el-button { height: 42px; padding: 0; min-width: 120px; overflow: hidden; }
.image-captcha img { height: 40px; width: 134px; }
</style>
