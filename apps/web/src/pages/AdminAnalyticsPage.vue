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
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';

import { useUiTheme } from '@/composables/useUiTheme';
import {
  analyticsQueryError,
  dailyMetricSeries,
  findUsagePeak,
  formatAnalyticsTime,
  hasDailyActivity,
  rechargeMetricSeries,
} from '@/features/analytics/analyticsPresentation';
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
const dateRange = ref<string[] | null>(null);
const accountId = ref('');
const eventFilter = ref<AnalyticsEventType | ''>('');
// 筛选输入是草稿；翻页和排行只能复用最近一次成功查询的账号与操作类型。
const appliedFilters = ref<{ userId?: string; eventType?: AnalyticsEventType }>({});
const dashboard = ref<AnalyticsDashboard>();
const operationPage = ref<AnalyticsOperationLogPage>();
const ranking = ref<AnalyticsRanking>();
const loading = ref(false);
const operationsLoading = ref(false);
const rankingLoading = ref(false);
const operationsError = ref('');
const rankingError = ref('');
let operationsRequestId = 0;
let rankingRequestId = 0;
const operationsPageNumber = ref(1);
const rankingPeriod = ref<AnalyticsRankingPeriod>('DAY');
const rankingDate = ref<string | null>('');
const rechargeCurrency = ref('');
const OPERATION_PAGE_SIZES = [10, 20, 50] as const;
const operationsPageSize = ref<(typeof OPERATION_PAGE_SIZES)[number]>(10);
let ignoreOperationsEcho = false;

const dailyChart = ref<HTMLDivElement | null>(null);
const hourlyChart = ref<HTMLDivElement | null>(null);
const monthlyChart = ref<HTMLDivElement | null>(null);
const deviceChart = ref<HTMLDivElement | null>(null);
const rechargeChart = ref<HTMLDivElement | null>(null);
const charts = new Map<string, EChartsType>();
const { activeThemeId } = useUiTheme();

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
  { value: 'ADMIN_MODEL_CHANGED', label: '模型配置变更' },
];

const activeMonth = computed(() => findUsagePeak(dashboard.value?.monthlyUsage ?? []));
const activeHour = computed(() => findUsagePeak(dashboard.value?.hourlyUsage ?? []));
const hasDailyData = computed(() => hasDailyActivity(dashboard.value?.dailyMetrics ?? []));
const rechargeCurrencies = computed(() => [...new Set(dashboard.value?.rechargeByDay.map((item) => item.currency) ?? [])]);
const selectedRechargeCount = computed(() => (dashboard.value?.rechargeByDay ?? [])
  .filter((item) => item.currency === rechargeCurrency.value).reduce((total, item) => total + item.paidCount, 0));

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
  const error = analyticsQueryError(range.value, dateRange.value, normalizedAccountId);
  if (error) {
    ElMessage.warning(error);
    return undefined;
  }
  if (range.value === 'CUSTOM') {
    return {
      range: range.value,
      fromDate: dateRange.value?.[0],
      toDate: dateRange.value?.[1],
      ...(normalizedAccountId ? { userId: normalizedAccountId } : {}),
    };
  }
  return {
    range: range.value,
    ...(normalizedAccountId ? { userId: normalizedAccountId } : {}),
  };
};

const loadOperations = async (page: number, filters = appliedFilters.value): Promise<void> => {
  const current = dashboard.value;
  if (!current) return;
  const requestId = ++operationsRequestId;
  operationsLoading.value = true;
  operationsError.value = '';
  try {
    const result = await getAnalyticsOperations({
      fromDate: current.period.fromDate,
      toDate: current.period.toDateInclusive,
      current: page,
      size: operationsPageSize.value,
      ...filters,
    });
    if (requestId !== operationsRequestId) return;
    operationPage.value = result;
    appliedFilters.value = filters;
    operationsPageNumber.value = page;
  } catch (error: unknown) {
    if (requestId !== operationsRequestId) return;
    operationsError.value = getApiErrorMessage(error);
    ElMessage.error(operationsError.value);
  } finally {
    if (requestId === operationsRequestId) operationsLoading.value = false;
  }
};

const loadRanking = async (fallbackDate?: string): Promise<void> => {
  const selectedDate = rankingDate.value || fallbackDate;
  if (!selectedDate) {
    ElMessage.warning('请选择排行锚点日期。');
    return;
  }
  const requestId = ++rankingRequestId;
  rankingLoading.value = true;
  rankingError.value = '';
  try {
    const result = await getAnalyticsRanking(
      rankingPeriod.value,
      selectedDate,
      20,
      appliedFilters.value.userId,
    );
    if (requestId !== rankingRequestId) return;
    ranking.value = result;
    rankingDate.value = selectedDate;
  } catch (error: unknown) {
    if (requestId !== rankingRequestId) return;
    rankingError.value = getApiErrorMessage(error);
    ElMessage.error(rankingError.value);
  } finally {
    if (requestId === rankingRequestId) rankingLoading.value = false;
  }
};

const refresh = async (): Promise<void> => {
  if (loading.value) return;
  const query = makeQuery();
  if (!query) return;
  const submittedFilters = {
    ...(query.userId ? { userId: query.userId } : {}),
    ...(eventFilter.value ? { eventType: eventFilter.value } : {}),
  };
  loading.value = true;
  try {
    dashboard.value = await getAnalyticsDashboard(query);
    if (!rechargeCurrencies.value.includes(rechargeCurrency.value)) {
      rechargeCurrency.value = rechargeCurrencies.value[0] ?? '';
    }
    appliedFilters.value = submittedFilters;
    // 新统计到达后丢弃旧条件的明细与在途响应，避免不同账号的数据混在同一屏。
    operationsRequestId += 1;
    rankingRequestId += 1;
    operationPage.value = undefined;
    ranking.value = undefined;
    operationsPageNumber.value = 1;
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

/** 图表读取现有主题变量，坐标轴与图例在深浅主题中保持可读；不修改数据序列。 */
const chartColor = (variable: string): string =>
  getComputedStyle(document.documentElement).getPropertyValue(variable).trim();

const options = (): EChartsOption => ({
  animationDuration: 320,
  textStyle: { fontFamily: getComputedStyle(document.documentElement).fontFamily, color: chartColor('--text-secondary') },
  tooltip: { trigger: 'axis', confine: true },
  grid: { left: 8, right: 12, top: 48, bottom: 12, containLabel: true },
});

const dailyOption = (data: AnalyticsDashboard): EChartsOption => ({
  ...options(),
  color: [chartColor('--accent'), chartColor('--success'), chartColor('--warning'), chartColor('--pink'), chartColor('--text-muted')],
  legend: { type: 'scroll', top: 0, right: 0, textStyle: { color: chartColor('--text-secondary') } },
  xAxis: {
    type: 'category',
    boundaryGap: false,
    data: data.dailyMetrics.map((item) => item.date),
    axisLabel: { color: chartColor('--text-secondary') },
    axisLine: { lineStyle: { color: chartColor('--glass-border') } },
  },
  yAxis: { type: 'value', minInterval: 1, axisLabel: { color: chartColor('--text-secondary') }, splitLine: { lineStyle: { color: chartColor('--glass-border-subtle') } } },
  series: dailyMetricSeries(data.dailyMetrics),
});

const hourlyOption = (data: AnalyticsDashboard): EChartsOption => ({
  ...options(),
  color: [chartColor('--success')],
  xAxis: { type: 'category', data: data.hourlyUsage.map((item) => `${String(item.hour).padStart(2, '0')}:00`), axisLabel: { color: chartColor('--text-secondary'), interval: 2 }, axisLine: { lineStyle: { color: chartColor('--glass-border') } } },
  yAxis: { type: 'value', minInterval: 1, axisLabel: { color: chartColor('--text-secondary') }, splitLine: { lineStyle: { color: chartColor('--glass-border-subtle') } } },
  series: [{ name: '关键操作', type: 'bar', barMaxWidth: 22, data: data.hourlyUsage.map((item) => item.operationCount), itemStyle: { borderRadius: [4, 4, 0, 0] } }],
});

const monthlyOption = (data: AnalyticsDashboard): EChartsOption => ({
  ...options(),
  color: [chartColor('--accent')],
  xAxis: { type: 'category', data: data.monthlyUsage.map((item) => item.month), axisLabel: { color: chartColor('--text-secondary') }, axisLine: { lineStyle: { color: chartColor('--glass-border') } } },
  yAxis: { type: 'value', minInterval: 1, axisLabel: { color: chartColor('--text-secondary') }, splitLine: { lineStyle: { color: chartColor('--glass-border-subtle') } } },
  series: [{ name: '关键操作', type: 'bar', barMaxWidth: 30, data: data.monthlyUsage.map((item) => item.operationCount), itemStyle: { borderRadius: [4, 4, 0, 0] } }],
});

const deviceOption = (data: AnalyticsDashboard): EChartsOption => ({
  ...options(),
  color: [chartColor('--accent'), chartColor('--success'), chartColor('--warning'), chartColor('--text-muted')],
  tooltip: { trigger: 'item', confine: true, formatter: '{b}: {c} 次登录（{d}%）' },
  legend: { type: 'scroll', bottom: 0, textStyle: { color: chartColor('--text-secondary') } },
  series: [{
    name: '登录设备',
    type: 'pie',
    radius: ['54%', '76%'],
    center: ['50%', '45%'],
    avoidLabelOverlap: true,
    itemStyle: { borderColor: chartColor('--bg-base'), borderWidth: 2 },
    label: { show: false },
    data: data.deviceDistribution.map((item) => ({ name: deviceLabel(item.deviceType), value: item.loginCount })),
  }],
});

const rechargeOption = (data: AnalyticsDashboard): EChartsOption => {
  const days = data.dailyMetrics.map((item) => item.date);
  return {
    ...options(),
    color: [chartColor('--success'), chartColor('--accent'), chartColor('--warning'), chartColor('--pink'), chartColor('--text-muted')],
    legend: { type: 'scroll', top: 0, right: 0, textStyle: { color: chartColor('--text-secondary') } },
    xAxis: { type: 'category', data: days, axisLabel: { color: chartColor('--text-secondary') }, axisLine: { lineStyle: { color: chartColor('--glass-border') } } },
    yAxis: { type: 'value', minInterval: 1, axisLabel: { color: chartColor('--text-secondary') }, splitLine: { lineStyle: { color: chartColor('--glass-border-subtle') } } },
    series: rechargeMetricSeries(data.rechargeByDay, rechargeCurrency.value, days),
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
    if (!target || target.clientWidth === 0) {
      charts.get(key)?.dispose();
      charts.delete(key);
      continue;
    }
    const chart = charts.get(key) ?? init(target);
    charts.set(key, chart);
    chart.resize();
    chart.setOption(option, { notMerge: true });
  }
};

const resizeCharts = (): void => {
  charts.forEach((chart) => { if (chart.getDom().clientWidth > 0) chart.resize(); });
};

watch(activeThemeId, () => { void nextTick(renderCharts); });
watch(rechargeCurrency, () => { void nextTick(renderCharts); });

/** 日志类型可在表格附近单独提交，仍复用最近一次成功查询的账号及日期。 */
const applyLogFilter = (): void => {
  void loadOperations(1, { ...appliedFilters.value, eventType: eventFilter.value || undefined });
};

const selectLogPage = (page: number): void => {
  if (ignoreOperationsEcho) {
    ignoreOperationsEcho = false;
    return;
  }
  if (page === operationsPageNumber.value) {
    return;
  }
  void loadOperations(page);
};

const selectLogPageSize = (nextSize: number): void => {
  const size = OPERATION_PAGE_SIZES.find((option) => option === nextSize);
  if (size === undefined || size === operationsPageSize.value) {
    return;
  }
  ignoreOperationsEcho = true;
  operationsPageSize.value = size;
  operationsPageNumber.value = 1;
  void loadOperations(1);
  void nextTick(() => {
    ignoreOperationsEcho = false;
  });
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
  <div class="admin-analytics">
    <header class="analytics-heading">
      <div class="heading-copy">
        <p class="eyebrow">PLATFORM OPERATIONS / ACCOUNT ACTIVITY</p>
        <h1>使用与访问</h1>
        <p>按登录账号汇总访问、关键操作与所在地审计记录。</p>
      </div>
    </header>
    <div class="filters" role="search" aria-label="统计筛选条件" @keyup.enter.capture="refresh">
      <div class="filter-field">
        <span class="filter-label">时间范围</span>
        <ElSelect v-model="range" aria-label="统计时间范围" class="range-select">
          <ElOption label="今天" value="TODAY" />
          <ElOption label="昨天" value="YESTERDAY" />
          <ElOption label="本周" value="THIS_WEEK" />
          <ElOption label="本月" value="THIS_MONTH" />
          <ElOption label="上月" value="LAST_MONTH" />
          <ElOption label="自定义" value="CUSTOM" />
        </ElSelect>
      </div>
      <div v-if="range === 'CUSTOM'" class="filter-field filter-field--dates">
        <span class="filter-label">起止日期 · 最多 366 天</span>
        <ElDatePicker
          v-model="dateRange"
          type="daterange"
          value-format="YYYY-MM-DD"
          format="YYYY-MM-DD"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          class="custom-dates"
          popper-class="analytics-date-range-popper"
        />
      </div>
      <div class="filter-field filter-field--account">
        <span class="filter-label">登录账号</span>
        <ElInput
          v-model="accountId"
          clearable
          class="account-filter"
          aria-label="按登录账号 ID 筛选"
          placeholder="账号 ID（可选）"
        />
      </div>
      <ElButton type="primary" :loading="loading" @click="refresh">查询</ElButton>
    </div>

    <div v-if="loading && !dashboard" class="loading-state" role="status">正在汇总账号事件…</div>
    <ElEmpty v-else-if="!dashboard" description="选择时间范围并查询统计数据。" />

    <template v-else>
      <div class="query-summary" aria-label="已生效的统计条件">
        <span class="query-period">{{ dashboard.period.fromDate }} — {{ dashboard.period.toDateInclusive }}</span>
        <span>统计时区：{{ dashboard.period.zoneId }}</span>
        <span>{{ appliedFilters.userId ? `账号：${appliedFilters.userId}` : '全部登录账号 · 包含管理员' }}</span>
      </div>
      <section class="metric-strip" aria-label="统计摘要">
        <article class="metric metric-primary">
          <span>活跃账号</span>
          <strong>{{ dashboard.activeUserCount.toLocaleString() }}</strong>
          <small>所选范围内任一日有登录或操作</small>
        </article>
        <article class="metric">
          <span>注册账号</span>
          <strong>{{ dashboard.registeredAccountCount.toLocaleString() }}</strong>
          <small>当前存量，包含平台管理员</small>
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
            <div class="panel-title"><span class="panel-kicker">ACCOUNT SIGNAL</span><h2>每日访问与活跃</h2></div>
            <span class="period-label">{{ dashboard.period.fromDate }} — {{ dashboard.period.toDateInclusive }}</span>
          </div>
          <div v-show="hasDailyData" ref="dailyChart" class="chart chart-tall" role="img" aria-label="每日访问、去重访问、活跃、实际使用与新增账号折线图"></div>
          <div v-if="!hasDailyData" class="chart-empty chart-tall">所选范围内暂无每日访问数据。</div>
        </article>

        <article class="chart-panel">
          <div class="panel-heading">
            <div class="panel-title"><span class="panel-kicker">TIME OF DAY</span><h2>高频使用时段</h2></div>
            <ElTag v-if="activeHour" size="small" effect="plain">峰值 {{ String(activeHour.hour).padStart(2, '0') }}:00</ElTag>
          </div>
          <div v-show="activeHour" ref="hourlyChart" class="chart chart-tall" role="img" aria-label="按小时汇总的关键操作柱状图"></div>
          <div v-if="!activeHour" class="chart-empty chart-tall">所选范围内暂无时段统计。</div>
        </article>

        <article class="chart-panel">
          <div class="panel-heading">
            <div class="panel-title"><span class="panel-kicker">MONTHLY RHYTHM</span><h2>高频月份 · 近 12 个月</h2></div>
            <ElTag v-if="activeMonth" size="small" effect="plain">峰值 {{ activeMonth.month }}</ElTag>
          </div>
          <p class="panel-caption">固定显示截至今天的近 12 个月，沿用账号筛选。</p>
          <div v-show="activeMonth" ref="monthlyChart" class="chart chart-medium" role="img" aria-label="按月份汇总的关键操作柱状图"></div>
          <div v-if="!activeMonth" class="chart-empty">近 12 个月暂无使用数据。</div>
        </article>

        <article class="chart-panel">
          <div class="panel-heading">
            <div class="panel-title"><span class="panel-kicker">DEVICE MIX</span><h2>登录设备</h2></div>
          </div>
          <div v-show="dashboard.deviceDistribution.length" ref="deviceChart" class="chart chart-medium" role="img" aria-label="手机、平板、电脑和未知设备登录分布图"></div>
          <div v-if="!dashboard.deviceDistribution.length" class="chart-empty">所选范围内暂无登录设备数据。</div>
          <ul v-else class="device-summary" aria-label="各设备登录次数与去重账号">
            <li v-for="item in dashboard.deviceDistribution" :key="item.deviceType">
              <span>{{ deviceLabel(item.deviceType) }}</span>
              <span>{{ item.loginCount.toLocaleString() }} 次 · {{ item.uniqueUsers.toLocaleString() }} 个账号</span>
            </li>
          </ul>
          <p v-if="dashboard.deviceDistribution.length" class="panel-caption device-note">账号在各设备内去重，跨设备不可相加。</p>
        </article>

        <article class="chart-panel">
          <div class="panel-heading">
            <div class="panel-title"><span class="panel-kicker">RECHARGE RECORDS</span><h2>每日充值套餐</h2></div>
            <ElSelect v-if="rechargeCurrencies.length > 1" v-model="rechargeCurrency" aria-label="充值统计币种" class="currency-select">
              <ElOption v-for="currency in rechargeCurrencies" :key="currency" :label="currency" :value="currency" />
            </ElSelect>
          </div>
          <p v-if="dashboard.rechargeStatisticsAvailable && rechargeCurrencies.length" class="panel-caption">{{ rechargeCurrency }} · 最小货币单位 · 成功支付 {{ selectedRechargeCount }} 笔</p>
          <div v-show="dashboard.rechargeStatisticsAvailable && dashboard.rechargeByDay.length" ref="rechargeChart" class="chart chart-medium" role="img" aria-label="每日充值套餐金额堆叠柱状图"></div>
          <div v-if="!dashboard.rechargeStatisticsAvailable" class="chart-empty recharge-placeholder"><strong>充值图表尚无数据源</strong><span>套餐与支付功能接入后显示每日统计。</span></div>
          <div v-else-if="!dashboard.rechargeByDay.length" class="chart-empty">所选范围内暂无充值记录。</div>
        </article>
      </section>

      <section class="data-grid" aria-label="账号排行与操作明细">
        <article class="table-panel ranking-panel">
          <div class="panel-heading">
            <div class="panel-title"><span class="panel-kicker">ACCOUNT RANKING</span><h2>使用频率排行</h2></div>
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
          <p class="panel-caption">按账号 ID 汇总关键操作；管理员账号计入。<span class="table-scroll-hint">左右滑动表格可查看完整字段。</span></p>
          <p v-if="ranking" class="panel-caption">实际排行周期：{{ ranking.period.fromDate }} — {{ ranking.period.toDateInclusive }} · {{ ranking.period.zoneId }} · 活跃天数包含仅访问的日期</p>
          <ElAlert v-if="rankingError" type="error" :closable="false" :title="rankingError" />
          <ElTable v-else v-loading="rankingLoading" :data="ranking?.items ?? []" stripe :max-height="440" empty-text="所选周期内没有关键使用操作。">
            <ElTableColumn label="排名" type="index" width="64" align="center" />
            <ElTableColumn label="账号 ID" min-width="280" show-overflow-tooltip>
              <template #default="scope"><code>{{ scope.row.userId }}</code></template>
            </ElTableColumn>
            <ElTableColumn prop="displayName" label="显示名称" min-width="180" show-overflow-tooltip />
            <ElTableColumn prop="operationCount" label="关键操作" min-width="120" align="right" />
            <ElTableColumn prop="loginCount" label="登录次数" min-width="110" align="right" />
            <ElTableColumn prop="activeDays" label="活跃天数" min-width="110" align="right" />
          </ElTable>
        </article>

        <article class="table-panel operations-panel">
          <div class="panel-heading operation-heading">
            <div class="panel-title"><span class="panel-kicker">AUDIT TRAIL</span><h2>关键操作日志</h2></div>
            <div class="operation-controls">
              <ElSelect v-model="eventFilter" clearable aria-label="按操作类型筛选" placeholder="全部操作" class="event-select">
                <ElOption v-for="item in eventTypes" :key="item.value" :label="item.label" :value="item.value" />
              </ElSelect>
              <ElButton :loading="operationsLoading" :disabled="loading" @click="applyLogFilter">筛选日志</ElButton>
            </div>
          </div>
          <p class="panel-caption">{{ dashboard.period.fromDate }} — {{ dashboard.period.toDateInclusive }} · {{ dashboard.period.zoneId }} · {{ appliedFilters.eventType ? eventLabel(appliedFilters.eventType) : '全部操作' }}<span class="table-scroll-hint">左右滑动表格可查看完整字段。</span></p>
          <ElAlert v-if="operationsError" type="error" :closable="false" :title="operationsError" />
          <!-- @vue-generic {AnalyticsOperationLog} -->
          <ElTable v-else v-loading="operationsLoading" :data="operationPage?.records ?? []" row-key="eventId" stripe :max-height="560" empty-text="所选范围内没有关键操作日志。">
            <ElTableColumn label="发生时间" min-width="185" show-overflow-tooltip>
              <template #default="scope">{{ formatAnalyticsTime(scope.row.occurredAt, dashboard.period.zoneId) }}</template>
            </ElTableColumn>
            <ElTableColumn label="账号 ID" min-width="280" show-overflow-tooltip>
              <template #default="scope"><code>{{ scope.row.userId }}</code></template>
            </ElTableColumn>
            <ElTableColumn label="操作" width="120">
              <template #default="scope">{{ eventLabel(scope.row.eventType) }}</template>
            </ElTableColumn>
            <ElTableColumn prop="displayName" label="显示名称" min-width="180" show-overflow-tooltip />
            <ElTableColumn prop="clientIp" label="IP" min-width="160" show-overflow-tooltip />
            <ElTableColumn label="操作所在地" min-width="200" show-overflow-tooltip>
              <template #default="scope">{{ locationFor(scope.row as AnalyticsOperationLog) }}</template>
            </ElTableColumn>
            <ElTableColumn label="登录所在地" min-width="200" show-overflow-tooltip>
              <template #default="scope">{{ loginLocationFor(scope.row as AnalyticsOperationLog) }}</template>
            </ElTableColumn>
            <ElTableColumn prop="eventId" label="事件 ID" min-width="280" show-overflow-tooltip />
            <ElTableColumn label="设备" width="90">
              <template #default="scope">{{ deviceLabel(scope.row.deviceType) }}</template>
            </ElTableColumn>
          </ElTable>
          <div v-if="operationPage && operationPage.total > 0" class="pagination-row">
            <span>共 {{ operationPage.total.toLocaleString() }} 条</span>
            <ElPagination
              background
              layout="prev, pager, next, sizes"
              :current-page="operationsPageNumber"
              :page-size="operationsPageSize"
              :page-sizes="[...OPERATION_PAGE_SIZES]"
              :pager-count="5"
              :total="operationPage.total"
              @current-change="selectLogPage"
              @size-change="selectLogPageSize"
            />
          </div>
        </article>
      </section>
    </template>
  </div>
</template>

<style scoped>
.admin-analytics {
  --analytics-gap: 16px;
  width: 100%;
  min-width: 0;
  padding: 24px clamp(16px, 1.5vw, 28px) 48px;
  color: var(--text-primary);
}

.panel-heading,
.filters,
.pagination-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--analytics-gap);
}

.analytics-heading {
  margin-bottom: 20px;
}

.heading-copy h1 {
  margin: 7px 0 6px;
  font-family: var(--font-display);
  font-size: clamp(26px, 2.2vw, 34px);
  font-weight: 600;
  letter-spacing: -0.8px;
}

.heading-copy > p:last-child {
  margin: 0;
  color: var(--text-secondary);
  font-size: 14px;
  line-height: 1.7;
}

.eyebrow,
.panel-kicker {
  color: var(--accent);
  font-family: var(--font-mono);
  font-size: 10px;
  font-weight: 500;
  letter-spacing: 1.35px;
}

.filters {
  align-items: flex-end;
  justify-content: flex-start;
  flex-wrap: wrap;
  margin-bottom: var(--analytics-gap);
  padding: 16px 20px;
  border: 1px solid var(--glass-border-subtle);
  border-radius: var(--radius-md);
  background: var(--glass-bg);
}

.filter-field { display: grid; flex: 0 0 160px; min-width: 0; gap: 7px; }
.filter-field--dates { flex: 1 1 300px; }
.filter-field--account { flex: 1 1 240px; }
.filter-label { color: var(--text-secondary); font-size: 12px; font-weight: 500; }
.range-select, .account-filter, .custom-dates { width: 100%; min-width: 0; }
.filters :deep(.el-date-editor) { width: 100%; min-width: 0; }
.filters > .el-button { min-width: 96px; }
.event-select { width: 168px; }
.ranking-controls { display: flex; min-width: 0; align-items: center; flex-wrap: wrap; gap: 8px; }
.rank-period-select { width: 96px; }
.rank-date.el-date-editor { width: 160px; }

.query-summary { display: flex; flex-wrap: wrap; gap: 8px 20px; margin: 0 2px 16px; color: var(--text-secondary); font-size: 12px; line-height: 1.7; overflow-wrap: anywhere; }
.query-period { color: var(--text-primary); font-family: var(--font-mono); font-variant-numeric: tabular-nums; }
.operation-controls { display: flex; flex-wrap: wrap; gap: 8px; min-width: 0; }
.currency-select { width: 104px; }
.device-summary { display: grid; gap: 8px; margin: 0; padding: 0; list-style: none; color: var(--text-secondary); font-size: 12px; }
.device-summary li { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 4px 12px; font-variant-numeric: tabular-nums; }
.device-note { margin-top: 12px; margin-bottom: 0; }
.recharge-placeholder { align-content: center; gap: 10px; }
.recharge-placeholder strong { color: var(--text-primary); font-weight: 500; }

.loading-state,
.chart-empty {
  display: grid;
  min-height: 140px;
  place-items: center;
  color: var(--text-secondary);
  font-size: 13px;
}

.metric-strip {
  display: grid;
  grid-template-columns: repeat(7, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: var(--analytics-gap);
}

.metric {
  display: flex;
  min-width: 0;
  min-height: 130px;
  flex-direction: column;
  justify-content: space-between;
  padding: 16px;
  border: 1px solid var(--glass-border-subtle);
  border-radius: var(--radius-md);
  background: var(--glass-bg);
  box-shadow: var(--glass-shadow);
}

.metric > span,
.metric small {
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.6;
}

.metric strong {
  margin: 8px 0;
  font-family: var(--font-display);
  font-size: clamp(24px, 1.8vw, 32px);
  font-weight: 600;
  line-height: 1.2;
  letter-spacing: -0.7px;
  font-variant-numeric: tabular-nums;
  overflow-wrap: anywhere;
}

.metric-primary {
  border-color: var(--accent-border);
  background: linear-gradient(145deg, var(--accent-soft), var(--glass-bg));
}

.charts-grid,
.data-grid {
  display: grid;
  gap: var(--analytics-gap);
  margin-bottom: var(--analytics-gap);
}

.charts-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); }
.data-grid { grid-template-columns: minmax(0, 1fr); }

.chart-panel,
.table-panel {
  min-width: 0;
  padding: 20px;
  border: 1px solid var(--glass-border-subtle);
  border-radius: var(--radius-md);
  background: var(--glass-bg);
  box-shadow: var(--glass-shadow);
}

.chart-wide { grid-column: span 2; }

.panel-heading {
  min-height: 40px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}

.panel-title { display: grid; min-width: 0; gap: 5px; }

.panel-heading h2 {
  margin: 0;
  color: var(--text-primary);
  font-size: 16px;
  font-weight: 600;
}

.period-label,
.panel-caption,
.panel-heading small {
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.6;
  overflow-wrap: anywhere;
}

.period-label { font-family: var(--font-mono); font-variant-numeric: tabular-nums; }
.panel-caption { margin: 0 0 14px; }
.chart { width: 100%; min-width: 0; }
.chart-tall { height: 280px; }
.chart-medium { height: 250px; }
.chart-empty { min-height: 250px; padding: 20px; text-align: center; }

.table-panel { overflow: hidden; }
.table-panel :deep(.el-table) {
  --el-table-header-bg-color: var(--glass-bg-strong);
  --el-table-header-text-color: var(--text-primary);
  --el-table-text-color: var(--text-secondary);
  width: 100%;
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}
.table-panel :deep(.el-table__cell) { padding: 12px 0; }
.table-panel :deep(.el-table .cell) { padding: 0 14px; }
.table-panel :deep(.el-table__empty-text) { width: 100%; padding: 16px; line-height: 1.7; }
.table-scroll-hint { display: none; margin-left: 12px; }
.pagination-row { flex-wrap: wrap; margin-top: 16px; color: var(--text-secondary); font-size: 13px; }
.pagination-row > span { flex-shrink: 0; }
.pagination-row :deep(.el-pagination) { max-width: 100%; flex-wrap: wrap; gap: 8px; }
code { color: var(--accent); font-family: var(--font-mono); font-size: 12px; }

@media (max-width: 1439px) {
  .metric-strip { grid-template-columns: repeat(4, minmax(0, 1fr)); }
}

@media (max-width: 1100px) {
  .charts-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .table-scroll-hint { display: inline; }
}

@media (max-width: 680px) {
  .admin-analytics { --analytics-gap: 12px; padding: 20px 12px calc(40px + env(safe-area-inset-bottom, 0px)); }
  .eyebrow { letter-spacing: 0.8px; }
  .filters { padding: 14px; }
  .filter-field { flex-basis: 100%; }
  .filters > .el-button { width: 100%; }
  .metric-strip { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .metric { min-height: 112px; padding: 12px; }
  .metric strong { margin: 5px 0; }
  .query-summary { gap: 4px; flex-direction: column; }
  .charts-grid { grid-template-columns: minmax(0, 1fr); }
  .chart-wide { grid-column: auto; }
  .chart-panel, .table-panel { padding: 16px 14px; }
  .ranking-panel .panel-heading { align-items: flex-start; flex-direction: column; }
  .ranking-controls { display: grid; width: 100%; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); }
  .rank-period-select, .rank-date.el-date-editor { width: 100%; min-width: 0; }
  .ranking-controls > .el-button { grid-column: 1 / -1; }
  .operation-controls { width: 100%; }
  .event-select { flex: 1; min-width: 0; width: auto; }
  .table-scroll-hint { display: block; margin: 4px 0 0; }
  .pagination-row { align-items: flex-start; flex-direction: column; }
  .pagination-row :deep(.el-pagination) { justify-content: flex-start; }
  .pagination-row :deep(.el-pagination__sizes) { margin: 0; }
  /* 日期弹层通过专属类适配手机，不改变其他页面的日期控件。 */
  :global(.analytics-date-range-popper .el-date-range-picker) { width: min(640px, calc(100vw - 24px)); max-height: calc(100dvh - 100px); overflow-y: auto; }
  :global(.analytics-date-range-popper .el-date-range-picker__content) { width: 100%; float: none; }
}
</style>
