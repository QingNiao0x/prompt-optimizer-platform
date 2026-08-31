<script setup lang="ts">
import { Delete, RefreshRight, Upload, View } from '@element-plus/icons-vue';
import {
  ElButton,
  ElDialog,
  ElEmpty,
  ElMessage,
  ElMessageBox,
  ElPagination,
  ElTable,
  ElTableColumn,
  ElTag,
} from 'element-plus';
import { onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';

import { getApiErrorMessage } from '@/services/http';
import {
  deleteHistory,
  getHistory,
  listHistory,
  reoptimizeHistory,
} from '@/services/promptOptimizerApi';
import { useOptimizationStore } from '@/stores/optimization';
import type {
  OptimizationHistoryDetail,
  OptimizationHistorySummary,
  TemplateCode,
} from '@/types/api';

const router = useRouter();
const store = useOptimizationStore();

const loading = ref(false);
const items = ref<OptimizationHistorySummary[]>([]);
const page = ref(0);
const pageSize = ref(10);
const total = ref(0);

const detailVisible = ref(false);
const detailLoading = ref(false);
const detail = ref<OptimizationHistoryDetail>();

const TEMPLATE_LABELS: Record<TemplateCode, string> = {
  AUTO: '自动识别',
  FEATURE_DEVELOPMENT: '新功能开发',
  BUG_FIX: 'Bug 修复',
  REFACTORING: '代码重构',
  TESTING: '测试补充',
};

// 拉取当前页的历史摘要。历史记录按创建时间倒序，最新的排在最前。
const loadPage = async (): Promise<void> => {
  loading.value = true;
  try {
    const response = await listHistory(page.value, pageSize.value);
    items.value = response.data.items;
    total.value = response.data.totalItems;
  } catch (error: unknown) {
    ElMessage.error(getApiErrorMessage(error));
  } finally {
    loading.value = false;
  }
};

const openDetail = async (id: string): Promise<void> => {
  detailLoading.value = true;
  detailVisible.value = true;
  detail.value = undefined;
  try {
    const response = await getHistory(id);
    detail.value = response.data;
  } catch (error: unknown) {
    ElMessage.error(getApiErrorMessage(error));
    detailVisible.value = false;
  } finally {
    detailLoading.value = false;
  }
};

// 载入只恢复原始提示词和选项，文件正文不在历史中保存。
const loadToWorkbench = async (id: string): Promise<void> => {
  try {
    const response = await getHistory(id);
    store.loadFromHistory(response.data);
    ElMessage.success('已载入工作台，可以继续修改后重新增强。');
    await router.push('/');
  } catch (error: unknown) {
    ElMessage.error(getApiErrorMessage(error));
  }
};

const reoptimize = async (id: string): Promise<void> => {
  try {
    await ElMessageBox.confirm(
      '将使用保存的原始输入和脱敏上下文再次优化，并生成一条新历史记录。',
      '重新优化',
      { type: 'warning', confirmButtonText: '开始', cancelButtonText: '取消' },
    );
  } catch {
    return;
  }

  try {
    const response = await reoptimizeHistory(id);
    store.applyReoptimized(response.data);
    ElMessage.success('重新优化完成，请在工作台确认是否应用结果。');
    await router.push('/');
  } catch (error: unknown) {
    ElMessage.error(getApiErrorMessage(error));
  }
};

const remove = async (id: string): Promise<void> => {
  try {
    await ElMessageBox.confirm('删除后无法恢复，是否继续？', '删除历史记录', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    });
  } catch {
    return;
  }

  try {
    await deleteHistory(id);
    ElMessage.success('历史记录已删除。');
    await loadPage();
  } catch (error: unknown) {
    ElMessage.error(getApiErrorMessage(error));
  }
};

const formatDate = (value: string): string => {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(date);
};

const templateLabel = (code: TemplateCode): string => TEMPLATE_LABELS[code] ?? code;

onMounted(loadPage);
</script>

<template>
  <section class="history-page">
    <header class="page-heading">
      <div>
        <span class="eyebrow">History</span>
        <h1>优化历史</h1>
        <p>查看、载入或重新优化已经生成的提示词。历史只保存脱敏摘要，不保存文件正文。</p>
      </div>
    </header>

    <div class="history-card">
      <ElTable v-loading="loading" :data="items" row-key="id" class="history-table">
        <ElTableColumn label="创建时间" width="150">
          <template #default="{ row }">{{ formatDate(row.createdAt) }}</template>
        </ElTableColumn>
        <ElTableColumn label="模板" width="110">
          <template #default="{ row }">
            <ElTag size="small" effect="plain">{{ templateLabel(row.templateCode) }}</ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="模型" width="170">
          <template #default="{ row }">
            <span class="model-cell">{{ row.providerName }} / {{ row.modelName }}</span>
          </template>
        </ElTableColumn>
        <ElTableColumn label="原始提示词" min-width="260">
          <template #default="{ row }">
            <span class="preview-cell">{{ row.rawPromptPreview }}</span>
          </template>
        </ElTableColumn>
        <ElTableColumn label="耗时" width="90">
          <template #default="{ row }">
            {{ row.latencyMs == null ? '—' : `${row.latencyMs} ms` }}
          </template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="300" align="right">
          <template #default="{ row }">
            <ElButton text size="small" :icon="View" @click="openDetail(row.id)">查看</ElButton>
            <ElButton text size="small" :icon="Upload" @click="loadToWorkbench(row.id)">
              载入
            </ElButton>
            <ElButton text size="small" :icon="RefreshRight" @click="reoptimize(row.id)">
              重新优化
            </ElButton>
            <ElButton text size="small" type="danger" :icon="Delete" @click="remove(row.id)">
              删除
            </ElButton>
          </template>
        </ElTableColumn>
        <template #empty>
          <ElEmpty description="还没有优化历史" />
        </template>
      </ElTable>

      <div v-if="total > 0" class="pagination-row">
        <ElPagination
          v-model:current-page="page"
          v-model:page-size="pageSize"
          layout="total, sizes, prev, pager, next"
          :total="total"
          :page-sizes="[10, 20, 50]"
          @current-change="loadPage"
          @size-change="loadPage"
        />
      </div>
    </div>

    <ElDialog v-model="detailVisible" title="历史记录详情" width="min(760px, 92vw)">
      <div v-loading="detailLoading" class="detail-body">
        <template v-if="detail">
          <section class="detail-block">
            <h3>原始提示词</h3>
            <pre>{{ detail.rawPrompt }}</pre>
          </section>
          <section class="detail-block">
            <h3>优化后提示词</h3>
            <pre>{{ detail.optimizedPrompt }}</pre>
          </section>
          <section v-if="detail.sections.length" class="detail-block">
            <h3>结构化段落</h3>
            <div v-for="section in detail.sections" :key="section.type" class="section-item">
              <ElTag size="small" effect="plain" round>{{ section.title }}</ElTag>
              <p>{{ section.content }}</p>
            </div>
          </section>
        </template>
      </div>
    </ElDialog>
  </section>
</template>

<style scoped>
.history-page {
  max-width: 1180px;
  margin: 0 auto;
}

.page-heading {
  margin-bottom: 22px;
}

.eyebrow {
  color: var(--accent-blue);
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.14em;
  text-transform: uppercase;
}

h1 {
  margin: 10px 0 6px;
  color: var(--ink-strong);
  font-family: var(--font-display);
  font-size: clamp(30px, 4vw, 44px);
  letter-spacing: -0.05em;
}

.page-heading p {
  margin: 0;
  color: var(--ink-muted);
  font-size: 13px;
}

.history-card {
  padding: 0;
  border: 1px solid var(--line-subtle);
  border-radius: var(--radius-large);
  background: var(--surface-panel);
  box-shadow: var(--shadow-panel);
}

.history-table {
  width: 100%;
}

.history-table :deep(.el-table__header-wrapper th) {
  height: 46px;
  color: var(--ink-soft);
  font-family: var(--font-mono);
  font-size: 10px;
  font-weight: 500;
  letter-spacing: 0.04em;
  text-transform: uppercase;
}

.history-table :deep(.el-table__body-wrapper td) {
  height: 58px;
}

.preview-cell {
  display: block;
  overflow: hidden;
  color: var(--ink-muted);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.model-cell {
  color: var(--ink-soft);
  font-family: var(--font-mono);
  font-size: 11px;
}

.pagination-row {
  display: flex;
  justify-content: flex-end;
  padding: 16px 18px 18px;
  border-top: 1px solid var(--line-subtle);
}

.detail-body {
  min-height: 180px;
}

.detail-block + .detail-block {
  margin-top: 18px;
}

.detail-block h3 {
  margin: 0 0 8px;
  color: var(--ink-strong);
  font-size: 13px;
}

.detail-block pre {
  margin: 0;
  padding: 14px;
  overflow: auto;
  max-height: 280px;
  border: 1px solid var(--line-subtle);
  border-radius: 11px;
  color: var(--ink-muted);
  font-family: var(--font-mono);
  font-size: 12px;
  line-height: 1.7;
  white-space: pre-wrap;
  background: var(--surface-code);
}

.section-item {
  padding: 10px 0;
  border-bottom: 1px solid var(--line-subtle);
}

.section-item p {
  margin: 8px 0 0;
  color: var(--ink-muted);
  font-size: 12px;
  line-height: 1.7;
  white-space: pre-wrap;
}
</style>
