<script setup lang="ts">
import { Delete, RefreshRight, Upload, View } from '@element-plus/icons-vue';
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { ElAlert, ElButton, ElCollapse, ElCollapseItem, ElDialog, ElEmpty, ElInput, ElMessage,
  ElMessageBox, ElPagination, ElTable, ElTableColumn, ElTag } from 'element-plus';
import { useAuthStore } from '@/stores/auth';
import { useTextPolishStore } from '@/stores/textPolish';
import { textPolishApi } from '@/services/textPolishApi';
import { getApiErrorMessage } from '@/services/http';
import { formatModelVersion } from '@/features/models/modelVersion';
import { polishStyles, type PolishDocument } from '@/types/textPolish';

const router = useRouter(), auth = useAuthStore(), store = useTextPolishStore();
const records = ref<PolishDocument[]>([]), currentPage = ref(1), pageSize = ref(10), total = ref(0);
const pageSizes = [10, 20, 50];
const loading = ref(false), error = ref(''), actionId = ref('');
const detail = ref<PolishDocument>(), detailVisible = ref(false), detailLoading = ref(false), manualCopyVisible = ref(false);
const identity = computed(() => [auth.user?.userId, auth.user?.tenantId, auth.user?.workspaceId].join('|'));
let active = true, epoch = 0, listSequence = 0, detailSequence = 0;
const valid = (expectedEpoch: number): boolean => active && epoch === expectedEpoch;
const styleLabel = (value: string): string => polishStyles.find(style => style.value === value)?.label ?? value;
const sourceLabel = (record: PolishDocument): string => record.sourceMode === 'CURRENT' ? '基于已保存当前稿继续润色'
  : record.sourceMode === 'ORIGINAL' ? '基于原文重新生成' : '首次提交原文';
const formatDate = (value: string): string => {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false });
};

/** 沿用文章专用私有接口，服务端分页；身份、分类或请求次序变化后不接受旧列表。 */
const loadPage = async (): Promise<void> => {
  const expectedEpoch = epoch, sequence = ++listSequence;
  loading.value = true; error.value = '';
  try {
    const value = await textPolishApi.history(currentPage.value, pageSize.value);
    if (!valid(expectedEpoch) || sequence !== listSequence) return;
    const lastPage = Math.max(1, Math.ceil(value.total / pageSize.value));
    // 删空末页或其他标签页删除后回到有效页，不能显示有记录却无结果的旧页码。
    if (currentPage.value > lastPage) { currentPage.value = lastPage; await loadPage(); return; }
    records.value = value.records; total.value = value.total;
  } catch (failure: unknown) {
    if (valid(expectedEpoch) && sequence === listSequence) { records.value = []; total.value = 0; error.value = getApiErrorMessage(failure); }
  } finally { if (valid(expectedEpoch) && sequence === listSequence) loading.value = false; }
};
const changePage = (value: number): void => { if (value !== currentPage.value) { currentPage.value = value; void loadPage(); } };
const changeSize = (value: number): void => {
  if (!pageSizes.includes(value) || value === pageSize.value) return;
  pageSize.value = value; currentPage.value = 1; void loadPage();
};
const openDetail = async (id: string): Promise<void> => {
  const expectedEpoch = epoch, sequence = ++detailSequence;
  detailVisible.value = true; detailLoading.value = true; detail.value = undefined;
  try {
    const value = await textPolishApi.detail(id);
    if (valid(expectedEpoch) && sequence === detailSequence) detail.value = value;
  } catch (failure: unknown) {
    if (valid(expectedEpoch) && sequence === detailSequence) { detailVisible.value = false; ElMessage.error(getApiErrorMessage(failure)); }
  } finally { if (valid(expectedEpoch) && sequence === detailSequence) detailLoading.value = false; }
};
const closeDetail = (): void => { detailSequence++; detail.value = undefined; detailLoading.value = false; manualCopyVisible.value = false; };
const copy = async (): Promise<void> => {
  const document = detail.value, expectedEpoch = epoch;
  if (!document) return;
  try { await navigator.clipboard.writeText(document.polishedText); if (valid(expectedEpoch) && detail.value === document) ElMessage.success('润色正文已复制。'); }
  catch { if (valid(expectedEpoch) && detail.value === document) manualCopyVisible.value = true; }
};

/** 只载入最新已保存记录，不再润色；先保护编辑稿和结果未确认的付费操作。 */
const restore = async (id: string): Promise<void> => {
  if (store.busy || store.saving || actionId.value) return;
  const expectedEpoch = epoch;
  if (store.recoveryPending || store.pendingResult) { ElMessage.warning('当前文章还有待确认的处理结果，请先返回文章工作台处理。'); return; }
  if (store.dirty) {
    try { await ElMessageBox.confirm('当前文章编辑稿尚未保存，是否放弃编辑并载入历史记录？', '载入文章记录',
      { confirmButtonText: '放弃编辑并载入', cancelButtonText: '保留编辑稿', type: 'warning' }); } catch { return; }
  }
  if (!valid(expectedEpoch) || store.busy || store.saving) return;
  actionId.value = id;
  try {
    const value = await textPolishApi.detail(id);
    if (!valid(expectedEpoch)) return;
    if (store.busy || store.saving || store.recoveryPending || store.pendingResult) {
      ElMessage.warning('文章处理状态已变化，请先返回工作台处理当前稿。'); return;
    }
    store.restoreRecord(value);
    await router.push({ path: '/text-polish', query: { pane: 'result' } });
  } catch (failure: unknown) { if (valid(expectedEpoch)) ElMessage.error(getApiErrorMessage(failure)); }
  finally { if (valid(expectedEpoch)) actionId.value = ''; }
};
const remove = async (id: string): Promise<void> => {
  if (actionId.value) return;
  const expectedEpoch = epoch;
  try {
    await ElMessageBox.confirm(store.result?.id === id && store.dirty
      ? '这条记录还有未保存的编辑稿，删除后将一起清除。是否删除？'
      : '删除后无法再查看、载入或继续润色这条记录。', '删除文章润色记录',
    { confirmButtonText: '删除记录', cancelButtonText: '取消', type: 'warning' });
  } catch { return; }
  if (!valid(expectedEpoch)) return;
  actionId.value = id;
  try {
    await textPolishApi.remove(id);
    if (!valid(expectedEpoch)) return;
    store.forgetRecord(id);
    if (detail.value?.id === id) detailVisible.value = false;
    ElMessage.success('文章润色记录已删除。');
    await loadPage();
  } catch (failure: unknown) { if (valid(expectedEpoch)) ElMessage.error(getApiErrorMessage(failure)); }
  finally { if (valid(expectedEpoch)) actionId.value = ''; }
};

// 私有正文与迟到响应一同失效；切换历史分类也卸载本组件，避免复用旧身份详情。
watch(identity, () => {
  epoch++; listSequence++; detailSequence++;
  records.value = []; total.value = 0; currentPage.value = 1;
  loading.value = false; error.value = ''; actionId.value = ''; detailVisible.value = false; closeDetail();
  if (auth.user) void loadPage();
}, { flush: 'sync' });
onMounted(loadPage);
onBeforeUnmount(() => { active = false; epoch++; listSequence++; detailSequence++; });
</script>

<template>
  <section class="polish-history-panel" aria-label="文章祛AI味历史记录">
    <div class="polish-history-toolbar">
      <ElButton :icon="RefreshRight" :loading="loading" @click="loadPage">刷新记录</ElButton>
      <ElButton type="primary" @click="router.push('/text-polish')">前往文章祛AI味</ElButton>
    </div>
    <p class="polish-history-note">润色成功后自动保存原文、润色正文、文体与模型。查看、复制或载入记录不会重新调用模型。</p>
    <ElAlert v-if="error" :title="error" type="error" :closable="false" />
    <ElAlert v-if="records.some(record => !record.persistentHistory)" title="演示历史保存在服务内存中，重启后不保留。" type="info" :closable="false" />
    <div v-loading="loading" class="polish-history-results">
      <ElEmpty v-if="!loading && !error && !records.length" description="还没有文章祛AI味记录" />
      <template v-else-if="records.length">
        <ul class="polish-history-cards">
          <li v-for="record in records" :key="record.id" class="polish-history-card">
            <div class="record-meta"><ElTag size="small" effect="plain">{{ styleLabel(record.input.style) }}</ElTag><time>{{ formatDate(record.createdAt) }}</time></div>
            <p class="record-source">原文：{{ record.input.sourceText.slice(0, 100) }}</p>
            <p class="record-preview">{{ record.polishedText.slice(0, 160) }}</p>
            <span class="record-model">{{ formatModelVersion(record.modelName) }}<template v-if="record.editRevision"> · 已编辑</template></span>
            <div class="record-actions">
              <ElButton text size="small" :icon="View" @click="openDetail(record.id)">查看</ElButton>
              <ElButton text size="small" :icon="Upload" :disabled="store.busy || store.saving || !!actionId" @click="restore(record.id)">载入</ElButton>
              <ElButton text size="small" type="danger" :icon="Delete" :disabled="!!actionId" @click="remove(record.id)">删除</ElButton>
            </div>
          </li>
        </ul>
        <ElTable :data="records" row-key="id" class="polish-history-table">
          <ElTableColumn label="生成时间" width="180"><template #default="{ row }">{{ formatDate(row.createdAt) }}</template></ElTableColumn>
          <ElTableColumn label="文体" width="110"><template #default="{ row }"><ElTag size="small" effect="plain">{{ styleLabel(row.input.style) }}</ElTag></template></ElTableColumn>
          <ElTableColumn label="模型" min-width="160"><template #default="{ row }"><span class="record-model">{{ formatModelVersion(row.modelName) }}</span></template></ElTableColumn>
          <ElTableColumn label="原始文本" min-width="180"><template #default="{ row }"><span class="table-preview">{{ row.input.sourceText.slice(0, 160) }}</span></template></ElTableColumn>
          <ElTableColumn label="润色正文" min-width="220"><template #default="{ row }"><span class="table-preview">{{ row.polishedText.slice(0, 160) }}</span></template></ElTableColumn>
          <ElTableColumn label="操作" width="260" align="right"><template #default="{ row }">
            <ElButton text size="small" :icon="View" @click="openDetail(row.id)">查看</ElButton>
            <ElButton text size="small" :icon="Upload" :disabled="store.busy || store.saving || !!actionId" @click="restore(row.id)">载入</ElButton>
            <ElButton text size="small" type="danger" :icon="Delete" :disabled="!!actionId" @click="remove(row.id)">删除</ElButton>
          </template></ElTableColumn>
        </ElTable>
      </template>
    </div>
    <div v-if="total > 0" class="polish-history-pagination">
      <ElPagination background layout="total, prev, pager, next, sizes" :current-page="currentPage" :page-size="pageSize"
        :page-sizes="pageSizes" :total="total" :disabled="loading" @current-change="changePage" @size-change="changeSize" />
    </div>

    <ElDialog v-model="detailVisible" title="文章润色记录详情" width="min(1000px, calc(100vw - 24px))" @close="closeDetail">
      <div v-loading="detailLoading" class="polish-history-detail">
        <template v-if="detail">
          <div class="record-meta"><ElTag effect="plain">{{ styleLabel(detail.input.style) }}</ElTag><span>{{ formatModelVersion(detail.modelName) }}</span><time>{{ formatDate(detail.createdAt) }}</time></div>
          <p class="detail-note">{{ detail.input.depth === 'LIGHT' ? '轻度润色' : '允许调整句式和段落' }} · {{ sourceLabel(detail) }}<template v-if="detail.editRevision"> · 已保存编辑 {{ detail.editRevision }} 次</template></p>
          <ElAlert v-if="detail.mock" title="演示模型回传结果，不代表真实润色效果。" type="info" :closable="false" />
          <div class="article-comparison">
            <section class="detail-block"><h3>原始文本</h3><pre>{{ detail.input.sourceText }}</pre></section>
            <section class="detail-block"><h3>润色正文（当前已保存）</h3><pre>{{ detail.polishedText }}</pre></section>
          </div>
          <div class="record-actions"><ElButton type="primary" @click="copy">复制正文</ElButton><ElButton :disabled="store.busy || store.saving || !!actionId" @click="restore(detail.id)">载入文章工作台</ElButton></div>
          <ElCollapse v-if="detail.editRevision || detail.input.audience || detail.input.requirements || detail.input.lockedTexts.length" class="record-settings">
            <ElCollapseItem v-if="detail.editRevision" title="首次生成正文" name="generated"><pre class="saved-text">{{ detail.generatedText }}</pre></ElCollapseItem>
            <ElCollapseItem v-if="detail.input.audience || detail.input.requirements || detail.input.lockedTexts.length" title="本次润色要求" name="settings">
              <p v-if="detail.input.audience">读者：{{ detail.input.audience }}</p><p v-if="detail.input.requirements" class="saved-text">补充要求：{{ detail.input.requirements }}</p>
              <template v-if="detail.input.lockedTexts.length"><p>保持原样的文字：</p><pre v-for="(text, index) in detail.input.lockedTexts" :key="index" class="saved-text">{{ text }}</pre></template>
            </ElCollapseItem>
          </ElCollapse>
        </template>
      </div>
    </ElDialog>
    <ElDialog v-model="manualCopyVisible" title="手动复制文章" width="min(760px, calc(100vw - 24px))">
      <ElInput :model-value="detail?.polishedText ?? ''" type="textarea" :rows="12" readonly aria-label="手动复制历史润色正文" />
    </ElDialog>
  </section>
</template>

<style scoped>
.polish-history-panel { border: 1px solid var(--line-subtle); border-radius: var(--radius-large); background: var(--surface-panel); box-shadow: var(--shadow-panel); overflow: hidden; }
.polish-history-toolbar { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; padding: 16px 18px; }
.polish-history-toolbar .el-button + .el-button { margin-left: 0; }
.polish-history-note { margin: 0; padding: 0 18px 16px; color: var(--ink-muted); font-size: 13px; line-height: 1.7; }
.polish-history-results, .polish-history-detail { position: relative; min-height: 180px; }
.polish-history-results :deep(.el-loading-mask), .polish-history-detail :deep(.el-loading-mask) { background: color-mix(in srgb, var(--bg-base) 46%, transparent); backdrop-filter: blur(8px); }
.polish-history-cards { display: none; list-style: none; margin: 0; padding: 0; }
.polish-history-card { display: grid; gap: 8px; padding: 16px; border-top: 1px solid var(--line-subtle); }
.record-meta { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 10px; color: var(--ink-soft); font-size: 13px; }
.record-source, .detail-note { color: var(--ink-muted); font-size: 12px; line-height: 1.7; }
.record-source, .record-preview { margin: 0; overflow-wrap: anywhere; }
.record-preview { color: var(--ink-strong); font-size: 14px; line-height: 1.7; }
.record-model { color: var(--ink-soft); font-family: var(--font-mono); font-size: 12px; }
.record-actions { display: flex; flex-wrap: wrap; gap: 4px; }
.table-preview { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--ink-muted); }
.polish-history-table :deep(th) { height: 46px; color: var(--ink-soft); }
.polish-history-table :deep(td) { height: 58px; }
.polish-history-pagination { display: flex; justify-content: flex-end; padding: 16px 18px; border-top: 1px solid var(--line-subtle); }
.article-comparison { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 16px; }
.detail-block { min-width: 0; margin-top: 16px; }
.detail-block h3 { margin: 0 0 8px; color: var(--ink-strong); font-size: 14px; }
.detail-block pre, .saved-text { white-space: pre-wrap; overflow-wrap: anywhere; color: var(--ink-muted); font: inherit; line-height: 1.8; }
.detail-block pre { margin: 0 0 12px; padding: 14px; max-height: 360px; overflow: auto; border: 1px solid var(--line-subtle); border-radius: var(--radius-sm); background: var(--surface-code); }
.record-settings { margin-top: 16px; }
@media (max-width: 720px) {
  .polish-history-cards { display: grid; }
  .polish-history-table { display: none; }
  .article-comparison { grid-template-columns: minmax(0, 1fr); gap: 0; }
  .polish-history-pagination { justify-content: center; padding: 16px 8px; }
  .polish-history-pagination :deep(.el-pagination) { flex-wrap: wrap; justify-content: center; gap: 8px; }
}
</style>
