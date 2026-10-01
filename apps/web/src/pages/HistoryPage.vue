<script setup lang="ts">
import { Delete, RefreshRight, Search, Upload, View } from '@element-plus/icons-vue';
import {
  ElButton,
  ElConfigProvider,
  ElDatePicker,
  ElDialog,
  ElEmpty,
  ElInput,
  ElMessage,
  ElMessageBox,
  ElPagination,
  ElTable,
  ElTableColumn,
  ElTag,
} from 'element-plus';
import zhCn from 'element-plus/es/locale/lang/zh-cn';
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';

import { getApiErrorMessage } from '@/services/http';
import {
  deleteHistory,
  getHistory,
  listHistory,
} from '@/services/promptOptimizerApi';
import { useOptimizationStore } from '@/stores/optimization';
import type {
  OptimizationHistoryDetail,
  OptimizationHistoryFilters,
  OptimizationHistorySummary,
  TemplateCode,
} from '@/types/api';

const router = useRouter();
const store = useOptimizationStore();

const HISTORY_PAGE_SIZES = [10, 20, 50] as const;

const loading = ref(false);
const items = ref<OptimizationHistorySummary[]>([]);
// 分页组件和列表接口都从 1 计页，对应 MyBatis-Plus 的 current。
const currentPage = ref(1);
const pageSize = ref<(typeof HISTORY_PAGE_SIZES)[number]>(10);
const total = ref(0);
const keyword = ref('');
const dateRange = ref<[string, string] | null>(null);

let loadSequence = 0;
// 搜索把页码拨回第一页时，分页组件可能再抛一次 current-change。忽略这一次回声，避免同一轮筛选打两次列表请求。
let ignorePaginationEcho = false;
let detachDatePointer: (() => void) | undefined;

const detailVisible = ref(false);
const detailLoading = ref(false);
const detail = ref<OptimizationHistoryDetail>();

const TEMPLATE_LABELS: Record<TemplateCode, string> = {
  AUTO: '自动识别',
  GENERAL: '通用任务',
  RESEARCH_ANALYSIS: '研究分析',
  FEATURE_DEVELOPMENT: '新功能开发',
  BUG_FIX: 'Bug 修复',
  REFACTORING: '代码重构',
  TESTING: '测试补充',
};

// 拉取当前页的历史摘要。历史记录按创建时间倒序，最新的排在最前。
const loadPage = async (): Promise<void> => {
  const sequence = ++loadSequence;
  loading.value = true;
  try {
    const filters: OptimizationHistoryFilters = {
      keyword: keyword.value.trim() || undefined,
      dateRange: dateRange.value,
    };
    const response = await listHistory(currentPage.value, pageSize.value, filters);
    // 筛选条件连续变化时，较早的请求可能晚于新请求返回，不能覆盖最新列表。
    if (sequence !== loadSequence) {
      return;
    }
    const returned = response.data.records ?? [];
    const limit = pageSize.value;
    const reportedTotal = Number(response.data.total);
    const safeTotal = Number.isFinite(reportedTotal) && reportedTotal > 0
      ? reportedTotal
      : returned.length;
    // 旧查询会忽略页大小，一次返回全部命中行。页面只渲染当前页，避免整表铺开。
    if (returned.length > limit) {
      const start = (currentPage.value - 1) * limit;
      items.value = returned.slice(start, start + limit);
      total.value = Math.max(safeTotal, returned.length);
    } else {
      items.value = returned;
      total.value = safeTotal;
    }
  } catch (error: unknown) {
    ElMessage.error(getApiErrorMessage(error));
  } finally {
    if (sequence === loadSequence) {
      loading.value = false;
    }
  }
};

const searchHistory = (): void => {
  ignorePaginationEcho = currentPage.value !== 1;
  currentPage.value = 1;
  void loadPage();
  void nextTick(() => {
    ignorePaginationEcho = false;
  });
};

const onCurrentChange = (nextPage: number): void => {
  if (ignorePaginationEcho) {
    ignorePaginationEcho = false;
    return;
  }
  if (nextPage === currentPage.value) {
    return;
  }
  currentPage.value = nextPage;
  void loadPage();
};

const onSizeChange = (nextSize: number): void => {
  const size = HISTORY_PAGE_SIZES.find((option) => option === nextSize);
  if (size === undefined || size === pageSize.value) {
    return;
  }
  ignorePaginationEcho = true;
  pageSize.value = size;
  currentPage.value = 1;
  void loadPage();
  void nextTick(() => {
    ignorePaginationEcho = false;
  });
};

const clearFilters = (): void => {
  keyword.value = '';
  dateRange.value = null;
};

const clearDatePointerPaint = (popper: ParentNode): void => {
  if (popper instanceof HTMLElement) {
    popper.classList.remove('is-overflow-hover');
  }
  popper.querySelectorAll('.el-date-table-cell__text').forEach((node) => {
    if (!(node instanceof HTMLElement)) {
      return;
    }
    node.style.removeProperty('background-color');
    node.style.removeProperty('color');
    node.style.removeProperty('transition');
  });
  popper.querySelectorAll('.history-date-pointer, .history-date-span, .has-pointer').forEach((node) => {
    node.classList.remove('history-date-pointer', 'history-date-span', 'has-pointer');
  });
};

const paintDatePointer = (popper: HTMLElement, cell: HTMLTableCellElement): void => {
  clearDatePointerPaint(popper);
  const panel = cell.closest('.el-date-range-picker__content');
  const overflow = cell.classList.contains('prev-month') || cell.classList.contains('next-month');
  cell.classList.add('history-date-pointer');
  panel?.classList.add('has-pointer');
  popper.classList.toggle('is-overflow-hover', overflow);
  const text = cell.querySelector('.el-date-table-cell__text');
  if (overflow && text instanceof HTMLElement) {
    text.style.setProperty('transition', 'none', 'important');
    text.style.setProperty('background-color', 'var(--accent)', 'important');
    text.style.setProperty('color', '#fff', 'important');
  }
  if (!overflow || !(panel instanceof HTMLElement)) {
    return;
  }
  const cells = [...panel.querySelectorAll('td')].filter(
    (node): node is HTMLTableCellElement => node instanceof HTMLTableCellElement,
  );
  const hoverIndex = cells.indexOf(cell);
  const startIndex = cells.findIndex((node) => node.classList.contains('start-date'));
  if (hoverIndex < 0 || startIndex < 0) {
    return;
  }
  const from = Math.min(startIndex, hoverIndex);
  const to = Math.max(startIndex, hoverIndex);
  for (let index = from; index <= to; index += 1) {
    if (index !== hoverIndex) {
      cells[index]?.classList.add('history-date-span');
    }
  }
};

// Element Plus 只给当月格子加 end-date。九月表里的上月、下月日期和另一侧月历是同一天，圆点会被画到另一边。
// 等组件先改完类名，再把圆点钉回指针下的格子，并收起另一侧重复的范围色。
const onHistoryCalendarChange = (dates: Array<Date | string | null> | null): void => {
  const popper = document.querySelector('.history-date-popper');
  if (!(popper instanceof HTMLElement)) {
    return;
  }
  const selectingEnd = Boolean(dates?.[0]) && dates?.[1] == null;
  popper.classList.toggle('is-selecting-end', selectingEnd);
  if (!selectingEnd) {
    clearDatePointerPaint(popper);
  }
};

const onDatePanelVisible = (visible: boolean): void => {
  detachDatePointer?.();
  detachDatePointer = undefined;
  if (!visible) {
    const popper = document.querySelector('.history-date-popper');
    if (popper instanceof HTMLElement) {
      popper.classList.remove('is-selecting-end');
      clearDatePointerPaint(popper);
    }
    return;
  }
  let paintFrame = 0;
  const onMove = (event: MouseEvent): void => {
    const popper = document.querySelector('.history-date-popper');
    if (!(popper instanceof HTMLElement) || !popper.classList.contains('is-selecting-end')) {
      return;
    }
    const fromTarget = event.target instanceof Element ? event.target.closest('td') : null;
    const cell = fromTarget instanceof HTMLTableCellElement && popper.contains(fromTarget)
      ? fromTarget
      : document.elementFromPoint(event.clientX, event.clientY)?.closest('td');
    if (!(cell instanceof HTMLTableCellElement) || !popper.contains(cell)) {
      return;
    }
    window.cancelAnimationFrame(paintFrame);
    paintFrame = window.requestAnimationFrame(() => {
      paintDatePointer(popper, cell);
    });
  };
  document.addEventListener('mousemove', onMove, true);
  detachDatePointer = () => {
    window.cancelAnimationFrame(paintFrame);
    document.removeEventListener('mousemove', onMove, true);
  };
};

onBeforeUnmount(() => {
  detachDatePointer?.();
});

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
    await router.push('/workbench');
  } catch (error: unknown) {
    ElMessage.error(getApiErrorMessage(error));
  }
};

const reoptimize = async (id: string): Promise<void> => {
  try {
    const response = await getHistory(id);
    store.loadFromHistory(response.data);
    await router.push({ path: '/workbench', query: { enhance: '1' } });
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
      <div class="history-filter-bar" aria-label="历史记录筛选">
        <ElInput
          v-model="keyword"
          class="history-filter-bar__keyword"
          clearable
          placeholder="搜索原始提示词"
          aria-label="原始提示词搜索"
          @keyup.enter="searchHistory"
        />
        <div class="history-filter-bar__date-control" @keyup.enter.capture="searchHistory">
          <ElConfigProvider :locale="zhCn">
            <ElDatePicker
              v-model="dateRange"
              class="history-filter-bar__date"
              popper-class="history-date-popper"
              type="daterange"
              format="YYYY-MM-DD"
              value-format="YYYY-MM-DD"
              range-separator="至"
              start-placeholder="开始日期"
              end-placeholder="结束日期"
              aria-label="创建时间范围"
              @calendar-change="onHistoryCalendarChange"
              @visible-change="onDatePanelVisible"
            />
          </ElConfigProvider>
        </div>
        <ElButton
          class="history-filter-bar__search"
          type="primary"
          :icon="Search"
          :loading="loading"
          @click="searchHistory"
        >
          搜索
        </ElButton>
        <ElButton
          v-if="keyword || dateRange"
          class="history-filter-bar__clear"
          text
          @click="clearFilters"
        >
          清除筛选
        </ElButton>
      </div>

      <div v-loading="loading" class="history-results">
      <p v-if="!loading && items.length === 0" class="history-card-list history-card-list--empty">
        还没有优化历史
      </p>
      <ul v-else-if="items.length" class="history-card-list">
        <li v-for="row in items" :key="row.id" class="history-mobile-card">
          <div class="history-mobile-card__meta">
            <time>{{ formatDate(row.createdAt) }}</time>
            <ElTag size="small" effect="plain">{{ templateLabel(row.templateCode) }}</ElTag>
          </div>
          <p class="history-mobile-card__preview">{{ row.rawPromptPreview }}</p>
          <span class="history-mobile-card__model">{{ row.modelVersion || '版本未记录' }}</span>
          <div class="history-mobile-card__actions">
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
          </div>
        </li>
      </ul>

      <ElTable :data="items" row-key="id" class="history-table">
        <ElTableColumn label="创建时间" width="150">
          <template #default="{ row }">{{ formatDate(row.createdAt) }}</template>
        </ElTableColumn>
        <ElTableColumn label="模板" width="110">
          <template #default="{ row }">
            <ElTag size="small" effect="plain">{{ templateLabel(row.templateCode) }}</ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="模型版本" min-width="200">
          <template #default="{ row }">
            <span class="model-cell" :title="row.modelVersion || '版本未记录'">{{ row.modelVersion || '版本未记录' }}</span>
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
      </div>

      <div v-if="total > 0" class="pagination-row">
        <ElPagination
          background
          layout="prev, pager, next, sizes"
          :current-page="currentPage"
          :page-size="pageSize"
          :page-sizes="[...HISTORY_PAGE_SIZES]"
          :total="total"
          @current-change="onCurrentChange"
          @size-change="onSizeChange"
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
  font-size: 12px;
  letter-spacing: 0.8px;
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
  font-size: 14px;
}

.history-card {
  padding: 0;
  overflow: hidden;
  border: 1px solid var(--line-subtle);
  border-radius: var(--radius-large);
  background: var(--surface-panel);
  box-shadow: var(--shadow-panel);
}

.history-results {
  position: relative;
  min-height: 280px;
  background: transparent;
}

.history-results :deep(.el-loading-mask) {
  background-color: color-mix(in srgb, var(--bg-base) 46%, transparent) !important;
  backdrop-filter: blur(8px);
}

.history-results :deep(.el-loading-spinner .path) {
  stroke: var(--accent);
}

.history-filter-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px 18px;
  border-bottom: 1px solid var(--line-subtle);
}

.history-filter-bar__keyword {
  width: min(360px, 100%);
}

.history-filter-bar__date {
  width: min(320px, 100%);
}

.history-filter-bar__date-control {
  flex: 0 1 auto;
}

.history-filter-bar__search {
  flex: 0 0 auto;
}

.history-filter-bar__clear {
  flex: 0 0 auto;
}

.history-table {
  width: 100%;
  background: transparent;
}

.history-table :deep(.el-table__inner-wrapper),
.history-table :deep(.el-table__body-wrapper),
.history-table :deep(.el-table__empty-block) {
  background: transparent;
}

.history-table :deep(.el-table__header-wrapper th) {
  height: 46px;
  color: var(--ink-soft);
  font-family: var(--font-mono);
  font-size: 12px;
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
  font-size: 12px;
}

.pagination-row {
  display: flex;
  justify-content: flex-end;
  padding: 16px 18px 18px;
  border-top: 1px solid var(--line-subtle);
}

.history-card-list {
  display: none;
  margin: 0;
  padding: 0;
  list-style: none;
}

.history-card-list--empty {
  padding: 48px 16px;
  color: var(--ink-muted);
  font-size: 14px;
  text-align: center;
}

.history-mobile-card {
  display: grid;
  gap: 8px;
  padding: 16px;
  border-bottom: 1px solid var(--line-subtle);
}

.history-mobile-card__meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  color: var(--ink-soft);
  font-size: 13px;
}

.history-mobile-card__preview {
  margin: 0;
  color: var(--ink-strong);
  font-size: 14px;
  line-height: 1.6;
}

.history-mobile-card__model {
  color: var(--ink-soft);
  font-family: var(--font-mono);
  font-size: 12px;
}

.history-mobile-card__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin: 4px -8px 0;
}

@media (max-width: 720px) {
  .history-filter-bar {
    align-items: stretch;
    flex-direction: column;
  }

  .history-filter-bar__keyword,
  .history-filter-bar__date,
  .history-filter-bar__date-control {
    width: 100%;
  }

  .history-filter-bar__clear {
    align-self: flex-start;
  }

  .history-filter-bar__search {
    align-self: flex-start;
  }

  .history-results {
    min-height: 220px;
  }

  .history-table {
    display: none;
  }

  .history-card-list {
    display: grid;
  }

  .pagination-row {
    justify-content: center;
  }

  .pagination-row :deep(.el-pagination) {
    flex-wrap: wrap;
    justify-content: center;
  }
}

.detail-body {
  position: relative;
  min-height: 180px;
}

.detail-body :deep(.el-loading-mask) {
  background-color: color-mix(in srgb, var(--bg-base) 46%, transparent) !important;
  backdrop-filter: blur(8px);
}

.detail-block + .detail-block {
  margin-top: 18px;
}

.detail-block h3 {
  margin: 0 0 8px;
  color: var(--ink-strong);
  font-size: 14px;
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
  font-size: 13px;
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
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-wrap;
}
</style>
