import { expect, test } from '@playwright/test';
import type { AnalyticsClientEvent } from '../src/types/api';
import { mockAuthentication, testUser } from './authFixture';

const prefix = 'prompt-optimizer.analytics.pending.v1:';
const sessionId = '00000000-0000-4000-8000-000000000301';

test('已有登录会话直接首页和刷新各采集一次，重复导航不制造新事件', async ({ page }) => {
  await mockAuthentication(page, true);
  const events: AnalyticsClientEvent[] = [];
  await page.route('**/api/v1/analytics/context', (route) => route.fulfill({
    json: { data: { userId: testUser.userId, loginSessionId: sessionId } },
  }));
  await page.route('**/api/v1/analytics/events', async (route) => {
    events.push(route.request().postDataJSON() as AnalyticsClientEvent);
    await route.fulfill({ status: 200, json: { data: null } });
  });
  await page.goto('/');
  await expect(page.getByRole('heading', { name: /让模型先理解你的工程/ })).toBeVisible();
  await expect.poll(() => events.length).toBe(1);
  expect(events[0]!.eventType).toBe('APP_VISIT');
  expect(events[0]!.expectedUserId).toBe(testUser.userId);
  await page.getByRole('link', { name: 'PromptOptimizer 首页' }).click();
  await page.waitForTimeout(150);
  expect(events).toHaveLength(1);
  await page.reload();
  await expect.poll(() => events.length).toBe(2);
  expect(events[0]!.eventId).not.toBe(events[1]!.eventId);
  await expect.poll(() => page.evaluate((keyPrefix) => (
    Object.keys(localStorage).filter((key) => key.startsWith(keyPrefix)).length
  ), prefix)).toBe(0);
});

test('上报503不阻断首页，重载恢复相同事件而不改变发生时间', async ({ page }) => {
  await mockAuthentication(page, true);
  await page.route('**/api/v1/analytics/context', (route) => route.fulfill({
    json: { data: { userId: testUser.userId, loginSessionId: sessionId } },
  }));
  const attempts: AnalyticsClientEvent[] = [];
  let recovering = false;
  await page.route('**/api/v1/analytics/events', async (route) => {
    attempts.push(route.request().postDataJSON() as AnalyticsClientEvent);
    await route.fulfill(recovering
      ? { status: 200, json: { data: null } }
      : { status: 503, json: { error: { code: 'ANALYTICS_DELIVERY_UNAVAILABLE' } } });
  });
  await page.goto('/');
  await expect(page.getByRole('heading', { name: /让模型先理解你的工程/ })).toBeVisible();
  await expect.poll(() => attempts.length).toBeGreaterThan(0);
  const firstEvent = attempts[0]!;
  await expect.poll(() => page.evaluate((keyPrefix) => (
    Object.keys(localStorage).filter((key) => key.startsWith(keyPrefix)).length
  ), prefix)).toBe(1);
  recovering = true;
  await page.reload();
  await expect.poll(() => attempts.filter((event) => event.eventId === firstEvent.eventId).length).toBeGreaterThanOrEqual(2);
  const restored = attempts.findLast((event) => event.eventId === firstEvent.eventId)!;
  expect(restored).toEqual(firstEvent);
  await expect.poll(() => page.evaluate((keyPrefix) => (
    Object.keys(localStorage).filter((key) => key.startsWith(keyPrefix)).length
  ), prefix)).toBe(0);
  await expect(page.getByRole('heading', { name: /让模型先理解你的工程/ })).toBeVisible();
});

test('匿名首页不采集，其他账号旧队列不会在当前登录身份下发送', async ({ page }) => {
  await mockAuthentication(page, false);
  const attempts: AnalyticsClientEvent[] = [];
  await page.route('**/api/v1/analytics/events', async (route) => {
    attempts.push(route.request().postDataJSON() as AnalyticsClientEvent);
    await route.fulfill({ status: 200, json: { data: null } });
  });
  await page.goto('/');
  await expect(page.getByRole('button', { name: '登录' }).first()).toBeVisible();
  expect(attempts).toHaveLength(0);
  await page.evaluate(({ keyPrefix, owner }) => {
    const eventId = '00000000-0000-4000-8000-000000000302';
    localStorage.setItem(`${keyPrefix}${owner}:${eventId}`, JSON.stringify({
      eventId, eventType: 'APP_VISIT', occurredAt: '2026-10-01T00:00:00Z',
      expectedUserId: owner, expectedLoginSessionId: null,
    }));
  }, { keyPrefix: prefix, owner: '00000000-0000-4000-8000-000000000303' });
  await mockAuthentication(page, true);
  await page.route('**/api/v1/analytics/context', (route) => route.fulfill({
    json: { data: { userId: testUser.userId, loginSessionId: sessionId } },
  }));
  await page.reload();
  await expect.poll(() => attempts.length).toBe(1);
  expect(attempts[0]!.expectedUserId).toBe(testUser.userId);
  expect(await page.evaluate((keyPrefix) => (
    Object.keys(localStorage).filter((key) => key.startsWith(keyPrefix)).length
  ), prefix)).toBe(1);
});
