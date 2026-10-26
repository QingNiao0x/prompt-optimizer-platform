<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { Delete, RefreshRight, Upload, View } from '@element-plus/icons-vue';
import {
  ElAlert, ElButton, ElDialog, ElEmpty, ElInput, ElMessage, ElMessageBox, ElOption,
  ElPagination, ElSelect, ElTable, ElTableColumn, ElTag,
} from 'element-plus';
import { imagePromptApi } from '@/services/imagePromptApi';
import { getApiErrorMessage } from '@/services/http';
import { useAuthStore } from '@/stores/auth';
import { useImagePromptStore } from '@/stores/imagePrompt';
import { formatModelVersion } from '@/features/models/modelVersion';
import type { ImagePromptDocument, ImagePromptMode, ImagePromptQuestion } from '@/types/imagePrompt';

const router = useRouter();
const auth = useAuthStore();
const store = useImagePromptStore();
const pageSizes = [10, 20, 50];
const records = ref<ImagePromptDocument[]>([]);
const currentPage = ref(1);
const pageSize = ref(10);
const total = ref(0);
const mode = ref<ImagePromptMode | ''>('');
const loading = ref(false);
const error = ref('');
const detailVisible = ref(false);
const detailLoading = ref(false);
const detail = ref<ImagePromptDocument>();
const manualCopyVisible = ref(false);
const actionId = ref('');
const identity = computed(() => [auth.user?.userId, auth.user?.tenantId, auth.user?.workspaceId].join('|'));
let active = true;
let epoch = 0;
let listSequence = 0;
let detailSequence = 0;
const valid = (expectedEpoch: number): boolean => active && expectedEpoch === epoch;
const modeLabel = (value: ImagePromptMode): string => value === 'CUSTOM_CREATION' ? 'Plan 新图创作' : '参考图还原';
const outputLabel = (document: ImagePromptDocument): string => ({ IMAGE: '图片', VIDEO: '视频', PPT: 'PPT' }[document.input.outputType ?? 'IMAGE']);
const materialCount = (document: ImagePromptDocument): number => document.materialSources?.length || document.sources.length;
const unitLabel = (unit: { page?: number | null; timestampMs?: number | null }): string => unit.page != null
  ? `第 ${unit.page} 页` : unit.timestampMs != null ? `${(unit.timestampMs / 1000).toFixed(2)} 秒画面` : '图片';
/** 历史引用转换为可读素材名，不把平台内部 ID 当成下游能够访问的附件。 */
const relationSource = (assetId?: string, unitId?: string): string => {
  if (!assetId) return '文字说明';
  const source = detail.value?.materialSources?.find(item => item.assetId === assetId);
  const unit = source?.units.find(item => item.id === unitId);
  return source ? `${source.name}${unit ? ` · ${unitLabel(unit)}` : ''}`
    : detail.value?.sources.find(item => item.id === assetId)?.name ?? '已过期素材';
};
const operationLabel = { PRESERVE: '保留', REPLACE: '替换', REMOVE: '移除', FUSE: '融合' } as const;
// 原始关系完整保留用于追溯；存在实际回答后，不把这些初始卡片冒充确认后的最新约束。
const hasActualPlanAnswers = computed(() => detail.value?.answers.some(answer => answer.optionIds.length > 0 || Boolean(answer.customText.trim())) ?? false);
const formatDate = (value: string): string => {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false });
};

/** 分页和模式均由服务端筛选；切换筛选、身份或卸载后，迟到结果不能覆盖当前私有列表。 */
const loadPage = async (): Promise<void> => {
  const expectedEpoch = epoch;
  const sequence = ++listSequence;
  loading.value = true;
  error.value = '';
  try {
    const value = await imagePromptApi.history(currentPage.value, pageSize.value, mode.value || undefined);
    if (!valid(expectedEpoch) || sequence !== listSequence) return;
    // 删除最后一条记录或其他标签页删除记录后，退到仍存在的最后一页。
    const lastPage = Math.max(1, Math.ceil(value.total / pageSize.value));
    if (currentPage.value > lastPage) {
      currentPage.value = lastPage;
      await loadPage();
      return;
    }
    records.value = value.records;
    total.value = value.total;
  } catch (failure: unknown) {
    if (valid(expectedEpoch) && sequence === listSequence) {
      records.value = [];
      total.value = 0;
      error.value = getApiErrorMessage(failure);
    }
  } finally {
    if (valid(expectedEpoch) && sequence === listSequence) loading.value = false;
  }
};
const changePage = (value: number): void => {
  if (value === currentPage.value) return;
  currentPage.value = value;
  void loadPage();
};
const changeSize = (value: number): void => {
  if (!pageSizes.includes(value) || value === pageSize.value) return;
  pageSize.value = value;
  currentPage.value = 1;
  void loadPage();
};
watch(mode, () => { currentPage.value = 1; void loadPage(); });

const openDetail = async (id: string | null): Promise<void> => {
  if (!id) return;
  const expectedEpoch = epoch;
  const sequence = ++detailSequence;
  detailVisible.value = true;
  detailLoading.value = true;
  detail.value = undefined;
  try {
    const value = await imagePromptApi.detail(id);
    if (valid(expectedEpoch) && sequence === detailSequence) detail.value = value;
  } catch (failure: unknown) {
    if (valid(expectedEpoch) && sequence === detailSequence) {
      detailVisible.value = false;
      ElMessage.error(getApiErrorMessage(failure));
    }
  } finally {
    if (valid(expectedEpoch) && sequence === detailSequence) detailLoading.value = false;
  }
};
const closeDetail = (): void => {
  detailSequence += 1;
  detail.value = undefined;
  detailLoading.value = false;
  manualCopyVisible.value = false;
};
const answerText = (question: ImagePromptQuestion): string => {
  const answer = detail.value?.answers.find(value => value.questionId === question.id);
  if (!answer) return '未回答';
  return [...answer.optionIds.map(id => question.options.find(option => option.id === id)?.label ?? id), answer.customText]
    .filter(Boolean).join('；') || '未回答';
};
const copyPrompt = async (): Promise<void> => {
  if (!detail.value) return;
  const expectedEpoch = epoch;
  const document = detail.value;
  try {
    await navigator.clipboard.writeText(document.optimizedPrompt);
    if (valid(expectedEpoch) && detail.value === document) ElMessage.success('提示词已复制。');
  } catch {
    if (valid(expectedEpoch) && detail.value === document) manualCopyVisible.value = true;
  }
};

/** 只读取最新已保存正文并恢复到图像工作台；未确认的付费操作和未保存编辑稿不能被静默覆盖。 */
const restore = async (id: string | null): Promise<void> => {
  if (!id || store.busy || actionId.value) return;
  const expectedEpoch = epoch;
  if (store.recoveryPending) {
    ElMessage.warning('当前图像任务结果尚未确认，请先返回图像转提示词查询处理结果。');
    return;
  }
  if (store.hasDraftChanges) {
    try {
      await ElMessageBox.confirm('当前图像提示词编辑稿尚未保存，是否放弃编辑并载入历史记录？', '载入图像提示词',
        { confirmButtonText: '放弃编辑并载入', cancelButtonText: '保留编辑稿', type: 'warning' });
    } catch { return; }
  }
  if (!valid(expectedEpoch) || store.busy) return;
  actionId.value = id;
  try {
    const value = await imagePromptApi.detail(id);
    if (!valid(expectedEpoch)) return;
    store.restore(value);
    await router.push({ path: '/image-to-prompt', query: { pane: 'result' } });
  } catch (failure: unknown) {
    if (valid(expectedEpoch)) ElMessage.error(getApiErrorMessage(failure));
  } finally {
    if (valid(expectedEpoch)) actionId.value = '';
  }
};
const remove = async (id: string | null): Promise<void> => {
  if (!id || actionId.value) return;
  const expectedEpoch = epoch;
  try {
    await ElMessageBox.confirm('删除后不能再查看、编辑或再次优化这条记录。', '删除图像提示词记录',
      { confirmButtonText: '删除记录', cancelButtonText: '取消', type: 'warning' });
  } catch { return; }
  if (!valid(expectedEpoch)) return;
  actionId.value = id;
  try {
    await imagePromptApi.remove(id);
    if (!valid(expectedEpoch)) return;
    // 删除当前结果时只清理该记录正文，保留用户随后输入的新构想和选图。
    if (store.result?.recordId === id) {
      store.result = undefined; store.undoDocument = undefined; store.editDraft = ''; store.isEditing = false;
    }
    if (detail.value?.recordId === id) detailVisible.value = false;
    ElMessage.success('图像提示词记录已删除。');
    await loadPage();
  } catch (failure: unknown) {
    if (valid(expectedEpoch)) ElMessage.error(getApiErrorMessage(failure));
  } finally {
    if (valid(expectedEpoch)) actionId.value = '';
  }
};

// 页面状态也按完整身份隔离，不让旧账号的列表、详情或延迟操作写回新账号。
watch(identity, () => {
  epoch += 1; listSequence += 1; detailSequence += 1;
  records.value = []; total.value = 0; currentPage.value = 1;
  error.value = ''; loading.value = false; actionId.value = '';
  detailVisible.value = false; closeDetail();
  if (auth.user) void loadPage();
}, { flush: 'sync' });
onMounted(loadPage);
onBeforeUnmount(() => { active = false; epoch += 1; listSequence += 1; detailSequence += 1; });
</script>

<template>
  <section class="image-history-panel" aria-label="图像转提示词历史记录">
    <div class="image-history-toolbar">
      <ElSelect v-model="mode" aria-label="图像记录模式" placeholder="全部模式" class="mode-filter">
        <ElOption label="全部模式" value="" />
        <ElOption label="参考图还原" value="REFERENCE_RECONSTRUCTION" />
        <ElOption label="Plan 新图创作" value="CUSTOM_CREATION" />
      </ElSelect>
      <ElButton :icon="RefreshRight" :loading="loading" @click="loadPage">刷新记录</ElButton>
      <ElButton type="primary" @click="router.push('/image-to-prompt')">前往图像转提示词</ElButton>
    </div>
    <p class="image-history-note">生成成功后记录提示词、模式、素材说明、替换关系与必要分析。原件不长期保存，载入历史不会重新识别素材或调用模型。</p>
    <ElAlert v-if="error" :title="error" type="error" :closable="false" />
    <ElAlert v-if="records.some(record => !record.persistentHistory)" title="本机演示记录保存在服务内存中，服务重启后不会保留。" type="info" :closable="false" />
    <div v-loading="loading" class="image-history-results">
      <ElEmpty v-if="!loading && !error && !records.length" description="还没有图像转提示词记录" />
      <template v-else-if="records.length">
        <ul class="image-history-cards">
          <li v-for="record in records" :key="record.recordId!" class="image-history-card">
            <div class="record-meta"><ElTag size="small" effect="plain">{{ modeLabel(record.mode) }}</ElTag><time>{{ formatDate(record.createdAt) }}</time></div>
            <p class="record-preview">{{ record.optimizedPrompt.slice(0, 160) }}</p>
            <span class="record-model">{{ formatModelVersion(record.modelName) }} · {{ outputLabel(record) }} · {{ materialCount(record) }} 份素材</span>
            <div class="record-actions">
              <ElButton text size="small" :icon="View" @click="openDetail(record.recordId)">查看</ElButton>
              <ElButton text size="small" :icon="Upload" :disabled="store.busy || !!actionId" @click="restore(record.recordId)">载入</ElButton>
              <ElButton text size="small" type="danger" :icon="Delete" :disabled="!!actionId" @click="remove(record.recordId)">删除</ElButton>
            </div>
          </li>
        </ul>
        <ElTable :data="records" row-key="recordId" class="image-history-table">
          <ElTableColumn label="生成时间" width="180"><template #default="{ row }">{{ formatDate(row.createdAt) }}</template></ElTableColumn>
          <ElTableColumn label="模式" width="140"><template #default="{ row }"><ElTag size="small" effect="plain">{{ modeLabel(row.mode) }}</ElTag></template></ElTableColumn>
          <ElTableColumn label="模型" min-width="160"><template #default="{ row }"><span class="record-model">{{ formatModelVersion(row.modelName) }}</span></template></ElTableColumn>
          <ElTableColumn label="生成的提示词" min-width="220"><template #default="{ row }"><span class="table-preview">{{ row.optimizedPrompt.slice(0, 160) }}</span></template></ElTableColumn>
          <ElTableColumn label="成品／素材" width="110"><template #default="{ row }">{{ outputLabel(row as ImagePromptDocument) }}／{{ materialCount(row as ImagePromptDocument) }} 份</template></ElTableColumn>
          <ElTableColumn label="操作" width="260" align="right"><template #default="{ row }">
            <ElButton text size="small" :icon="View" @click="openDetail(row.recordId)">查看</ElButton>
            <ElButton text size="small" :icon="Upload" :disabled="store.busy || !!actionId" @click="restore(row.recordId)">载入</ElButton>
            <ElButton text size="small" type="danger" :icon="Delete" :disabled="!!actionId" @click="remove(row.recordId)">删除</ElButton>
          </template></ElTableColumn>
        </ElTable>
      </template>
    </div>
    <div v-if="total > 0" class="image-history-pagination">
      <ElPagination background layout="total, prev, pager, next, sizes" :current-page="currentPage" :page-size="pageSize"
        :page-sizes="pageSizes" :total="total" :disabled="loading" @current-change="changePage" @size-change="changeSize" />
    </div>

    <ElDialog v-model="detailVisible" title="图像提示词记录详情" width="min(820px, calc(100vw - 24px))" @close="closeDetail">
      <div v-loading="detailLoading" class="image-history-detail">
        <template v-if="detail">
          <div class="record-meta"><ElTag effect="plain">{{ modeLabel(detail.mode) }}</ElTag><span>{{ formatModelVersion(detail.modelName) }}</span><time>{{ formatDate(detail.createdAt) }}</time></div>
          <ElAlert v-if="detail.mock" title="本机演示结果，不代表真实视觉识别。" type="info" :closable="false" />
          <section v-if="detail.input.creationIntent" class="detail-block"><h3>{{ outputLabel(detail) }}创作构想</h3><pre>{{ detail.input.creationIntent }}</pre></section>
          <section v-if="detail.input.exactText" class="detail-block"><h3>画面指定文案</h3><pre>{{ detail.input.exactText }}</pre></section>
          <section v-if="detail.input.context" class="detail-block"><h3>项目上下文</h3><pre>{{ detail.input.context }}</pre></section>
          <section class="detail-block"><h3>生成的提示词</h3><pre>{{ detail.optimizedPrompt }}</pre><ElButton type="primary" @click="copyPrompt">复制提示词</ElButton></section>
          <section v-if="detail.sources.length" class="detail-block"><h3>参考图片来源</h3><p v-for="source in detail.sources" :key="source.id">{{ source.name }} · {{ source.width }} × {{ source.height }}</p><p>仅保留来源说明；历史不恢复原图。</p></section>
          <section v-if="detail.materialSources?.length" class="detail-block" aria-label="历史素材对照表"><h3>素材对照表与识别范围</h3>
            <article v-for="source in detail.materialSources" :key="source.assetId" class="material-history-source">
              <h4>{{ source.track === 'REFERENCE' ? '参考素材' : '替换素材（新主体／新内容）' }}：{{ source.name }}</h4>
              <p>{{ source.kind === 'PPTX' ? 'PPTX 幻灯片' : source.kind === 'VIDEO' ? '视频片段' : '图片' }} · 仅使用以下 {{ source.units.length }} 个选中画面</p>
              <p v-for="unit in source.units" :key="unit.id">{{ unitLabel(unit) }}<template v-if="unit.masks.length"> · 已遮挡 {{ unit.masks.length }} 处区域</template></p>
              <p v-for="warning in source.warnings" :key="warning">{{ warning }}</p>
            </article>
            <p>需要在下游使用参考素材时，请按对照关系重新上传文件。历史只保留文字与来源范围，不恢复原件、页面或视频帧。</p>
          </section>
          <section v-if="detail.input.relations?.length" class="detail-block" aria-label="历史替换关系"><h3>{{ hasActualPlanAnswers ? '初始保留与替换关系' : '用户指定的保留与替换关系' }}</h3>
            <p v-if="hasActualPlanAnswers">以下卡片记录初始要求，用于追溯；当前要求以 Plan 确认题答和当前正文为准。</p>
            <article v-for="relation in detail.input.relations" :key="relation.id" class="material-history-source">
              <h4>{{ operationLabel[relation.operation] }} · {{ relation.referenceObject }}</h4>
              <p>参考来源：{{ relationSource(relation.referenceAssetId, relation.referenceUnitId) }}</p>
              <p v-if="relation.replacementAssetId || relation.replacementObject">新内容来源：{{ relationSource(relation.replacementAssetId, relation.replacementUnitId) }} · {{ relation.replacementObject }}</p>
              <p v-if="relation.attributes.length">作用属性：{{ relation.attributes.join('、') }}</p><p v-if="relation.scope">作用范围：{{ relation.scope }}</p><p v-if="relation.instruction">{{ relation.instruction }}</p>
            </article>
          </section>
          <section v-if="detail.questions.length" class="detail-block"><h3>Plan 确认记录</h3><div v-for="question in detail.questions" :key="question.id"><h4>{{ question.question }}</h4><pre>{{ answerText(question) }}</pre></div></section>
          <section v-if="detail.analysis.length" class="detail-block"><h3>视觉分析</h3><p v-for="(observation, index) in detail.analysis" :key="index">{{ observation.status === 'OBSERVED' ? '观察' : observation.status === 'UNCERTAIN' ? '不确定' : '推断' }}：{{ observation.description }}</p></section>
          <section v-if="detail.warnings.length" class="detail-block"><h3>注意事项</h3><p v-for="warning in detail.warnings" :key="warning">{{ warning }}</p></section>
        </template>
      </div>
    </ElDialog>
    <ElDialog v-model="manualCopyVisible" title="手动复制提示词" width="min(720px, calc(100vw - 24px))">
      <ElInput :model-value="detail?.optimizedPrompt ?? ''" type="textarea" :rows="12" readonly aria-label="手动复制历史图像提示词" />
    </ElDialog>
  </section>
</template>

<style scoped>
.image-history-panel { border: 1px solid var(--line-subtle); border-radius: var(--radius-large); background: var(--surface-panel); box-shadow: var(--shadow-panel); overflow: hidden; }
.image-history-toolbar { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; padding: 16px 18px; }
.image-history-toolbar .el-button + .el-button { margin-left: 0; }
.mode-filter { width: 180px; }
.image-history-note { margin: 0; padding: 0 18px 16px; color: var(--ink-muted); font-size: 13px; line-height: 1.7; }
.image-history-results, .image-history-detail { position: relative; min-height: 180px; }
.image-history-results :deep(.el-loading-mask), .image-history-detail :deep(.el-loading-mask) { background: color-mix(in srgb, var(--bg-base) 46%, transparent); backdrop-filter: blur(8px); }
.image-history-cards { display: none; list-style: none; margin: 0; padding: 0; }
.image-history-card { display: grid; gap: 8px; padding: 16px; border-top: 1px solid var(--line-subtle); }
.record-meta { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: center; gap: 10px; color: var(--ink-soft); font-size: 13px; }
.record-preview { margin: 0; color: var(--ink-strong); font-size: 14px; line-height: 1.7; overflow-wrap: anywhere; }
.record-model { color: var(--ink-soft); font-family: var(--font-mono); font-size: 12px; }
.record-actions { display: flex; flex-wrap: wrap; gap: 4px; margin-left: -8px; }
.table-preview { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--ink-muted); }
.image-history-table :deep(th) { height: 46px; color: var(--ink-soft); }
.image-history-table :deep(td) { height: 58px; }
.image-history-pagination { display: flex; justify-content: flex-end; padding: 16px 18px; border-top: 1px solid var(--line-subtle); }
.detail-block { margin-top: 18px; color: var(--ink-muted); }
.detail-block h3, .detail-block h4 { margin: 0 0 8px; color: var(--ink-strong); font-size: 14px; }
.detail-block p { white-space: pre-wrap; overflow-wrap: anywhere; font-size: 13px; line-height: 1.7; }
.detail-block pre { margin: 0 0 12px; padding: 14px; max-height: 320px; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; border: 1px solid var(--line-subtle); border-radius: var(--radius-sm); background: var(--surface-code); color: var(--ink-muted); font-family: var(--font-mono); line-height: 1.7; }
.material-history-source { padding: 12px; margin: 10px 0; border: 1px solid var(--line-subtle); border-radius: var(--radius-sm); }
@media (max-width: 720px) {
  .image-history-cards { display: grid; }
  .image-history-table { display: none; }
  .image-history-pagination { justify-content: center; padding: 16px 8px; }
  .image-history-pagination :deep(.el-pagination) { flex-wrap: wrap; justify-content: center; gap: 8px; }
}
</style>
