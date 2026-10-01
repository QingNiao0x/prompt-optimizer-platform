import type { BarSeriesOption, LineSeriesOption } from 'echarts/charts';

import type { AnalyticsDailyMetric, AnalyticsRange, AnalyticsRechargeMetric } from '@/types/api';

/** 与后端日桶上限一致；历史数据仍可按多个区间分别查询。 */
export const MAX_ANALYTICS_DAYS = 366;

/** 用 UTC 仅计算日历日差，不使用浏览器时区；同时拒绝自动归一化的非法日期。 */
const calendarDay = (value: string): number | undefined => {
  if (!/^[0-9]{4}-[0-9]{2}-[0-9]{2}$/.test(value) || value.startsWith('0000-')) return undefined;
  const timestamp = Date.parse(`${value}T00:00:00Z`);
  return Number.isFinite(timestamp) && new Date(timestamp).toISOString().slice(0, 10) === value
    ? timestamp / 86_400_000 : undefined;
};

/** 筛选是提交前的草稿，清空日期也属于可预期输入；后端仍执行最终校验。 */
export const analyticsQueryError = (
  range: AnalyticsRange,
  dates: readonly string[] | null,
  accountId: string,
): string | undefined => {
  if (accountId.trim() && !/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(accountId.trim())) {
    return '账号 ID 必须是完整的 UUID。';
  }
  if (range !== 'CUSTOM') return undefined;
  if (dates?.length !== 2 || !dates[0] || !dates[1]) return '请选择完整的自定义开始和结束日期。';
  const from = calendarDay(dates[0]);
  const to = calendarDay(dates[1]);
  if (from === undefined || to === undefined) return '请使用 0001–9999 年之间有效的 YYYY-MM-DD 日期。';
  if (to < from) return '自定义结束日期必须不早于开始日期。';
  if (to - from + 1 > MAX_ANALYTICS_DAYS) return '单次统计范围不能超过 366 个自然日，请分段查询。';
  return undefined;
};

/** 零值补齐的小时、月份不构成峰值；相同正数时保留较早的桶。 */
export const findUsagePeak = <T extends { operationCount: number }>(items: readonly T[]): T | undefined => (
  items.reduce<T | undefined>((peak, item) => item.operationCount > (peak?.operationCount ?? 0) ? item : peak, undefined)
);

/** 后端会补齐零值日桶，不能用数组长度判断有无行为或新增账号。 */
export const hasDailyActivity = (metrics: readonly AnalyticsDailyMetric[]): boolean => metrics.some((item) => (
  item.accessCount > 0 || item.uniqueVisitors > 0 || item.activeUsers > 0 || item.actualUsers > 0 || item.newAccounts > 0
));

/** 五个每日指标使用同一日序列，单日查询必须绘制标记，避免只有一个不可见线段。 */
export const dailyMetricSeries = (metrics: readonly AnalyticsDailyMetric[]): LineSeriesOption[] => {
  const dimensions: Array<[string, keyof Omit<AnalyticsDailyMetric, 'date'>]> = [
    ['页面访问', 'accessCount'], ['去重访问账号', 'uniqueVisitors'], ['活跃账号', 'activeUsers'],
    ['实际使用账号', 'actualUsers'], ['新增账号', 'newAccounts'],
  ];
  return dimensions.map(([name, field]) => ({
    name, type: 'line', smooth: false, showSymbol: metrics.length === 1, symbolSize: 9,
    data: metrics.map((item) => item[field]),
  }));
};

/** 日志始终使用 API 返回的统计时区；无效时间明确提示，不回退到浏览器当地时间。 */
export const formatAnalyticsTime = (value: string, zoneId: string): string => {
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) return '时间无效';
  try {
    const parts = new Intl.DateTimeFormat('en-CA', {
      timeZone: zoneId, year: 'numeric', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
    }).formatToParts(date);
    const part = (type: Intl.DateTimeFormatPartTypes): string => parts.find((item) => item.type === type)?.value ?? '';
    return `${part('year').padStart(4, '0')}-${part('month')}-${part('day')} ${part('hour')}:${part('minute')}:${part('second')}`;
  } catch (error: unknown) {
    if (error instanceof RangeError) return '时间不可用（统计时区无效）';
    throw error;
  }
};

/** 每次只绘制一个币种，套餐按稳定代码分组；同名套餐不会合并，不换算最小货币单位。 */
export const rechargeMetricSeries = (
  records: readonly AnalyticsRechargeMetric[], currency: string, days: readonly string[],
): BarSeriesOption[] => {
  const selected = records.filter((item) => item.currency === currency);
  const plans = new Map(selected.map((item) => [item.planCode, item.planName]));
  return [...plans].map(([code, name]) => ({
    name: `${name} · ${code}`, type: 'bar', stack: `paid-${currency}`,
    data: days.map((date) => selected.filter((item) => item.date === date && item.planCode === code)
      .reduce((total, item) => total + item.amountMinor, 0)),
  }));
};
