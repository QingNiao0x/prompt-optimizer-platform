<script setup lang="ts">
import {
  BarChart,
  LineChart,
  PieChart,
} from 'echarts/charts';
import {
  DataZoomComponent,
  GridComponent,
  LegendComponent,
  TooltipComponent,
} from 'echarts/components';
import {
  type EChartsType,
  init,
  use,
} from 'echarts/core';
import { CanvasRenderer } from 'echarts/renderers';
import type { EChartsOption } from 'echarts/types/dist/option';
import {
  ElAlert,
  ElButton,
  ElDatePicker,
  ElEmpty,
  ElInput,
  ElMessage,
  ElOption,
  ElPagination,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElTag,
} from 'element-plus';
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue';

import { getApiErrorMessage } from '@/services/http';
import { getAnalyticsDashboard, getAnalyticsOperations, getAnalyticsRanking } from '@/services/adminAnalyticsApi';
import type {
  AnalyticsDashboard,
  AnalyticsDashboardQuery,
  AnalyticsEventType,
  AnalyticsOperationLog,
  AnalyticsOperationLogPage,
  AnalyticsRanking,
  AnalyticsRankingPeriod,
  AnalyticsRange,
} from '@/types/api';

use([CanvasRenderer, LineChart, BarChart, PieChart, DataZoomComponent,
  GridComponent, LegendComponent, TooltipComponent]);

const range = ref<AnalyticsRange>('TODAY');
const dateRange = ref<string[]>([]);
const accountId = ref('');
const eventFilter = ref<AnalyticsEventType | ''>('');
const dashboard = ref<AnalyticsDashboard>();
const operationPage = ref<AnalyticsOperationLogPage>();
const ranking = ref<AnalyticsRanking>();
const loading = ref(false);
const operationsLoading = ref(false);
const rankingLoading = ref(false);
const operationsPageNumber = ref(1);
const rankingPeriod = ref<AnalyticsRankingPeriod>('DAY');
const rankingDate = ref('');
const pageSize = 20;

const dailyChart = ref<HTMLDivElement | null>(null);
const hourlyChart = ref<HTMLDivElement | null>(null);
const monthlyChart = ref<HTMLDivElement | null>(null);
const deviceChart = ref<HTMLDivElement | null>(null);
const rechargeChart = ref<HTMLDivElement | null>(null);
const charts = new Map<string, EChartsType>();

const eventTypes: Array<{ value: AnalyticsEventType; label: string }> = [
  { value: 'LOGIN', label: '登录' },
  { value: 'LOGOUT', label: '退出' },
  { value: 'APP_VISIT', label: '页面访问' },
  { value: 'OPTIMIZATION_SUBMITTED', label: '提交优化' },
  { value: 'PLAN_CREATED', label: '生成方案' },
  { value: 'CONTEXT_PREPARED', label: '准备上下文' },
  { value: 'CONTEXT_ANALYZED', label: '分析上下文' },
  { value: 'RESULT_EXPORTED', label: '复制结果' },
  { value: 'RECHARGE_PAID', label: '充值成功' },
];

const activeMonth = computed(() => {
  const months = dashboard.value?.monthlyUsage ?? [];
  return months.reduce((peak, current) => (
    current.operationCount > (peak?.operationCount ?? -1) ? current : peak
  ), months[0]);
});

const activeHour = computed(() => {
  const hours = dashboard.value?.hourlyUsage ?? [];
  return hours.reduce((peak, current) => (
    current.operationCount > (peak?.operationCount ?? -1) ? current : peak
  ), hours[0]);
});

const deviceLabel = (deviceType: string): string => ({
  MOBILE: '手机',
  TABLET: '平板',
  DESKTOP: '电脑',
  UNKNOWN: '未知',
}[deviceType] ?? deviceType);

const eventLabel = (eventType: string): string => (
  eventTypes.find((item) => item.value === eventType)?.label ?? eventType
);

const placeLabel = (country: string | null, province: string | null, city: string | null): string => {
  const parts = [country, province, city].filter((part): part is string => Boolean(part));
  return parts.length > 0 ? parts.join(' · ') : '未能解析';
};

const makeQuery = (): AnalyticsDashboardQuery | undefined => {
  const normalizedAccountId = accountId.value.trim();
  if (range.value === 'CUSTOM') {
    if (dateRange.value.length !== 2 || !dateRange.value[0] || !dateRange.value[1]) {
      ElMessage.warning('请选择完整的自定义开始和结束日期。');
      return undefined;
    }
    return {
      range: range.value,
      fromDate: dateRange.value[0],
      toDate: dateRange.value[1],
      ...(normalizedAccountId ? { userId: normalizedAccountId } : {}),
    };
  }
  return {
    range: range.value,
    ...(normalizedAccountId ? { userId: normalizedAccountId } : {}),
  };
};

const loadOperations = async (page: number): Promise<void> => {
  const current = dashboard.value;
  if (!current) return;
  operationsLoading.value = true;
  try {
    const normalizedAccountId = accountId.value.trim();
    operationPage.value = await getAnalyticsOperations({
      fromDate: current.period.fromDate,
      toDate: current.period.toDateInclusive,
      page,
      pageSize,
      ...(normalizedAccountId ? { userId: normalizedAccountId } : {}),
      ...(eventFilter.value ? { eventType: eventFilter.value } : {}),
    });
    operationsPageNumber.value = page;
  } catch (error: unknown) {
    ElMessage.error(getApiErrorMessage(error));
  } finally {
    operationsLoading.value = false;
  }
};

const loadRanking = async (fallbackDate?: string): Promise<void> => {
  const selectedDate = rankingDate.value || fallbackDate;
  if (!selectedDate) return;
  rankingLoading.value = true;
  try {
    const normalizedAccountId = accountId.value.trim();
    ranking.value = await getAnalyticsRanking(
      rankingPeriod.value,
      selectedDate,
      20,
      normalizedAccountId || undefined,
    );
    rankingDate.value = selectedDate;
  } catch (error: unknown) {
    ElMessage.error(getApiErrorMessage(error));
  } finally {
    rankingLoading.value = false;
  }
};

const refresh = async (): Promise<void> => {
  const query = makeQuery();
  if (!query) return;
  loading.value = true;
  try {
    dashboard.value = await getAnalyticsDashboard(query);
    await nextTick();
    renderCharts();
    await Promise.all([
      loadOperations(1),
      loadRanking(dashboard.value.period.toDateInclusive),
    ]);
  } catch (error: unknown) {
    ElMessage.error(getApiErrorMessage(error));
  } finally {
    loading.value = false;
  }
};

const options = (): EChartsOption => ({
  animationDuration: 320,
  textStyle: { fontFamily: 'JetBrains Mono, Space Grotesk, sans-serif' },
  tooltip: { trigger: 'axis', confine: true },
  grid: { left: 44, right: 20, top: 30, bottom: 30 },
});

const dailyOption = (data: AnalyticsDashboard): EChartsOption => ({
  ...options(),
  color: ['#4d6bfe', '#13a9a1', '#d99128'],
  legend: { top: 0, right: 0, textStyle: { color: '#66779c' } },
  xAxis: {
    type: 'category',
    boundaryGap: false,
    data: data.dailyMetrics.map((item) => item.date.slice(5)),
    axisLabel: { color: '#66779c' },
    axisLine: { lineStyle: { color: 'rgba(102,119,156,.24)' } },
  },
  yAxis: { type: 'value', minInterval: 1, axisLabel: { color: '#66779c' }, splitLine: { lineStyle: { color: 'rgba(102,119,156,.12)' } } },
  series: [
    { name: '页面访问', type: 'line', smooth: true, showSymbol: false, data: data.dailyMetrics.map((item) => item.accessCount) },
    { name: '去重访问账号', type: 'line', smooth: true, showSymbol: false, data: data.dailyMetrics.map((item) => item.uniqueVisitors) },
    { name: '活跃账号', type: 'line', smooth: true, showSymbol: false, data: data.dailyMetrics.map((item) => item.activeUsers) },
  ],
});

const hourlyOption = (data: AnalyticsDashboard): EChartsOption => ({
  ...options(),
  color: ['#13a9a1'],
  xAxis: { type: 'category', data: data.hourlyUsage.map((item) => `${String(item.hour).padStart(2, '0')}:00`), axisLabel: { color: '#66779c', interval: 2 }, axisLine: { lineStyle: { color: 'rgba(102,119,156,.24)' } } },
  yAxis: { type: 'value', minInterval: 1, axisLabel: { color: '#66779c' }, splitLine: { lineStyle: { color: 'rgba(102,119,156,.12)' } } },
  series: [{ name: '关键操作', type: 'bar', barMaxWidth: 22, data: data.hourlyUsage.map((item) => item.operationCount), itemStyle: { borderRadius: [4, 4, 0, 0] } }],
});

const monthlyOption = (data: AnalyticsDashboard): EChartsOption => ({
  ...options(),
  color: ['#4d6bfe'],
  xAxis: { type: 'category', data: data.monthlyUsage.map((item) => item.month), axisLabel: { color: '#66779c' }, axisLine: { lineStyle: { color: 'rgba(102,119,156,.24)' } } },
  yAxis: { type: 'value', minInterval: 1, axisLabel: { color: '#66779c' }, splitLine: { lineStyle: { color: 'rgba(102,119,156,.12)' } } },
  series: [{ name: '关键操作', type: 'bar', barMaxWidth: 30, data: data.monthlyUsage.map((item) => item.operationCount), itemStyle: { borderRadius: [4, 4, 0, 0] } }],
});

const deviceOption = (data: AnalyticsDashboard): EChartsOption => ({
  ...options(),
  color: ['#4d6bfe', '#13a9a1', '#e6a23c', '#a0a7b8'],
  tooltip: { trigger: 'item', confine: true, formatter: '{b}: {c} 次登录（{d}%）' },
  legend: { bottom: 0, textStyle: { color: '#66779c' } },
  series: [{
    name: '登录设备',
    type: 'pie',
    radius: ['54%', '76%'],
    center: ['50%', '45%'],
    avoidLabelOverlap: true,
    itemStyle: { borderColor: 'rgba(255,255,255,.75)', borderWidth: 2 },
    label: { show: false },
    data: data.deviceDistribution.map((item) => ({ name: deviceLabel(item.deviceType), value: item.loginCount })),
  }],
});

const rechargeOption = (data: AnalyticsDashboard): EChartsOption => {
  const days = [...new Set(data.rechargeByDay.map((item) => item.date))];
  const groups = [...new Set(data.rechargeByDay.map((item) => `${item.planName} · ${item.currency}`))];
  return {
    ...options(),
    color: ['#13a9a1', '#4d6bfe', '#d99128', '#8b5cf6', '#d45e81'],
    legend: { top: 0, right: 0, textStyle: { color: '#66779c' } },
    xAxis: { type: 'category', data: days.map((day) => day.slice(5)), axisLabel: { color: '#66779c' }, axisLine: { lineStyle: { color: 'rgba(102,119,156,.24)' } } },
    yAxis: { type: 'value', minInterval: 1, axisLabel: { color: '#66779c' }, splitLine: { lineStyle: { color: 'rgba(102,119,156,.12)' } } },
    series: groups.map((group) => ({
      name: group,
      type: 'bar' as const,
      stack: 'paid-amount',
      data: days.map((day) => data.rechargeByDay
        .filter((item) => item.date === day && `${item.planName} · ${item.currency}` === group)
        .reduce((total, item) => total + item.amountMinor, 0)),
    })),
  };
};

const renderCharts = (): void => {
  const data = dashboard.value;
  if (!data) return;
  const targets: Array<[string, HTMLDivElement | null, EChartsOption]> = [
    ['daily', dailyChart.value, dailyOption(data)],
    ['hourly', hourlyChart.value, hourlyOption(data)],
    ['monthly', monthlyChart.value, monthlyOption(data)],
    ['device', deviceChart.value, deviceOption(data)],
    ['recharge', rechargeChart.value, rechargeOption(data)],
  ];
  for (const [key, target, option] of targets) {
    if (!target) continue;
    const chart = charts.get(key) ?? init(target);
    charts.set(key, chart);
    chart.setOption(option, { notMerge: true });
  }
};

const resizeCharts = (): void => {
  charts.forEach((chart) => chart.resize());
};

const selectLogPage = (page: number): void => {
  void loadOperations(page);
};

const locationFor = (item: AnalyticsOperationLog): string => (
  placeLabel(item.country, item.province, item.city)
);

const loginLocationFor = (item: AnalyticsOperationLog): string => (
  placeLabel(item.loginCountry, item.loginProvince, item.loginCity)
);

onMounted(() => {
  window.addEventListener('resize', resizeCharts, { passive: true });
  void refresh();
});

onBeforeUnmount(() => {
  window.removeEventListener('resize', resizeCharts);
  charts.forEach((chart) => chart.dispose());
  charts.clear();
});
</script>

<template>
  <main class="admin-analytics">
    <header class="analytics-heading">
      <div class="heading-copy">
        <p class="eyebrow">PLATFORM OPERATIONS / ACCOUNT ACTIVITY</p>
        <h1>使用与访问</h1>
        <p>按登录账号汇总访问、关键操作与所在地审计记录。</p>
      </div>
      <div class="filters" role="search" aria-label="统计筛选条件">
        <ElSelect v-model="range" aria-label="统计时间范围" class="range-select">
          <ElOption label="今天" value="TODAY" />
          <ElOption label="昨天" value="YESTERDAY" />
          <ElOption label="本周" value="THIS_WEEK" />
          <ElOption label="本月" value="THIS_MONTH" />
          <ElOption label="上月" value="LAST_MONTH" />
          <ElOption label="自定义" value="CUSTOM" />
        </ElSelect>
        <ElDatePicker
          v-if="range === 'CUSTOM'"
          v-model="dateRange"
          type="daterange"
          value-format="YYYY-MM-DD"
          format="YYYY-MM-DD"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          class="custom-dates"
        />
        <ElInput
          v-model="accountId"
          clearable
          class="account-filter"
          aria-label="按登录账号 ID 筛选"
          placeholder="账号 ID（可选）"
        />
        <ElButton type="primary" :loading="loading" @click="refresh">查询</ElButton>
      </div>
    </header>

    <ElAlert
      v-if="dashboard && !dashboard.rechargeStatisticsAvailable"
      class="recharge-notice"
      type="warning"
      :closable="false"
      title="充值图表尚无数据源"
      description="充值记录表及支付来源尚未启用；支付套餐统计已按待办保留，不会用模拟数据填充。"
    />

    <div v-if="loading && !dashboard" class="loading-state" role="status">正在汇总账号事件…</div>
    <ElEmpty v-else-if="!dashboard" description="选择时间范围并查询统计数据。" />

    <template v-else>
      <section class="metric-strip" aria-label="统计摘要">
        <article class="metric metric-primary">
          <span>活跃账号</span>
          <strong>{{ dashboard.activeUserCount.toLocaleString() }}</strong>
          <small>所选范围内任一日有登录或操作</small>
        </article>
        <article class="metric">
          <span>注册账号</span>
          <strong>{{ dashboard.registeredAccountCount.toLocaleString() }}</strong>
          <small>包含平台管理员</small>
        </article>
        <article class="metric">
          <span>新增账号</span>
          <strong>{{ dashboard.newAccountCount.toLocaleString() }}</strong>
          <small>按账号创建时间</small>
        </article>
        <article class="metric">
          <span>页面访问</span>
          <strong>{{ dashboard.accessCount.toLocaleString() }}</strong>
          <small>页面进入次数，刷新会增加</small>
        </article>
        <article class="metric">
          <span>去重访问账号</span>
          <strong>{{ dashboard.uniqueVisitorCount.toLocaleString() }}</strong>
          <small>所选区间内按账号去重</small>
        </article>
        <article class="metric">
          <span>实际使用账号</span>
          <strong>{{ dashboard.actualUserCount.toLocaleString() }}</strong>
          <small>优化、上下文、导出或充值关键操作</small>
        </article>
        <article class="metric">
          <span>平均日活</span>
          <strong>{{ dashboard.averageDailyActiveUsers.toLocaleString() }}</strong>
          <small>所选自然日均值，含零活跃日</small>
        </article>
      </section>

      <section class="charts-grid" aria-label="使用统计图表">
        <article class="chart-panel chart-wide">
          <div class="panel-heading">
            <div><span class="panel-kicker">ACCOUNT SIGNAL</span><h2>每日访问与活跃</h2></div>
            <span class="period-label">{{ dashboard.period.fromDate }} — {{ dashboard.period.toDateInclusive }}</span>
          </div>
          <div ref="dailyChart" class="chart chart-tall" role="img" aria-label="每日访问、去重访问账号与活跃账号折线图"></div>
        </article>

        <article class="chart-panel">
          <div class="panel-heading">
            <div><span class="panel-kicker">TIME OF DAY</span><h2>高频使用时段</h2></div>
            <ElTag v-if="activeHour" size="small" effect="plain">峰值 {{ String(activeHour.hour).padStart(2, '0') }}:00</ElTag>
          </div>
          <div ref="hourlyChart" class="chart chart-tall" role="img" aria-label="按小时汇总的关键操作柱状图"></div>
        </article>

        <article class="chart-panel">
          <div class="panel-heading">
            <div><span class="panel-kicker">MONTHLY RHYTHM</span><h2>高频月份 · 近 12 个月</h2></div>
            <ElTag v-if="activeMonth" size="small" effect="plain">峰值 {{ activeMonth.month }}</ElTag>
          </div>
          <div ref="monthlyChart" class="chart chart-medium" role="img" aria-label="按月份汇总的关键操作柱状图"></div>
        </article>

        <article class="chart-panel">
          <div class="panel-heading">
            <div><span class="panel-kicker">DEVICE MIX</span><h2>登录设备</h2></div>
          </div>
          <div ref="deviceChart" class="chart chart-medium" role="img" aria-label="手机、平板、电脑和未知设备登录分布图"></div>
        </article>

        <article class="chart-panel chart-wide">
          <div class="panel-heading">
            <div><span class="panel-kicker">RECHARGE RECORDS</span><h2>每日充值套餐</h2></div>
            <small>金额以货币最小单位分币种汇总</small>
          </div>
          <div v-if="dashboard.rechargeStatisticsAvailable" ref="rechargeChart" class="chart chart-medium" role="img" aria-label="每日充值套餐金额堆叠柱状图"></div>
          <div v-else class="chart-empty">支付记录模块完成迁移并接入已验证的支付结果后显示。</div>
        </article>
      </section>

      <section class="data-grid">
    <article class="table-panel">
      <div class="panel-heading">
        <div><span class="panel-kicker">ACCOUNT RANKING</span><h2>使用频率排行</h2></div>
        <div class="ranking-controls">
          <ElSelect v-model="rankingPeriod" aria-label="排行周期" class="rank-period-select">
            <ElOption label="按日" value="DAY" />
            <ElOption label="按周" value="WEEK" />
            <ElOption label="按月" value="MONTH" />
          </ElSelect>
          <ElDatePicker
            v-model="rankingDate"
            type="date"
            value-format="YYYY-MM-DD"
            format="YYYY-MM-DD"
            aria-label="排行锚点日期"
            class="rank-date"
          />
          <ElButton :loading="rankingLoading" @click="loadRanking()">更新排行</ElButton>
        </div>
      </div>
      <p class="panel-caption">按账号 ID 汇总关键操作；管理员账号计入。</p>
      <ElTable v-if="ranking?.items.length" v-loading="rankingLoading" :data="ranking.items" stripe>
            <ElTableColumn label="排名" type="index" width="64" />
            <ElTableColumn label="账号 ID" min-width="230">
              <template #default="scope"><code>{{ scope.row.userId }}</code></template>
            </ElTableColumn>
            <ElTableColumn prop="displayName" label="显示名称" min-width="120" />
            <ElTableColumn prop="operationCount" label="关键操作" width="100" />
            <ElTableColumn prop="loginCount" label="登录次数" width="100" />
            <ElTableColumn prop="activeDays" label="活跃天数" width="100" />
          </ElTable>
      <p v-else class="table-empty">所选周期内没有关键使用操作。</p>
        </article>

        <article class="table-panel operations-panel">
          <div class="panel-heading operation-heading">
            <div><span class="panel-kicker">AUDIT TRAIL</span><h2>关键操作日志</h2></div>
            <ElSelect v-model="eventFilter" clearable aria-label="按操作类型筛选" placeholder="全部操作" class="event-select">
              <ElOption v-for="item in eventTypes" :key="item.value" :label="item.label" :value="item.value" />
            </ElSelect>
          </div>
          <!-- @vue-generic {AnalyticsOperationLog} -->
          <ElTable v-loading="operationsLoading" :data="operationPage?.items ?? []" stripe>
            <ElTableColumn label="发生时间" min-width="175">
              <template #default="scope">{{ new Date(scope.row.occurredAt).toLocaleString() }}</template>
            </ElTableColumn>
            <ElTableColumn label="账号 ID" min-width="230">
              <template #default="scope"><code>{{ scope.row.userId }}</code></template>
            </ElTableColumn>
            <ElTableColumn label="操作" width="120">
              <template #default="scope">{{ eventLabel(scope.row.eventType) }}</template>
            </ElTableColumn>
            <ElTableColumn prop="clientIp" label="IP" min-width="130" />
            <ElTableColumn label="操作所在地" min-width="200">
              <template #default="scope">{{ locationFor(scope.row as AnalyticsOperationLog) }}</template>
            </ElTableColumn>
            <ElTableColumn label="登录所在地" min-width="200">
              <template #default="scope">{{ loginLocationFor(scope.row as AnalyticsOperationLog) }}</template>
            </ElTableColumn>
            <ElTableColumn label="设备" width="90">
              <template #default="scope">{{ deviceLabel(scope.row.deviceType) }}</template>
            </ElTableColumn>
          </ElTable>
          <div v-if="operationPage && operationPage.totalItems > 0" class="pagination-row">
            <span>共 {{ operationPage.totalItems.toLocaleString() }} 条</span>
            <ElPagination
              background
              layout="prev, pager, next"
              :current-page="operationsPageNumber"
              :page-size="pageSize"
              :total="operationPage.totalItems"
              @current-change="selectLogPage"
            />
          </div>
          <p v-else class="table-empty">所选范围内没有关键操作日志。</p>
        </article>
      </section>
    </template>
  </main>
</template>

<style scoped>
.admin-analytics {
  width: min(1480px, 100%);
  margin: 0 auto;
  padding: 34px clamp(16px, 3vw, 42px) 56px;
  color: var(--text-primary);
}

.analytics-heading,
.panel-heading,
.filters,
.pagination-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.analytics-heading {
  align-items: flex-end;
  margin-bottom: 24px;
}

.heading-copy h1 {
  margin: 7px 0 6px;
  font-family: 'Space Grotesk', sans-serif;
  font-size: clamp(28px, 4vw, 38px);
  font-weight: 600;
  letter-spacing: -1.5px;
}

.heading-copy > p:last-child {
  margin: 0;
  color: var(--text-muted);
  font-size: 14px;
}

.eyebrow,
.panel-kicker {
  color: var(--accent);
  font-family: 'JetBrains Mono', monospace;
  font-size: 10px;
  font-weight: 500;
  letter-spacing: 1.35px;
}

.filters {
  justify-content: flex-end;
  flex-wrap: wrap;
}

.range-select { width: 116px; }
.custom-dates { width: 270px; }
.account-filter { width: 230px; }
.event-select { width: 150px; }
.ranking-controls { display: flex; align-items: center; justify-content: flex-end; flex-wrap: wrap; gap: 8px; }
.rank-period-select { width: 90px; }
.rank-date { width: 150px; }

.recharge-notice { margin: 0 0 18px; }

.loading-state,
.chart-empty,
.table-empty {
  display: grid;
  min-height: 140px;
  place-items: center;
  color: var(--text-muted);
  font-size: 13px;
}

.metric-strip {
  display: grid;
  grid-template-columns: repeat(7, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 14px;
}

.metric {
  display: flex;
  min-height: 118px;
  flex-direction: column;
  justify-content: space-between;
  padding: 16px 17px 14px;
  border: 1px solid var(--glass-border-subtle);
  border-radius: 12px;
  background: var(--glass-bg);
  box-shadow: var(--glass-shadow);
}

.metric > span,
.metric small {
  color: var(--text-muted);
  font-size: 12px;
}

.metric strong {
  margin: 8px 0;
  font-family: 'Space Grotesk', sans-serif;
  font-size: clamp(22px, 2.4vw, 29px);
  font-weight: 600;
  letter-spacing: -1px;
  font-variant-numeric: tabular-nums;
}

.metric-primary {
  border-color: var(--accent-border);
  background: linear-gradient(145deg, var(--accent-soft), var(--glass-bg));
}

.charts-grid,
.data-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
  margin-bottom: 14px;
}

.chart-panel,
.table-panel {
  min-width: 0;
  padding: 19px 20px 16px;
  border: 1px solid var(--glass-border-subtle);
  border-radius: 14px;
  background: var(--glass-bg);
  box-shadow: var(--glass-shadow);
}

.chart-wide { grid-column: span 2; }

.panel-heading {
  min-height: 36px;
  margin-bottom: 8px;
}

.panel-heading > div { display: grid; gap: 5px; }

.panel-heading h2 {
  margin: 0;
  color: var(--text-primary);
  font-size: 16px;
  font-weight: 600;
}

.period-label,
.panel-caption,
.panel-heading small {
  color: var(--text-muted);
  font-size: 11px;
}

.period-label { font-family: 'JetBrains Mono', monospace; }
.chart { width: 100%; }
.chart-tall { height: 270px; }
.chart-medium { height: 240px; }
.chart-empty { min-height: 240px; padding: 0 20px; text-align: center; }

.data-grid { grid-template-columns: minmax(340px, 0.85fr) minmax(0, 1.65fr); }
.table-panel { overflow: hidden; }
.operation-heading { margin-bottom: 16px; }
.operations-panel :deep(.el-table) { width: 100%; }
.pagination-row { justify-content: flex-end; margin-top: 15px; color: var(--text-muted); font-size: 12px; }
.table-empty { min-height: 100px; }
code { color: var(--accent); font-family: 'JetBrains Mono', monospace; font-size: 11px; }

@media (max-width: 1120px) {
  .metric-strip { grid-template-columns: repeat(4, minmax(0, 1fr)); }
  .analytics-heading { align-items: flex-start; flex-direction: column; }
  .filters { justify-content: flex-start; }
}

@media (max-width: 800px) {
  .charts-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .chart-wide { grid-column: span 2; }
  .data-grid { grid-template-columns: minmax(0, 1fr); }
}

@media (max-width: 560px) {
  .admin-analytics { padding: 24px 12px 40px; }
  .metric-strip { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .metric { min-height: 104px; padding: 13px; }
  .charts-grid { grid-template-columns: minmax(0, 1fr); }
  .chart-wide { grid-column: auto; }
  .chart-panel, .table-panel { padding: 15px 13px; }
  .filters { width: 100%; }
  .range-select, .account-filter, .custom-dates { width: 100%; }
  .filters :deep(.el-button) { width: 100%; }
  .panel-heading { align-items: flex-start; flex-direction: column; }
  .ranking-controls { justify-content: flex-start; }
  .rank-period-select, .rank-date { width: 100%; }
  .pagination-row { align-items: flex-end; flex-direction: column; }
}
</style>
