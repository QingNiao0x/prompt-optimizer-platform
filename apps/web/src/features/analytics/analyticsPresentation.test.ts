import { describe, expect, it } from 'vitest';

import { analyticsQueryError, dailyMetricSeries, findUsagePeak, formatAnalyticsTime, hasDailyActivity, rechargeMetricSeries } from './analyticsPresentation';

describe('统计筛选与展示口径', () => {
  it('清空日期可校验，非法日期、账号与超长区间被拒绝', () => {
    expect(analyticsQueryError('CUSTOM', null, '')).toContain('请选择完整');
    expect(analyticsQueryError('CUSTOM', [], '')).toContain('请选择完整');
    expect(analyticsQueryError('CUSTOM', ['2024-01-01', '2024-12-31'], '')).toBeUndefined();
    expect(analyticsQueryError('CUSTOM', ['2024-01-01', '2025-01-01'], '')).toContain('366');
    expect(analyticsQueryError('CUSTOM', ['2026-02-30', '2026-03-01'], '')).toContain('有效');
    expect(analyticsQueryError('CUSTOM', ['0000-01-01', '0000-01-01'], '')).toContain('有效');
    expect(analyticsQueryError('CUSTOM', ['2026-10-02', '2026-10-01'], '')).toContain('不早于');
    expect(analyticsQueryError('TODAY', null, 'invalid')).toContain('UUID');
    expect(analyticsQueryError('TODAY', null, ' 00000000-0000-0000-0000-000000000001 ')).toBeUndefined();
  });

  it('真实的补零序列没有峰值，正数序列选择最高桶', () => {
    expect(findUsagePeak(Array.from({ length: 24 }, (_, hour) => ({ hour, operationCount: 0 })))).toBeUndefined();
    expect(findUsagePeak([])).toBeUndefined();
    expect(findUsagePeak([{ hour: 0, operationCount: 0 }, { hour: 9, operationCount: 2 }])?.hour).toBe(9);
  });

  it('邮箱与名称允许关键词及字面符号，长度边界与后端保持一致', () => {
    expect(analyticsQueryError('TODAY', null, '', { email: 'member+tag', displayName: '名称_100%' })).toBeUndefined();
    expect(analyticsQueryError('TODAY', null, '', { email: 'x'.repeat(320), displayName: '名'.repeat(80) })).toBeUndefined();
    expect(analyticsQueryError('TODAY', null, '', { email: 'x'.repeat(321) })).toContain('320');
    expect(analyticsQueryError('TODAY', null, '', { displayName: '名'.repeat(81) })).toContain('80');
  });

  it('单日数据可见，新增账号或实际使用账号独立出现时仍展示图表', () => {
    const zero = { date: '2026-10-01', accessCount: 0, uniqueVisitors: 0, activeUsers: 0, actualUsers: 0, newAccounts: 0 };
    expect(hasDailyActivity([zero])).toBe(false);
    expect(hasDailyActivity([{ ...zero, newAccounts: 1 }])).toBe(true);
    expect(hasDailyActivity([{ ...zero, actualUsers: 1 }])).toBe(true);
    const series = dailyMetricSeries([{ ...zero, actualUsers: 2, newAccounts: 1 }]);
    expect(series).toHaveLength(5);
    expect(series.every((item) => item.showSymbol)).toBe(true);
    expect(series.find((item) => item.name === '实际使用账号')?.data).toEqual([2]);
    expect(dailyMetricSeries([zero, { ...zero, date: '2026-10-02' }]).every((item) => !item.showSymbol)).toBe(true);
  });

  it('使用统计时区显示跨午夜与夏令时回拨事件', () => {
    expect(formatAnalyticsTime('2026-09-30T16:30:00Z', 'Asia/Shanghai')).toBe('2026-10-01 00:30:00');
    expect(formatAnalyticsTime('2026-09-30T16:30:00Z', 'America/Los_Angeles')).toBe('2026-09-30 09:30:00');
    expect(formatAnalyticsTime('2026-11-01T09:30:00Z', 'America/Los_Angeles')).toBe('2026-11-01 01:30:00');
    expect(formatAnalyticsTime('invalid', 'Asia/Shanghai')).toBe('时间无效');
    expect(formatAnalyticsTime('2026-10-01T00:00:00Z', 'invalid')).toContain('时区无效');
  });

  it('同名不同代码套餐保持独立，不把不同币种堆叠相加', () => {
    const record = { date: '2026-10-01', planCode: 'A', planName: '基础版', paidCount: 1, amountMinor: 100, currency: 'CNY' };
    const rows = [record, { ...record, planCode: 'B', amountMinor: 200 }, { ...record, currency: 'USD', amountMinor: 500 }];
    const series = rechargeMetricSeries(rows, 'CNY', ['2026-10-01', '2026-10-02']);
    expect(series.map((item) => item.data)).toEqual([[100, 0], [200, 0]]);
    expect(series[0]?.name).not.toBe(series[1]?.name);
    expect(rechargeMetricSeries(rows, 'USD', ['2026-10-01'])[0]?.data).toEqual([500]);
  });
});
