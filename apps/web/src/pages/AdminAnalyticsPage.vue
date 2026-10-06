<script setup lang="ts">
import {
  Calendar, CirclePlus, Clock, DataLine, Filter, List, Lock,
  MagicStick, Monitor, Mouse, Search, TrendCharts, Trophy, User, UserFilled, View, Wallet,
} from '@element-plus/icons-vue';
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
  ElConfigProvider,
  ElDatePicker,
  ElEmpty,
  ElIcon,
  ElInput,
  ElMessage,
  ElOption,
  ElPagination,
  ElSelect,
  ElSkeleton,
  ElTable,
  ElTableColumn,
  ElTag,
} from 'element-plus';
import zhCn from 'element-plus/es/locale/lang/zh-cn';
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';

import { useUiTheme } from '@/composables/useUiTheme';
import {
  analyticsQueryError,
  dailyMetricSeries,
  featureUsageSeries,
  findUsagePeak,
  formatAnalyticsTime,
  hasDailyActivity,
  rechargeMetricSeries,
} from '@/features/analytics/analyticsPresentation';
import { getApiErrorMessage } from '@/services/http';
import { getAnalyticsDashboard, getAnalyticsDeliveryStatus, getAnalyticsOperations, getAnalyticsRanking } from '@/services/adminAnalyticsApi';
import type {
  AnalyticsAccountFilters,
  AnalyticsDashboard,
  AnalyticsDashboardQuery,
  AnalyticsDeliveryStatus,
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
const email = ref('');
const displayName = ref('');
const eventFilter = ref<AnalyticsEventType | ''>('');
// 筛选输入是草稿；翻页和排行只能复用最近一次成功查询的账号与操作类型。
const appliedFilters = ref<AnalyticsAccountFilters & { eventType?: AnalyticsEventType }>({});
const dashboard = ref<AnalyticsDashboard>();
const operationPage = ref<AnalyticsOperationLogPage>();
const ranking = ref<AnalyticsRanking>();
const loading = ref(false);
const deliveryStatus = ref<AnalyticsDeliveryStatus>();
const deliveryError = ref<string>();
let deliveryRequestId = 0;
let deliveryTimer: number | undefined;
const deliveryAlert = computed(() => {
  if (deliveryError.value) return {
    type: 'warning' as const, title: '无法读取审计投递状态',
    description: `${deliveryError.value} 统计查询仍可使用，请重新查询或检查后台。`,
  };
  const status = deliveryStatus.value;
  if (!status || (status.healthy && status.pendingEvents === 0)) return undefined;
  return {
    type: status.status === 'UNAVAILABLE' || status.corruptFiles > 0 ? 'error' as const : 'warning' as const,
    title: status.status === 'UNAVAILABLE' ? '审计投递暂不可用'
      : status.pendingEvents > 0 ? '审计事件正在补写' : '审计投递需要检查',
    description: `当前实例待补写 ${status.pendingEvents.toLocaleString()} 条，最老事件距今 ${status.oldestPendingAgeSeconds.toLocaleString()} 秒，损坏文件 ${status.corruptFiles.toLocaleString()} 个。数据库投递${status.databaseAvailable ? '可用' : '异常'}，持久接收${status.journalAvailable ? '可用' : '异常'}。统计可能尚未包含待补写事件；补写完成后点击查询刷新。`,
  };
});
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
const featureUsageChart = ref<HTMLDivElement | null>(null);
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
  { value: 'DIRECT_OPTIMIZATION_SUBMITTED', label: '直接增强提交' },
  { value: 'PLAN_COMPLETED', label: 'Plan 完成' },
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
const hasFeatureUsageData = computed(() => dashboard.value?.dailyMetrics.some((item) => (
  item.directEnhancementCount > 0 || item.planCompletedCount > 0
)) ?? false);
const rechargeCurrencies = computed(() => [...new Set(dashboard.value?.rechargeByDay.map((item) => item.currency) ?? [])]);
const selectedRechargeCount = computed(() => (dashboard.value?.rechargeByDay ?? [])
  .filter((item) => item.currency === rechargeCurrency.value).reduce((total, item) => total + item.paidCount, 0));
const totalLogins = computed(() => (dashboard.value?.deviceDistribution ?? [])
  .reduce((total, item) => total + item.loginCount, 0));
const appliedAccountLabel = computed(() => {
  const filters = appliedFilters.value;
  const labels = [
    filters.userId ? `账号：${filters.userId}` : '',
    filters.email ? `邮箱包含：${filters.email}` : '',
    filters.displayName ? `名称包含：${filters.displayName}` : '',
  ].filter(Boolean);
  return labels.length ? labels.join(' · ') : '全部登录账号 · 包含管理员';
});

/** 图表和明细使用同一设备颜色，避免后端返回顺序变化后颜色错位。 */
const deviceColorVariable = (deviceType: string): string => ({
  DESKTOP: '--accent', MOBILE: '--success', TABLET: '--warning', UNKNOWN: '--text-muted',
}[deviceType] ?? '--text-muted');

/** 操作标签只按用途区分颜色，不把操作记录误标为成功或失败状态。 */
const eventTone = (eventType: AnalyticsEventType): string => {
  if (eventType === 'LOGIN' || eventType === 'LOGOUT') return 'session';
  if (eventType === 'RECHARGE_PAID') return 'payment';
  if (eventType === 'ADMIN_MODEL_CHANGED') return 'admin';
  if (eventType === 'APP_VISIT') return 'visit';
  return 'usage';
};

/** 页内定位不更改路由，避免一次滚动被访问采集当成页面进入。 */
const scrollToSection = (section: 'analytics-trends' | 'analytics-ranking' | 'analytics-operations'): void => {
  document.getElementById(section)?.scrollIntoView({
    behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth',
    block: 'start',
  });
};

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
  const error = analyticsQueryError(range.value, dateRange.value, normalizedAccountId, {
    email: email.value, displayName: displayName.value,
  });
  if (error) {
    ElMessage.warning(error);
    return undefined;
  }
  const account: AnalyticsAccountFilters = {
    ...(normalizedAccountId ? { userId: normalizedAccountId } : {}),
    ...(email.value.trim() ? { email: email.value.trim() } : {}),
    ...(displayName.value.trim() ? { displayName: displayName.value.trim() } : {}),
  };
  if (range.value === 'CUSTOM') {
    return {
      range: range.value,
      fromDate: dateRange.value?.[0],
      toDate: dateRange.value?.[1],
      ...account,
    };
  }
  return {
    range: range.value,
    ...account,
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
      appliedFilters.value,
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

/** 投递状态与统计查询分别处理；故障提示不能把成功的仪表盘误标为查询失败。 */
const loadDeliveryStatus = async (): Promise<void> => {
  const requestId = ++deliveryRequestId;
  try {
    const status = await getAnalyticsDeliveryStatus();
    if (requestId !== deliveryRequestId) return;
    deliveryStatus.value = status;
    deliveryError.value = undefined;
  } catch (error: unknown) {
    if (requestId !== deliveryRequestId) return;
    deliveryError.value = getApiErrorMessage(error);
  }
};

const refresh = async (): Promise<void> => {
  if (loading.value) return;
  const query = makeQuery();
  if (!query) return;
  void loadDeliveryStatus();
  const submittedFilters = {
    ...(query.userId ? { userId: query.userId } : {}),
    ...(query.email ? { email: query.email } : {}),
    ...(query.displayName ? { displayName: query.displayName } : {}),
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
  animation: !window.matchMedia('(prefers-reduced-motion: reduce)').matches,
  animationDuration: 320,
  textStyle: { fontFamily: getComputedStyle(document.documentElement).fontFamily, color: chartColor('--text-secondary') },
  tooltip: {
    trigger: 'axis', confine: true,
    backgroundColor: chartColor('--glass-fallback'), borderColor: chartColor('--accent-border'),
    textStyle: { color: chartColor('--text-primary'), fontSize: 12 },
    padding: [10, 14], axisPointer: { type: 'line', lineStyle: { color: chartColor('--text-muted'), type: 'dashed' } },
  },
  grid: { left: 4, right: 12, top: 46, bottom: 8, containLabel: true },
});

const dailyOption = (data: AnalyticsDashboard): EChartsOption => ({
  ...options(),
  color: [chartColor('--accent'), chartColor('--success'), chartColor('--warning'), chartColor('--pink'), chartColor('--text-muted')],
  legend: { type: 'scroll', top: 0, left: 0, itemWidth: 14, itemHeight: 8, itemGap: 20, textStyle: { color: chartColor('--text-secondary'), fontSize: 11 } },
  xAxis: {
    type: 'category',
    boundaryGap: false,
    data: data.dailyMetrics.map((item) => item.date),
    axisLabel: { color: chartColor('--text-muted'), margin: 14, hideOverlap: true },
    axisTick: { show: false },
    axisLine: { lineStyle: { color: chartColor('--glass-border') } },
  },
  yAxis: { type: 'value', minInterval: 1, axisLabel: { color: chartColor('--text-muted') }, splitLine: { lineStyle: { color: chartColor('--text-muted'), opacity: 0.14, type: 'dashed' } } },
  series: dailyMetricSeries(data.dailyMetrics).map((series, index) => ({
    ...series,
    lineStyle: { width: index === 0 ? 3 : 2 },
    emphasis: { focus: 'series' as const },
    ...(index === 0 ? { areaStyle: { opacity: 0.05 } } : {}),
  })),
});

const hourlyOption = (data: AnalyticsDashboard): EChartsOption => ({
  ...options(),
  grid: { left: 4, right: 8, top: 18, bottom: 8, containLabel: true },
  color: [chartColor('--accent')],
  xAxis: { type: 'category', data: data.hourlyUsage.map((item) => `${String(item.hour).padStart(2, '0')}:00`), axisLabel: { color: chartColor('--text-muted'), interval: 5, margin: 14 }, axisTick: { show: false }, axisLine: { lineStyle: { color: chartColor('--glass-border') } } },
  yAxis: { type: 'value', minInterval: 1, axisLabel: { color: chartColor('--text-muted') }, splitLine: { lineStyle: { color: chartColor('--text-muted'), opacity: 0.14, type: 'dashed' } } },
  series: [{ name: '关键操作', type: 'bar', barMaxWidth: 18, data: data.hourlyUsage.map((item) => ({
    value: item.operationCount,
    itemStyle: { opacity: item.hour === activeHour.value?.hour ? 1 : 0.4 },
  })), itemStyle: { borderRadius: [4, 4, 0, 0] }, emphasis: { itemStyle: { opacity: 1 } } }],
});

const monthlyOption = (data: AnalyticsDashboard): EChartsOption => ({
  ...options(),
  grid: { left: 4, right: 8, top: 20, bottom: 8, containLabel: true },
  color: [chartColor('--accent')],
  xAxis: { type: 'category', data: data.monthlyUsage.map((item) => item.month), axisLabel: { color: chartColor('--text-muted'), margin: 14, hideOverlap: true }, axisTick: { show: false }, axisLine: { lineStyle: { color: chartColor('--glass-border') } } },
  yAxis: { type: 'value', minInterval: 1, axisLabel: { color: chartColor('--text-muted') }, splitLine: { lineStyle: { color: chartColor('--text-muted'), opacity: 0.14, type: 'dashed' } } },
  series: [{ name: '关键操作', type: 'bar', barMaxWidth: 24, data: data.monthlyUsage.map((item) => ({
    value: item.operationCount,
    itemStyle: { opacity: item.month === activeMonth.value?.month ? 1 : 0.45 },
  })), itemStyle: { borderRadius: [5, 5, 0, 0] }, emphasis: { itemStyle: { opacity: 1 } } }],
});

const deviceOption = (data: AnalyticsDashboard): EChartsOption => ({
  ...options(),
  color: [chartColor('--accent'), chartColor('--success'), chartColor('--warning'), chartColor('--text-muted')],
  tooltip: { trigger: 'item', confine: true, formatter: '{b}: {c} 次登录（{d}%）', backgroundColor: chartColor('--glass-fallback'), borderColor: chartColor('--accent-border'), textStyle: { color: chartColor('--text-primary'), fontSize: 12 } },
  legend: { type: 'scroll', bottom: 0, icon: 'circle', itemWidth: 7, itemHeight: 7, textStyle: { color: chartColor('--text-secondary'), fontSize: 11 } },
  series: [{
    name: '登录设备',
    type: 'pie',
    radius: ['60%', '76%'],
    center: ['50%', '43%'],
    avoidLabelOverlap: true,
    itemStyle: { borderColor: chartColor('--glass-fallback'), borderWidth: 3, borderRadius: 5 },
    label: { show: false },
    data: data.deviceDistribution.map((item) => ({ name: deviceLabel(item.deviceType), value: item.loginCount, itemStyle: { color: chartColor(deviceColorVariable(item.deviceType)) } })),
  }],
});

/** 功能计数沿用同一统计时区、账号条件和日期序列，颜色从当前产品主题读取。 */
const featureUsageOption = (data: AnalyticsDashboard): EChartsOption => ({
  ...options(),
  color: [chartColor('--accent'), chartColor('--success')],
  tooltip: { trigger: 'axis', confine: true, backgroundColor: chartColor('--glass-fallback'),
    borderColor: chartColor('--accent-border'), textStyle: { color: chartColor('--text-primary') } },
  legend: { top: 0, type: 'scroll', textStyle: { color: chartColor('--text-secondary') } },
  grid: { left: 12, right: 20, top: 48, bottom: 28, containLabel: true },
  xAxis: { type: 'category', data: data.dailyMetrics.map((item) => item.date),
    axisLabel: { color: chartColor('--text-secondary') }, axisLine: { lineStyle: { color: chartColor('--glass-border') } } },
  yAxis: { type: 'value', minInterval: 1, axisLabel: { color: chartColor('--text-secondary') },
    splitLine: { lineStyle: { color: chartColor('--glass-border-subtle'), type: 'dashed' } } },
  series: featureUsageSeries(data.dailyMetrics),
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
    ['featureUsage', featureUsageChart.value, featureUsageOption(data)],
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
  // 只轮询轻量实例状态；后台补写不会触发全量统计查询或额外页面访问事件。
  deliveryTimer = window.setInterval(() => { void loadDeliveryStatus(); }, 10_000);
  void refresh();
});

onBeforeUnmount(() => {
  deliveryRequestId += 1;
  if (deliveryTimer !== undefined) window.clearInterval(deliveryTimer);
  window.removeEventListener('resize', resizeCharts);
  charts.forEach((chart) => chart.dispose());
  charts.clear();
});
</script>

<template>
  <ElConfigProvider :locale="zhCn">
  <div class="admin-analytics">
    <header class="analytics-heading">
      <div class="heading-copy">
        <p class="eyebrow"><span class="eyebrow-mark" aria-hidden="true"></span> 平台运营 <span class="eyebrow-divider">/</span> 统计日志</p>
        <h1>使用与访问</h1>
        <p>按登录账号汇总访问、关键操作与所在地审计记录。</p>
      </div>
      <div class="heading-side">
        <span class="admin-badge"><ElIcon aria-hidden="true"><Lock /></ElIcon> 平台管理员视图</span>
        <nav v-if="dashboard" class="section-links" aria-label="统计页导航">
          <a href="#analytics-trends" @click.prevent="scrollToSection('analytics-trends')">使用趋势</a><span aria-hidden="true">/</span>
          <a href="#analytics-ranking" @click.prevent="scrollToSection('analytics-ranking')">账号排行</a><span aria-hidden="true">/</span>
          <a href="#analytics-operations" @click.prevent="scrollToSection('analytics-operations')">操作日志</a>
        </nav>
      </div>
    </header>
    <ElAlert v-if="deliveryAlert" class="delivery-notice" data-testid="analytics-delivery-alert"
      :type="deliveryAlert.type" :title="deliveryAlert.title" :description="deliveryAlert.description"
      :closable="false" show-icon />
    <div class="filters" role="search" aria-label="统计筛选条件" @keyup.enter.capture="refresh">
      <div class="filter-intro">
        <span class="filter-icon"><ElIcon aria-hidden="true"><Filter /></ElIcon></span>
        <div><strong>数据范围</strong><small>设置条件后查询</small></div>
      </div>
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
          range-separator="至"
          class="custom-dates"
          popper-class="analytics-date-range-popper"
        />
      </div>
      <div class="filter-field filter-field--account">
        <span class="filter-label">登录账号 ID</span>
        <ElInput
          v-model="accountId"
          clearable
          class="account-filter"
          aria-label="按登录账号 ID 筛选"
          placeholder="账号 ID（可选）"
        />
      </div>
      <div class="filter-field filter-field--email">
        <span class="filter-label">登录邮箱</span>
        <ElInput v-model="email" clearable :maxlength="320" aria-label="按登录邮箱筛选" placeholder="邮箱关键词（可选）" />
      </div>
      <div class="filter-field filter-field--name">
        <span class="filter-label">显示名称</span>
        <ElInput v-model="displayName" clearable :maxlength="80" aria-label="按显示名称筛选" placeholder="名称关键词（可选）" />
      </div>
      <ElButton type="primary" :icon="Search" :loading="loading" @click="refresh">查询</ElButton>
    </div>

    <div v-if="loading && !dashboard" class="loading-state" role="status">
      <p>正在汇总账号事件…</p><ElSkeleton :rows="6" animated />
    </div>
    <ElEmpty v-else-if="!dashboard" description="选择时间范围并查询统计数据。" />

    <template v-else>
      <div class="query-summary" aria-label="已生效的统计条件">
        <span class="query-period"><ElIcon aria-hidden="true"><Calendar /></ElIcon>{{ dashboard.period.fromDate }} — {{ dashboard.period.toDateInclusive }}</span>
        <span><ElIcon aria-hidden="true"><Clock /></ElIcon>统计时区：{{ dashboard.period.zoneId }}</span>
        <span class="query-account"><ElIcon aria-hidden="true"><User /></ElIcon>{{ appliedAccountLabel }}</span>
      </div>
      <section class="metric-strip" aria-label="统计摘要">
        <article class="metric metric-primary">
          <div class="metric-label"><span>活跃账号</span><ElIcon aria-hidden="true"><UserFilled /></ElIcon></div>
          <div class="metric-value"><strong>{{ dashboard.activeUserCount.toLocaleString() }}</strong><span>个账号</span></div>
          <small>所选范围内任一日有登录或操作</small>
        </article>
        <article class="metric">
          <div class="metric-label"><span>注册账号</span><ElIcon aria-hidden="true"><User /></ElIcon></div>
          <strong>{{ dashboard.registeredAccountCount.toLocaleString() }}</strong>
          <small>当前存量，包含平台管理员</small>
        </article>
        <article class="metric">
          <div class="metric-label"><span>新增账号</span><ElIcon aria-hidden="true"><CirclePlus /></ElIcon></div>
          <strong>{{ dashboard.newAccountCount.toLocaleString() }}</strong>
          <small>按账号创建时间</small>
        </article>
        <article class="metric">
          <div class="metric-label"><span>页面访问</span><ElIcon aria-hidden="true"><Mouse /></ElIcon></div>
          <strong>{{ dashboard.accessCount.toLocaleString() }}</strong>
          <small>页面进入次数，刷新会增加</small>
        </article>
        <article class="metric">
          <div class="metric-label"><span>去重访问账号</span><ElIcon aria-hidden="true"><View /></ElIcon></div>
          <strong>{{ dashboard.uniqueVisitorCount.toLocaleString() }}</strong>
          <small>所选区间内按账号去重</small>
        </article>
        <article class="metric">
          <div class="metric-label"><span>实际使用账号</span><ElIcon aria-hidden="true"><MagicStick /></ElIcon></div>
          <strong>{{ dashboard.actualUserCount.toLocaleString() }}</strong>
          <small>优化、上下文、导出或充值关键操作</small>
        </article>
        <article class="metric">
          <div class="metric-label"><span>平均日活</span><ElIcon aria-hidden="true"><DataLine /></ElIcon></div>
          <strong>{{ dashboard.averageDailyActiveUsers.toLocaleString() }}</strong>
          <small>所选自然日均值，含零活跃日</small>
        </article>
      </section>

      <section class="feature-usage-strip" aria-label="增强功能使用次数">
        <article class="metric" data-testid="direct-enhancement-count">
          <div class="metric-label"><span>直接增强提交次数</span><ElIcon aria-hidden="true"><MagicStick /></ElIcon></div>
          <strong>{{ dashboard.directEnhancementCount.toLocaleString() }}</strong>
          <small>包含生成失败及再次增强的提交尝试</small>
        </article>
        <article class="metric" data-testid="plan-completed-count">
          <div class="metric-label"><span>Plan 完成次数</span><ElIcon aria-hidden="true"><List /></ElIcon></div>
          <strong>{{ dashboard.planCompletedCount.toLocaleString() }}</strong>
          <small>成功生成并保存历史，每个计划计一次</small>
        </article>
      </section>
      <p class="panel-caption feature-usage-note">细分次数自本功能启用后记录；旧通用优化记录无法区分这两种使用路径。</p>

      <div id="analytics-trends" class="section-heading"><h2>使用趋势</h2><span>访问、活跃与使用习惯</span></div>
      <section class="charts-grid" aria-label="使用统计图表">
        <article class="chart-panel feature-usage-panel">
          <div class="panel-heading">
            <div class="panel-title"><span class="panel-icon"><ElIcon aria-hidden="true"><MagicStick /></ElIcon></span><div><h2>直接增强与 Plan 使用</h2><span class="panel-kicker">直接增强按提交日，Plan 按完成日汇总</span></div></div>
            <span class="panel-badge">按日汇总</span>
          </div>
          <div v-show="hasFeatureUsageData" ref="featureUsageChart" class="chart chart-tall" role="img" aria-label="直接增强提交次数与 Plan 完成次数每日折线图"></div>
          <div v-if="!hasFeatureUsageData" class="chart-empty chart-tall">所选范围内暂无增强功能细分记录。</div>
        </article>
        <article class="chart-panel chart-wide">
          <div class="panel-heading">
            <div class="panel-title"><span class="panel-icon"><ElIcon aria-hidden="true"><TrendCharts /></ElIcon></span><div><h2>每日访问与活跃</h2><span class="panel-kicker">账号行为的每日变化</span></div></div>
            <span class="panel-badge">按日汇总</span>
          </div>
          <div v-show="hasDailyData" ref="dailyChart" class="chart chart-tall" role="img" aria-label="每日访问、去重访问、活跃、实际使用与新增账号折线图"></div>
          <div v-if="!hasDailyData" class="chart-empty chart-tall">所选范围内暂无每日访问数据。</div>
        </article>

        <article class="chart-panel hourly-panel">
          <div class="panel-heading">
            <div class="panel-title"><span class="panel-icon"><ElIcon aria-hidden="true"><Clock /></ElIcon></span><div><h2>高频使用时段</h2><span class="panel-kicker">一天中的关键操作分布</span></div></div>
            <ElTag v-if="activeHour" size="small" effect="plain">峰值 {{ String(activeHour.hour).padStart(2, '0') }}:00</ElTag>
          </div>
          <div v-if="activeHour" class="peak-summary"><div><strong>{{ String(activeHour.hour).padStart(2, '0') }}:00</strong><span>最活跃时段</span></div><span>{{ activeHour.operationCount.toLocaleString() }} 次关键操作</span></div>
          <div v-show="activeHour" ref="hourlyChart" class="chart chart-hours" role="img" aria-label="按小时汇总的关键操作柱状图"></div>
          <div v-if="!activeHour" class="chart-empty chart-tall">所选范围内暂无时段统计。</div>
        </article>

        <article class="chart-panel">
          <div class="panel-heading">
            <div class="panel-title"><span class="panel-icon"><ElIcon aria-hidden="true"><Calendar /></ElIcon></span><div><h2>高频月份 · 近 12 个月</h2><span class="panel-kicker">长期使用分布</span></div></div>
            <ElTag v-if="activeMonth" size="small" effect="plain">峰值 {{ activeMonth.month }}</ElTag>
          </div>
          <p class="panel-caption">固定显示截至今天的近 12 个月，沿用账号筛选。</p>
          <div v-show="activeMonth" ref="monthlyChart" class="chart chart-medium" role="img" aria-label="按月份汇总的关键操作柱状图"></div>
          <div v-if="!activeMonth" class="chart-empty">近 12 个月暂无使用数据。</div>
        </article>

        <article class="chart-panel device-panel">
          <div class="panel-heading">
            <div class="panel-title"><span class="panel-icon"><ElIcon aria-hidden="true"><Monitor /></ElIcon></span><div><h2>登录设备</h2><span class="panel-kicker">按登录次数汇总</span></div></div>
          </div>
          <div v-show="dashboard.deviceDistribution.length" class="device-chart-wrap">
            <div ref="deviceChart" class="chart chart-medium" role="img" aria-label="手机、平板、电脑和未知设备登录分布图"></div>
            <div class="device-chart-total" aria-hidden="true"><strong>{{ totalLogins.toLocaleString() }}</strong><span>次登录 · 全部设备</span></div>
          </div>
          <div v-if="!dashboard.deviceDistribution.length" class="chart-empty">所选范围内暂无登录设备数据。</div>
          <ul v-else class="device-summary" aria-label="各设备登录次数与去重账号">
            <li v-for="item in dashboard.deviceDistribution" :key="item.deviceType">
              <span class="device-name"><i :style="{ background: `var(${deviceColorVariable(item.deviceType)})` }" aria-hidden="true"></i>{{ deviceLabel(item.deviceType) }}</span>
              <span><strong>{{ item.loginCount.toLocaleString() }}</strong> 次 · {{ item.uniqueUsers.toLocaleString() }} 个账号</span>
            </li>
          </ul>
          <p v-if="dashboard.deviceDistribution.length" class="panel-caption device-note">账号在各设备内去重，跨设备不可相加。</p>
        </article>

        <article class="chart-panel recharge-panel">
          <div class="panel-heading">
            <div class="panel-title"><span class="panel-icon"><ElIcon aria-hidden="true"><Wallet /></ElIcon></span><div><h2>每日充值套餐</h2><span class="panel-kicker">成功支付记录</span></div></div>
            <span v-if="!dashboard.rechargeStatisticsAvailable" class="panel-badge">待接入</span>
            <ElSelect v-if="rechargeCurrencies.length > 1" v-model="rechargeCurrency" aria-label="充值统计币种" class="currency-select">
              <ElOption v-for="currency in rechargeCurrencies" :key="currency" :label="currency" :value="currency" />
            </ElSelect>
          </div>
          <p v-if="dashboard.rechargeStatisticsAvailable && rechargeCurrencies.length" class="panel-caption">{{ rechargeCurrency }} · 最小货币单位 · 成功支付 {{ selectedRechargeCount }} 笔</p>
          <div v-show="dashboard.rechargeStatisticsAvailable && dashboard.rechargeByDay.length" ref="rechargeChart" class="chart chart-medium" role="img" aria-label="每日充值套餐金额堆叠柱状图"></div>
          <div v-if="!dashboard.rechargeStatisticsAvailable" class="chart-empty recharge-placeholder"><span class="placeholder-icon"><ElIcon aria-hidden="true"><Wallet /></ElIcon></span><strong>充值图表尚无数据源</strong><span>套餐与支付功能接入后显示每日统计。</span><small>接入后可按日期与币种查看</small></div>
          <div v-else-if="!dashboard.rechargeByDay.length" class="chart-empty">所选范围内暂无充值记录。</div>
        </article>
      </section>

      <div class="section-heading"><h2>账号与审计</h2><span>定位高频用户，追踪关键操作</span></div>
      <section class="data-grid" aria-label="账号排行与操作明细">
        <article id="analytics-ranking" class="table-panel ranking-panel">
          <div class="panel-heading">
            <div class="panel-title"><span class="panel-icon"><ElIcon aria-hidden="true"><Trophy /></ElIcon></span><div><h2>使用频率排行</h2><span class="panel-kicker">按关键操作次数排序 · 最多 20 位</span></div></div>
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
                placeholder="选择排行日期"
                class="rank-date"
              />
              <ElButton :loading="rankingLoading" @click="loadRanking()">更新排行</ElButton>
            </div>
          </div>
          <p class="panel-caption">按账号 ID 汇总关键操作；管理员账号计入。<span class="table-scroll-hint">左右滑动表格可查看完整字段。</span></p>
          <p v-if="ranking" class="panel-caption">实际排行周期：{{ ranking.period.fromDate }} — {{ ranking.period.toDateInclusive }} · {{ ranking.period.zoneId }} · 活跃天数包含仅访问的日期</p>
          <ElAlert v-if="rankingError" type="error" :closable="false" :title="rankingError" />
          <ElTable v-else v-loading="rankingLoading" :data="ranking?.items ?? []" stripe :max-height="440" empty-text="所选周期内没有关键使用操作。">
            <ElTableColumn label="排名" type="index" width="72" align="center">
              <template #default="scope"><span class="rank-number" :class="{ 'rank-number--top': scope.$index < 3 }">{{ String(scope.$index + 1).padStart(2, '0') }}</span></template>
            </ElTableColumn>
            <ElTableColumn label="账号 ID" min-width="280" show-overflow-tooltip>
              <template #default="scope"><code>{{ scope.row.userId }}</code></template>
            </ElTableColumn>
            <ElTableColumn prop="displayName" label="显示名称" min-width="180" show-overflow-tooltip />
            <ElTableColumn prop="operationCount" label="关键操作" min-width="120" align="right">
              <template #default="scope"><strong class="operation-count">{{ scope.row.operationCount.toLocaleString() }}</strong></template>
            </ElTableColumn>
            <ElTableColumn prop="loginCount" label="登录次数" min-width="110" align="right" />
            <ElTableColumn prop="activeDays" label="活跃天数" min-width="110" align="right" />
          </ElTable>
        </article>

        <article id="analytics-operations" class="table-panel operations-panel">
          <div class="panel-heading operation-heading">
            <div class="panel-title"><span class="panel-icon"><ElIcon aria-hidden="true"><List /></ElIcon></span><div><h2>关键操作日志 <span v-if="operationPage" class="record-count">{{ operationPage.total.toLocaleString() }}</span></h2><span class="panel-kicker">登录、访问与关键业务操作</span></div></div>
            <div class="operation-controls">
              <ElSelect v-model="eventFilter" clearable aria-label="按操作类型筛选" placeholder="全部操作" class="event-select">
                <ElOption v-for="item in eventTypes" :key="item.value" :label="item.label" :value="item.value" />
              </ElSelect>
              <ElButton :icon="Filter" :loading="operationsLoading" :disabled="loading" @click="applyLogFilter">筛选日志</ElButton>
            </div>
          </div>
          <p class="panel-caption">{{ dashboard.period.fromDate }} — {{ dashboard.period.toDateInclusive }} · {{ dashboard.period.zoneId }} · {{ appliedFilters.eventType ? eventLabel(appliedFilters.eventType) : '全部操作' }}<span class="table-scroll-hint">左右滑动表格可查看完整字段。</span></p>
          <ElAlert v-if="operationsError" type="error" :closable="false" :title="operationsError" />
          <!-- @vue-generic {AnalyticsOperationLog} -->
          <ElTable v-else v-loading="operationsLoading" :data="operationPage?.records ?? []" row-key="eventId" stripe :max-height="560" empty-text="所选范围内没有关键操作日志。">
            <ElTableColumn label="发生时间" min-width="185" show-overflow-tooltip>
              <template #default="scope"><time class="log-time">{{ formatAnalyticsTime(scope.row.occurredAt, dashboard.period.zoneId) }}</time></template>
            </ElTableColumn>
            <ElTableColumn label="账号 ID" min-width="280" show-overflow-tooltip>
              <template #default="scope"><code>{{ scope.row.userId }}</code></template>
            </ElTableColumn>
            <ElTableColumn label="操作" width="140">
              <template #default="scope"><span class="event-badge" :class="`event-badge--${eventTone(scope.row.eventType)}`"><i aria-hidden="true"></i>{{ eventLabel(scope.row.eventType) }}</span></template>
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
  </ElConfigProvider>
</template>

<style scoped>
.delivery-notice { margin-bottom: var(--analytics-gap, 16px); }
.admin-analytics {
  --analytics-gap: 20px;
  --analytics-surface: color-mix(in srgb, var(--glass-fallback) 86%, var(--bg-base));
  --analytics-line: color-mix(in srgb, var(--text-muted) 18%, transparent);
  width: 100%;
  min-width: 0;
  padding: 30px clamp(16px, 1.5vw, 28px) 56px;
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
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 20px;
  margin-bottom: 28px;
}

.heading-copy h1 {
  margin: 10px 0 8px;
  font-family: var(--font-display);
  font-size: clamp(28px, 2.4vw, 38px);
  font-weight: 600;
  letter-spacing: -0.8px;
}

.heading-copy > p:last-child {
  margin: 0;
  color: var(--text-secondary);
  font-size: 14px;
  line-height: 1.7;
}

.eyebrow {
  display: flex;
  align-items: center;
  gap: 9px;
  margin: 0;
  color: var(--accent);
  font-family: var(--font-mono);
  font-size: 10px;
  font-weight: 500;
  letter-spacing: 1.1px;
}

.eyebrow-mark { width: 7px; height: 7px; border-radius: 2px; background: var(--accent); }
.eyebrow-divider { margin: 0 2px; color: var(--text-muted); }
.heading-side { display: grid; justify-items: end; gap: 15px; }
.admin-badge { display: inline-flex; align-items: center; gap: 7px; padding: 6px 11px; border: 1px solid var(--analytics-line); border-radius: var(--radius-pill); color: var(--text-secondary); font-size: 11px; background: var(--glass-bg); }
.admin-badge .el-icon { color: var(--accent); font-size: 13px; }
.section-links { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; font-size: 12px; }
.section-links a { color: var(--text-secondary); text-decoration: none; text-underline-offset: 5px; }
.section-links a:hover { color: var(--accent); text-decoration: underline; }
.section-links > span { color: var(--analytics-line); }

.filters {
  align-items: flex-end;
  justify-content: flex-start;
  flex-wrap: wrap;
  margin-bottom: var(--analytics-gap);
  padding: 20px;
  border: 1px solid var(--analytics-line);
  border-radius: var(--radius-md);
  background: var(--analytics-surface);
}

.filter-intro { display: flex; flex: 0 0 154px; align-items: center; align-self: center; gap: 12px; margin-right: 4px; padding-right: 20px; border-right: 1px solid var(--analytics-line); }
.filter-icon { display: grid; width: 34px; height: 34px; flex-shrink: 0; place-items: center; border-radius: var(--radius-sm); background: var(--accent-soft); color: var(--accent); font-size: 17px; }
.filter-intro strong, .filter-intro small { display: block; }
.filter-intro strong { font-size: 13px; font-weight: 600; }
.filter-intro small { margin-top: 2px; color: var(--text-muted); font-size: 11px; }
.filter-field { display: grid; flex: 0 0 160px; min-width: 0; gap: 7px; }
.filter-field--dates { flex: 1 1 300px; }
.filter-field--account { flex: 1 1 240px; }
.filter-field--email { flex: 1 1 220px; }
.filter-field--name { flex: 1 1 180px; }
.filter-label { color: var(--text-secondary); font-size: 12px; font-weight: 500; }
.range-select, .account-filter, .custom-dates { width: 100%; min-width: 0; }
.filters :deep(.el-date-editor) { width: 100%; min-width: 0; }
.filters > .el-button { min-width: 104px; min-height: 36px; }
.filters :deep(.el-input__wrapper), .filters :deep(.el-select__wrapper) { min-height: 36px; }
.event-select { width: 168px; }
.ranking-controls { display: flex; min-width: 0; align-items: center; flex-wrap: wrap; gap: 8px; }
.rank-period-select { width: 96px; }
.rank-date.el-date-editor { width: 160px; }

.query-summary { display: flex; flex-wrap: wrap; gap: 8px 22px; margin: 0 2px 18px; color: var(--text-muted); font-size: 11px; line-height: 1.7; overflow-wrap: anywhere; }
.query-summary > span { display: inline-flex; align-items: center; gap: 7px; min-width: 0; }
.query-summary .el-icon { flex-shrink: 0; font-size: 13px; }
.query-account { margin-left: auto; }
.query-period { color: var(--text-primary); font-family: var(--font-mono); font-variant-numeric: tabular-nums; }
.operation-controls { display: flex; flex-wrap: wrap; gap: 8px; min-width: 0; }
.currency-select { width: 104px; }
.device-summary { display: grid; margin: 16px 0 0; padding: 0; list-style: none; color: var(--text-secondary); font-size: 12px; }
.device-summary li { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 4px 12px; padding: 8px 0; border-top: 1px solid var(--analytics-line); font-variant-numeric: tabular-nums; }
.device-summary strong { color: var(--text-primary); font-weight: 600; }
.device-name { display: inline-flex; align-items: center; gap: 8px; }
.device-name i { width: 7px; height: 7px; border-radius: 50%; }
.device-chart-wrap { position: relative; }
.device-chart-total { position: absolute; top: 43%; left: 50%; display: grid; max-width: 45%; gap: 2px; text-align: center; transform: translate(-50%, -50%); pointer-events: none; }
.device-chart-total strong { color: var(--text-primary); font-family: var(--font-mono); font-size: clamp(18px, 1.6vw, 26px); font-weight: 500; line-height: 1.25; overflow-wrap: anywhere; }
.device-chart-total span { color: var(--text-muted); font-size: 10px; }
.device-note { margin-top: 12px; margin-bottom: 0; }
.recharge-placeholder { min-height: 330px; align-content: center; gap: 10px; margin-top: 20px; border: 1px dashed var(--analytics-line); border-radius: var(--radius-sm); background: var(--glass-bg-subtle); }
.recharge-placeholder strong { color: var(--text-primary); font-weight: 500; }
.recharge-placeholder small { color: var(--text-muted); font-size: 11px; }
.placeholder-icon { display: grid; width: 52px; height: 52px; margin-bottom: 6px; place-items: center; border: 1px solid var(--analytics-line); border-radius: var(--radius-md); color: var(--text-muted); background: var(--analytics-surface); font-size: 23px; }

.chart-empty {
  display: grid;
  min-height: 140px;
  place-items: center;
  color: var(--text-secondary);
  font-size: 13px;
}
.loading-state { padding: 28px; border: 1px solid var(--analytics-line); border-radius: var(--radius-md); background: var(--analytics-surface); }
.loading-state p { margin: 0 0 24px; color: var(--text-secondary); font-size: 13px; }

.metric-strip {
  display: grid;
  grid-template-columns: repeat(8, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 30px;
}

.feature-usage-strip { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.feature-usage-strip .metric { min-height: 132px; }
.feature-usage-note { margin: 12px 0 24px; }
.feature-usage-panel { grid-column: 1 / -1; }

.metric {
  display: flex;
  min-width: 0;
  min-height: 158px;
  flex-direction: column;
  justify-content: space-between;
  padding: 18px;
  border: 1px solid var(--analytics-line);
  border-radius: var(--radius-md);
  background: var(--analytics-surface);
}

.metric-label,
.metric small {
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.6;
}
.metric-label { display: flex; justify-content: space-between; align-items: center; gap: 5px; font-weight: 500; }
.metric-label .el-icon { flex-shrink: 0; color: var(--text-muted); font-size: 16px; }
.metric small { font-size: 11px; min-height: 35px; color: var(--text-muted); }

.metric strong {
  margin: 8px 0;
  font-family: var(--font-display);
  font-size: clamp(25px, 1.85vw, 34px);
  font-weight: 600;
  line-height: 1.2;
  letter-spacing: -0.7px;
  font-variant-numeric: tabular-nums;
  overflow-wrap: anywhere;
}

.metric-primary {
  grid-column: span 2;
  border-color: var(--accent-border);
  background: linear-gradient(115deg, var(--accent-soft), var(--analytics-surface));
}
.metric-primary .metric-label { color: var(--accent); }
.metric-primary .metric-label .el-icon { display: grid; width: 26px; height: 26px; place-items: center; border-radius: 8px; color: var(--accent); background: var(--accent-soft); }
.metric-value { display: flex; align-items: baseline; flex-wrap: wrap; gap: 8px; min-width: 0; }
.metric-value strong { font-size: clamp(34px, 2.6vw, 48px); letter-spacing: -1.3px; }
.metric-value > span { color: var(--text-muted); font-size: 11px; }
.metric-primary small { min-height: auto; }
.section-heading { display: flex; align-items: baseline; flex-wrap: wrap; gap: 12px; margin: 28px 0 14px; scroll-margin-top: 140px; }
.section-heading h2 { margin: 0; color: var(--text-primary); font-size: 15px; font-weight: 600; }
.section-heading > span { color: var(--text-muted); font-size: 11px; }

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
  padding: 22px;
  border: 1px solid var(--analytics-line);
  border-radius: var(--radius-md);
  background: var(--analytics-surface);
}

.chart-wide { grid-column: span 2; }

.panel-heading {
  min-height: 40px;
  flex-wrap: wrap;
  margin-bottom: 20px;
}

.panel-title { display: flex; align-items: center; min-width: 0; gap: 11px; }
.panel-title > div { min-width: 0; }
.panel-icon { display: grid; width: 36px; height: 36px; flex-shrink: 0; place-items: center; color: var(--accent); background: var(--accent-soft); border-radius: var(--radius-sm); font-size: 18px; }
.panel-kicker { display: block; margin-top: 3px; color: var(--text-muted); font-size: 11px; font-weight: 400; }
.panel-badge { padding: 3px 8px; border: 1px solid var(--analytics-line); border-radius: 6px; color: var(--text-muted); font-size: 10px; white-space: nowrap; }

.panel-heading h2 {
  margin: 0;
  color: var(--text-primary);
  font-size: 15px;
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
.chart-tall { height: 304px; }
.chart-hours { height: 208px; }
.chart-medium { height: 250px; }
.chart-empty { min-height: 250px; padding: 20px; border-radius: var(--radius-sm); text-align: center; background: var(--glass-bg-subtle); }
.peak-summary { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px; min-height: 84px; margin: -4px 0 16px; padding: 0 0 16px; border-bottom: 1px solid var(--analytics-line); }
.peak-summary > div { display: grid; gap: 4px; }
.peak-summary strong { font-family: var(--font-mono); font-size: 30px; font-weight: 500; line-height: 1.15; letter-spacing: -1px; }
.peak-summary span { color: var(--text-muted); font-size: 11px; }
.peak-summary > span { padding: 5px 9px; border-radius: 6px; color: var(--accent); background: var(--accent-soft); font-variant-numeric: tabular-nums; }

.table-panel { overflow: hidden; scroll-margin-top: 140px; }
.table-panel :deep(.el-table) {
  --el-table-header-bg-color: color-mix(in srgb, var(--text-muted) 7%, var(--analytics-surface));
  --el-table-header-text-color: var(--text-muted);
  --el-table-text-color: var(--text-secondary);
  --el-table-border-color: var(--analytics-line);
  --el-table-tr-bg-color: transparent;
  --el-table-row-hover-bg-color: var(--accent-soft);
  width: 100%;
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}
.table-panel :deep(.el-table th.el-table__cell) { font-size: 11px; font-weight: 500; }
.table-panel :deep(.el-table .el-table__row--striped td.el-table__cell) { background: var(--glass-bg-subtle); }
.table-panel :deep(.el-table__body tr:hover > td.el-table__cell) { background: var(--accent-soft); }
.table-panel :deep(.el-table__cell) { padding: 12px 0; }
.table-panel :deep(.el-table .cell) { padding: 0 14px; }
.table-panel :deep(.el-table__empty-text) { width: 100%; padding: 16px; line-height: 1.7; }
.table-scroll-hint { display: none; margin-left: 12px; }
.pagination-row { flex-wrap: wrap; margin-top: 16px; color: var(--text-secondary); font-size: 13px; }
.pagination-row > span { flex-shrink: 0; }
.pagination-row :deep(.el-pagination) { max-width: 100%; flex-wrap: wrap; gap: 8px; }
code { color: var(--text-secondary); font-family: var(--font-mono); font-size: 11px; }
.rank-number { display: inline-grid; width: 28px; height: 28px; place-items: center; border-radius: 7px; color: var(--text-muted); font-family: var(--font-mono); font-size: 12px; }
.rank-number--top { color: var(--accent); background: var(--accent-soft); font-weight: 600; }
.operation-count { color: var(--text-primary); font-weight: 600; }
.record-count { display: inline-block; margin-left: 6px; padding: 1px 7px; border-radius: 5px; color: var(--accent); background: var(--accent-soft); font-size: 11px; font-variant-numeric: tabular-nums; vertical-align: middle; }
.log-time { font-family: var(--font-mono); font-size: 11px; }
.event-badge { display: inline-flex; align-items: center; gap: 6px; padding: 3px 8px; border-radius: 5px; color: var(--text-secondary); background: var(--glass-bg-strong); font-size: 11px; line-height: 1.6; white-space: nowrap; }
.event-badge i { width: 5px; height: 5px; flex-shrink: 0; border-radius: 50%; background: currentColor; }
.event-badge--usage { color: var(--accent); background: var(--accent-soft); }
.event-badge--session { color: var(--blue); background: var(--blue-soft); }
.event-badge--payment { color: var(--green); background: var(--green-soft); }
.event-badge--admin { color: var(--orange); background: var(--orange-soft); }

@media (max-width: 1599px) {
  .metric-strip { grid-template-columns: repeat(4, minmax(0, 1fr)); }
}

@media (max-width: 1100px) {
  .filter-intro { flex-basis: 100%; border-right: 0; padding: 0 0 14px; border-bottom: 1px solid var(--analytics-line); }
  .filter-intro > div { display: flex; align-items: center; gap: 10px; }
  .filter-intro small { margin: 0; }
  .charts-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .query-account { margin-left: 0; }
  .table-scroll-hint { display: inline; }
}

@media (max-width: 680px) {
  .admin-analytics { --analytics-gap: 12px; padding: 20px 12px calc(40px + env(safe-area-inset-bottom, 0px)); }
  .analytics-heading { gap: 16px; margin-bottom: 20px; }
  .heading-copy > p:last-child { font-size: 12px; }
  .heading-side { display: flex; width: 100%; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 12px; }
  .admin-badge { font-size: 10px; }
  .section-links { font-size: 11px; gap: 10px; }
  .eyebrow { font-size: 9px; letter-spacing: 0.6px; }
  .filters { padding: 14px; }
  .filter-field { flex-basis: 100%; }
  .filters > .el-button { width: 100%; }
  .metric-strip { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .feature-usage-strip { grid-template-columns: minmax(0, 1fr); }
  .metric { min-height: 126px; padding: 14px; }
  .metric-primary { min-height: 140px; }
  .metric strong { margin: 5px 0; }
  .query-summary { gap: 4px; flex-direction: column; }
  .section-heading { margin-top: 24px; }
  .charts-grid { grid-template-columns: minmax(0, 1fr); }
  .chart-wide { grid-column: auto; }
  .chart-panel, .table-panel { padding: 16px 14px; }
  .panel-heading { gap: 10px; margin-bottom: 18px; }
  .panel-icon { width: 30px; height: 30px; font-size: 16px; }
  .panel-title { gap: 8px; }
  .panel-heading h2 { font-size: 14px; }
  .panel-kicker { font-size: 10px; }
  .device-chart-total strong { font-size: 24px; }
  .peak-summary strong { font-size: 28px; }
  .recharge-placeholder { min-height: 240px; }
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

@media (prefers-reduced-motion: reduce) {
  .admin-analytics :deep(.el-skeleton__item) { animation: none; }
}
</style>
