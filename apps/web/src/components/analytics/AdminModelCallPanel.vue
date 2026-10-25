<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { ElAlert, ElButton, ElDatePicker, ElEmpty, ElInput, ElInputNumber, ElMessage, ElOption, ElPagination, ElSelect, ElTable, ElTableColumn, ElTag } from 'element-plus';
import { BarChart } from 'echarts/charts';
import { GridComponent, TooltipComponent } from 'echarts/components';
import { init, use, type EChartsType } from 'echarts/core';
import { CanvasRenderer } from 'echarts/renderers';
import { useUiTheme } from '@/composables/useUiTheme';
import { formatAnalyticsTime } from '@/features/analytics/analyticsPresentation';
import { getModelCalls, getModelTokenRanking } from '@/services/adminAnalyticsApi';
import { getApiErrorMessage } from '@/services/http';
import type { AnalyticsRankingPeriod, ModelCallPage, ModelCallRecord, ModelCallScope, TokenRank, TokenRanking, TokenRankingQuery } from '@/types/api';

use([CanvasRenderer, BarChart, GridComponent, TooltipComponent]);
const { activeThemeId } = useUiTheme();
const period = ref<AnalyticsRankingPeriod>('DAY');
const date = ref(new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai' }).format(new Date()));
const scope = ref<ModelCallScope>('GLOBAL');
const tenantId = ref('');
const groupBy = ref<'OPERATION' | 'SOURCE'>('OPERATION');
const limit = ref(20);
const loading = ref(false);
const callsLoading = ref(false);
const error = ref('');
const callsError = ref('');
const ranking = ref<TokenRanking>();
const calls = ref<ModelCallPage>();
const applied = ref<TokenRankingQuery>();
const chartElement = ref<HTMLDivElement>();
let chart: EChartsType | undefined;
let resize: ResizeObserver | undefined;
let callsGeneration = 0;
let disposed = false;
const operationNames: Record<string, string> = { 'prompt.optimize': '提示词优化', 'plan.generate': 'Plan 问题生成', 'document.summary.map': '文档分段摘要', 'document.summary.reduce': '文档合并摘要', 'context.embedding': '上下文向量化', 'context.vision': '上下文视觉识别', 'text.polish': '文章祛AI味', unknown: '未知操作' };
const sourceName = (value: string): string => ranking.value?.groupBy === 'OPERATION' ? (operationNames[value] ?? value) : value;
const usageNames = { COMPLETE: '完整', PARTIAL: '部分', UNKNOWN: '未知', INVALID: '无效' };
const cacheNames = { HIT: '命中', MISS: '未命中', MIXED: '部分命中', NONE: '零输入', UNKNOWN: '未知' };
const transportNames = { MOCK: '模拟调用', NOT_SENT: '未发送', STARTED: '已开始传输', RESPONSE_RECEIVED: '已收到响应', UNKNOWN: '传输未知' };
const transportLabel = (row: ModelCallRecord): string => transportNames[row.transportState];
const usageLabel = (row: ModelCallRecord): string => usageNames[row.usageState];
const cacheLabel = (row: ModelCallRecord): string => cacheNames[row.cacheState];
const hasLargeTokens = computed(() => ranking.value?.ranking.some(row => BigInt(row.totalTokens) > BigInt(Number.MAX_SAFE_INTEGER)) ?? false);

/** 表格保留精确十进制数，图表仅表达相对规模，不用浮点值回写计量。 */
const tokens = (value: string | null): string => value === null ? '未知' : BigInt(value).toLocaleString('zh-CN');

/** 零分母保持未知；修复成功率只计算实际进入修复且已结束的请求。 */
const rate = (count: number, total: number): string => total > 0 ? `${(count / total * 100).toFixed(1)}%` : '—';

/** 独立筛选模型数据，不复用主仪表盘的用户、邮箱与名称条件。 */
const query = (): TokenRankingQuery | undefined => {
  if (!date.value || !/^\d{4}-\d{2}-\d{2}$/.test(date.value)) { ElMessage.warning('请选择模型对账日期。'); return; }
  const tenant = tenantId.value.trim();
  if (scope.value === 'TENANT' && !/^[a-f\d]{8}-[a-f\d]{4}-[a-f\d]{4}-[a-f\d]{4}-[a-f\d]{12}$/i.test(tenant)) {
    ElMessage.warning('租户范围需要有效的租户 ID。'); return;
  }
  return { period: period.value, date: date.value, scope: scope.value, ...(scope.value === 'TENANT' ? { tenantId: tenant } : {}), groupBy: groupBy.value, limit: limit.value };
};

/** 使用成功查询时的生效范围翻页，编辑草稿不会改变当前结果的归属。 */
const loadCalls = async (page = 1): Promise<void> => {
  const selected = applied.value;
  const result = ranking.value;
  if (!selected || !result) return;
  const generation = ++callsGeneration;
  callsLoading.value = true;
  callsError.value = '';
  try {
    const value = await getModelCalls({ fromDate: result.period.fromDate, toDate: result.period.toDateInclusive,
      scope: selected.scope, tenantId: selected.tenantId, current: page, size: 10 });
    if (generation === callsGeneration && !disposed) calls.value = value;
  } catch (failure: unknown) {
    if (generation === callsGeneration && !disposed) callsError.value = getApiErrorMessage(failure);
  } finally { if (generation === callsGeneration && !disposed) callsLoading.value = false; }
};

/** 查询错误保持可见；新排行成功后清空旧明细，避免异步响应串入不同租户。 */
const refresh = async (): Promise<void> => {
  if (loading.value) return;
  const selected = query();
  if (!selected) return;
  loading.value = true;
  error.value = '';
  try {
    const value = await getModelTokenRanking(selected);
    if (disposed) return;
    ranking.value = value;
    applied.value = selected;
    callsGeneration++;
    calls.value = undefined;
    await nextTick();
    renderChart();
    await loadCalls();
  } catch (failure: unknown) {
    if (!disposed) {
      error.value = getApiErrorMessage(failure);
      chart?.dispose(); chart = undefined;
      resize?.disconnect(); resize = undefined;
    }
  } finally { if (!disposed) loading.value = false; }
};

/** 恢复默认草稿并查询，不触碰账号统计的筛选状态。 */
const reset = async (): Promise<void> => {
  period.value = 'DAY';
  date.value = new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai' }).format(new Date());
  scope.value = 'GLOBAL'; tenantId.value = ''; groupBy.value = 'OPERATION'; limit.value = 20;
  await refresh();
};

/** 沿用产品主题变量；未知用量仍显示覆盖计数，不能按零消费宣称完成对账。 */
const renderChart = (): void => {
  if (!chartElement.value || !ranking.value?.ranking.length) {
    chart?.dispose(); chart = undefined; resize?.disconnect(); resize = undefined; return;
  }
  const color = (name: string): string => getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  chart ??= init(chartElement.value);
  if (!resize) { resize = new ResizeObserver(() => chart?.resize()); resize.observe(chartElement.value); }
  const rows = [...ranking.value.ranking].slice(0, 10).reverse();
  chart.setOption({ animation: !window.matchMedia('(prefers-reduced-motion: reduce)').matches,
    color: [color('--accent')], tooltip: { trigger: 'axis', renderMode: 'richText', confine: true },
    grid: { left: 8, right: 20, top: 12, bottom: 8, containLabel: true },
    xAxis: { type: 'value', axisLabel: { color: color('--text-muted') }, splitLine: { lineStyle: { opacity: 0.12 } } },
    yAxis: { type: 'category', data: rows.map(row => sourceName(row.source)), axisLabel: { color: color('--text-secondary'), width: 150, overflow: 'truncate' } },
    series: [{ name: '已报告总 Token', type: 'bar', barMaxWidth: 24, data: rows.map(row => Number(row.totalTokens)) }],
  }, true);
};
watch(activeThemeId, () => { void nextTick(renderChart); });
onBeforeUnmount(() => { disposed = true; callsGeneration++; resize?.disconnect(); chart?.dispose(); });
</script>

<template>
  <article class="model-call-panel" aria-labelledby="model-call-title">
    <header><div><h2 id="model-call-title">模型调用对账与 Token 排行</h2><p>独立租户级计量 · 每次尝试记录 · 包含失败与修复重试的已报告用量</p></div></header>
    <ElAlert type="info" :closable="false" title="自功能启用后采集。Token 排行不关联账号；页面上方的账号筛选不作用于此处。缺失用量保持未知，不能据此反推完整供应商账单。" />
    <div class="model-call-filters">
      <label>统计周期<ElSelect v-model="period" aria-label="模型统计周期"><ElOption label="按日" value="DAY" /><ElOption label="按周" value="WEEK" /><ElOption label="按月" value="MONTH" /></ElSelect></label>
      <label>周期内日期<ElDatePicker v-model="date" type="date" value-format="YYYY-MM-DD" format="YYYY年MM月DD日" aria-label="模型对账日期" placeholder="选择日期" /></label>
      <label>数据范围<ElSelect v-model="scope" aria-label="模型数据范围"><ElOption label="全平台" value="GLOBAL" /><ElOption label="指定租户" value="TENANT" /></ElSelect></label>
      <label v-if="scope === 'TENANT'" class="tenant-filter">租户 ID<ElInput v-model="tenantId" maxlength="36" aria-label="模型租户 ID" placeholder="输入租户 UUID" /></label>
      <label>排行维度<ElSelect v-model="groupBy" aria-label="Token 排行维度"><ElOption label="操作类型" value="OPERATION" /><ElOption label="模型来源" value="SOURCE" /></ElSelect></label>
      <label>排行条数<ElInputNumber v-model="limit" :min="1" :max="100" :precision="0" :value-on-clear="20" aria-label="Token 排行条数" /></label>
      <div class="model-call-actions"><ElButton type="primary" :loading="loading" @click="refresh">查询对账</ElButton><ElButton :disabled="loading" @click="reset">重置</ElButton></div>
    </div>
    <ElAlert v-if="error" type="error" :closable="false" :title="error" />
    <template v-if="ranking && !error">
      <p class="period-caption">{{ ranking.period.fromDate }} — {{ ranking.period.toDateInclusive }} · {{ ranking.period.zoneId }} · {{ ranking.scope === 'GLOBAL' ? '全平台' : '指定租户' }}</p>
      <p v-if="hasLargeTokens">大整数在表格中精确显示，图表仅用于比较相对规模。</p>
      <div v-if="ranking.ranking.length" ref="chartElement" class="token-chart" role="img" aria-label="已报告 Token 消耗前十名柱状图"></div>
      <!-- @vue-generic {TokenRank} -->
      <ElTable :data="ranking.ranking" row-key="source" stripe empty-text="所选范围内没有可排行的模型调用。">
        <ElTableColumn label="来源／操作" min-width="190"><template #default="s">{{ sourceName(s.row.source) }}</template></ElTableColumn>
        <ElTableColumn prop="callCount" label="调用尝试" width="105" align="right" />
        <ElTableColumn prop="failureCount" label="失败尝试" width="105" align="right" />
        <ElTableColumn label="输入 Token" min-width="145" align="right"><template #default="s">{{ tokens(s.row.inputTokens) }}</template></ElTableColumn>
        <ElTableColumn label="输出 Token" min-width="145" align="right"><template #default="s">{{ tokens(s.row.outputTokens) }}</template></ElTableColumn>
        <ElTableColumn label="已报告总 Token" min-width="175" align="right"><template #default="s">{{ tokens(s.row.totalTokens) }}</template></ElTableColumn>
        <ElTableColumn label="缓存命中／未命中 Token" min-width="220"><template #default="s">{{ tokens(s.row.cacheHitTokens) }} / {{ tokens(s.row.cacheMissTokens) }}</template></ElTableColumn>
        <ElTableColumn label="缓存用量覆盖（已知／未知）" min-width="220"><template #default="s">{{ s.row.cacheKnownUsageCount }} / {{ s.row.cacheUnknownUsageCount }}</template></ElTableColumn>
        <ElTableColumn label="用量覆盖（完整／部分／未知／无效）" min-width="285"><template #default="s">{{ s.row.completeUsageCount }} / {{ s.row.partialUsageCount }} / {{ s.row.unknownUsageCount }} / {{ s.row.invalidUsageCount }}</template></ElTableColumn>
      </ElTable>
      <p class="period-caption">排行排除 Mock 与明确未发出的请求；以下明细保留全部尝试。失败响应的有效 Token 不回滚。缓存未报告时保留未知状态。</p>
      <h3>模型调用尝试明细</h3>
      <template v-if="calls?.outcomes && !callsError">
        <p>增强／Plan 已结束请求 {{ calls.outcomes.completedRequestCount }} 次 ·
          首次成功 {{ calls.outcomes.firstSuccessCount }} 次（{{ rate(calls.outcomes.firstSuccessCount, calls.outcomes.completedRequestCount) }}） ·
          修复后成功 {{ calls.outcomes.repairedSuccessCount }} 次（修复成功率 {{ rate(calls.outcomes.repairedSuccessCount, calls.outcomes.repairedRequestCount) }}） ·
          最终失败 {{ calls.outcomes.finalFailureCount }} 次（{{ rate(calls.outcomes.finalFailureCount, calls.outcomes.completedRequestCount) }}）</p>
        <p>首次成功率和最终失败率以已结束请求为分母；修复成功率以实际重试且已结束的 {{ calls.outcomes.repairedRequestCount }} 次请求为分母。
          另有 {{ calls.outcomes.incompleteRequestCount }} 次进行中或记录不完整的请求、{{ calls.outcomes.uncorrelatedAttemptCount }} 条缺关联号尝试，均不计入成功率。
          按租户、请求关联号和操作归并；Plan 提问与最终增强分别计数，不表示作品质量已通过。</p>
      </template>
      <ElAlert v-if="callsError" type="error" :closable="false" :title="callsError" />
      <ElButton v-if="callsError" :loading="callsLoading" @click="loadCalls(calls?.current ?? 1)">重试明细查询</ElButton>
      <!-- @vue-generic {ModelCallRecord} -->
      <ElTable v-else v-loading="callsLoading" :data="calls?.records ?? []" row-key="eventId" stripe empty-text="所选范围内没有模型对账记录。">
        <ElTableColumn label="调用开始时间" min-width="180"><template #default="s">{{ formatAnalyticsTime(s.row.occurredAt, ranking.period.zoneId) }}</template></ElTableColumn>
        <ElTableColumn label="操作" min-width="150"><template #default="s">{{ operationNames[s.row.operation] ?? s.row.operation }}</template></ElTableColumn>
        <ElTableColumn prop="model" label="公开模型" min-width="150" show-overflow-tooltip />
        <ElTableColumn prop="providerRoute" label="模型路由" min-width="150" show-overflow-tooltip />
        <ElTableColumn prop="attempt" label="尝试" width="70" />
        <ElTableColumn label="响应" min-width="160"><template #default="s"><ElTag :type="s.row.successful ? 'success' : 'danger'">{{ s.row.successful ? '成功' : s.row.failureType ?? '失败' }}</ElTag></template></ElTableColumn>
        <ElTableColumn label="校验原因" min-width="230" show-overflow-tooltip><template #default="s">{{ s.row.validationReason ?? '未记录' }}</template></ElTableColumn>
        <ElTableColumn label="校验位置" min-width="170" show-overflow-tooltip><template #default="s">{{ s.row.validationField ?? '未记录' }}</template></ElTableColumn>
        <ElTableColumn prop="upstreamStatus" label="上游状态" width="100" />
        <ElTableColumn label="传输" min-width="145"><template #default="s">{{ transportLabel(s.row as ModelCallRecord) }}</template></ElTableColumn>
        <ElTableColumn label="用量状态" width="100"><template #default="s">{{ usageLabel(s.row as ModelCallRecord) }}</template></ElTableColumn>
        <ElTableColumn label="输入／输出／总 Token" min-width="220"><template #default="s">{{ tokens(s.row.inputTokens) }} / {{ tokens(s.row.outputTokens) }} / {{ tokens(s.row.totalTokens) }}</template></ElTableColumn>
        <ElTableColumn label="缓存状态" width="100"><template #default="s">{{ cacheLabel(s.row as ModelCallRecord) }}</template></ElTableColumn>
        <ElTableColumn label="缓存命中／未命中 Token" min-width="220"><template #default="s">{{ tokens(s.row.cacheHitTokens) }} / {{ tokens(s.row.cacheMissTokens) }}</template></ElTableColumn>
        <ElTableColumn label="后续修复重试" width="120"><template #default="s">{{ s.row.willRetry ? '是' : '否' }}</template></ElTableColumn>
        <ElTableColumn prop="durationMs" label="耗时（ms）" width="110" />
        <ElTableColumn prop="requestId" label="请求关联号" min-width="280" show-overflow-tooltip />
        <ElTableColumn prop="workflowId" label="流程指纹" min-width="220" show-overflow-tooltip />
        <ElTableColumn prop="tenantId" label="租户 ID" min-width="280" show-overflow-tooltip />
      </ElTable>
      <ElPagination v-if="calls && calls.total > 0" background layout="total, prev, pager, next" :total="calls.total" :page-size="calls.size" :current-page="calls.current" :pager-count="5" @current-change="loadCalls" />
    </template>
    <ElEmpty v-else-if="!error" description="选择独立的模型统计条件后，点击“查询对账”。" :image-size="70" />
  </article>
</template>

<style scoped>
.model-call-panel { min-width: 0; margin-top: var(--analytics-gap, 20px); padding: 22px; border: 1px solid var(--analytics-line, var(--glass-border)); border-radius: 14px; background: var(--analytics-surface, var(--glass-fallback)); color: var(--text-primary); }
header { display: flex; gap: 16px; align-items: center; margin-bottom: 14px; }
h2 { margin: 0; font-size: 18px; } h3 { margin: 22px 0 12px; font-size: 16px; }
p { font-size: 12px; line-height: 1.7; color: var(--text-secondary); }
.model-call-filters { display: flex; flex-wrap: wrap; gap: 14px; align-items: end; margin: 20px 0; }
label { display: flex; flex-direction: column; gap: 7px; font-size: 12px; color: var(--text-secondary); width: 140px; }
label:has(.el-date-editor) { width: 190px; }
.tenant-filter { width: min(100%, 320px); }
.model-call-filters :deep(.el-date-editor) { width: 100%; }
.model-call-filters :deep(.el-input-number) { width: 100%; }
.model-call-actions { display: flex; gap: 10px; } .model-call-actions :deep(.el-button + .el-button) { margin: 0; }
.token-chart { height: 320px; width: 100%; min-width: 0; }
.el-pagination { margin-top: 18px; overflow-x: auto; }
@media (max-width: 640px) { .model-call-panel { padding: 16px 12px; } label, label:has(.el-date-editor) { width: calc(50% - 7px); } .tenant-filter { width: 100%; } .model-call-actions { width: 100%; } .model-call-actions .el-button { flex: 1; } h2 { font-size: 16px; } }
</style>
