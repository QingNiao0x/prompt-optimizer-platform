import { expect, test } from '@playwright/test';

import { mockAuthentication } from './authFixture';
import type { AdminModel } from '../src/types/api';

test('普通用户无法进入模型管理页面', async ({ page }) => {
  await mockAuthentication(page);
  await page.route('**/api/v1/models', (route) => route.fulfill({
    status: 200, json: { requestId: 'e2e-models', data: [] },
  }));
  await page.goto('/admin/models');
  await expect(page).toHaveURL(/\/workbench$/);
  await expect(page.getByRole('link', { name: '模型管理' })).toHaveCount(0);
});

test('管理员可发布、修改和移除用户可选模型', async ({ page }) => {
  await mockAuthentication(page, true, true);
  const models: AdminModel[] = [{
    id: '00000000-0000-0000-0000-000000000101',
    publicId: 'deepseek:deepseek-chat', routeKey: 'deepseek', upstreamModel: 'deepseek-chat',
    displayName: 'DeepSeek', enabled: true, defaultModel: true, sortOrder: 0,
  }];
  await page.route('**/api/v1/admin/models**', async (route) => {
    const url = new URL(route.request().url());
    const method = route.request().method();
    let data: unknown = null;
    if (url.pathname.endsWith('/routes')) {
      data = [{ key: 'deepseek', providerName: 'DeepSeek' }];
    } else if (method === 'GET') {
      data = models;
    } else if (method === 'POST') {
      const body = route.request().postDataJSON() as AdminModel;
      const created = { ...body, id: '00000000-0000-0000-0000-000000000202',
        publicId: `deepseek:${body.upstreamModel}` };
      models.push(created);
      data = created;
    } else if (method === 'PUT') {
      const body = route.request().postDataJSON() as AdminModel;
      const model = models.find((entry) => url.pathname.endsWith(entry.id));
      if (!model) throw new Error('Unknown test model');
      if (body.defaultModel) models.forEach((entry) => { entry.defaultModel = false; });
      Object.assign(model, body);
      data = model;
    } else if (method === 'DELETE') {
      const index = models.findIndex((entry) => url.pathname.endsWith(entry.id));
      if (index < 0) throw new Error('Unknown test model');
      models.splice(index, 1);
    }
    await route.fulfill({ status: 200, json: { requestId: 'e2e-admin', data } });
  });

  await page.goto('/admin/models');
  await expect(page.getByRole('heading', { name: '用户可选模型' })).toBeVisible();
  await page.getByRole('button', { name: '添加模型' }).click();
  const dialog = page.getByRole('dialog', { name: '添加模型' });
  await dialog.getByLabel('上游调用 ID').fill('kimi-k3');
  await dialog.getByLabel('模型版本').fill('Kimi K3');
  await dialog.getByRole('button', { name: '保存' }).click();
  const kimiRow = page.getByRole('row').filter({ hasText: 'Kimi K3' });
  await expect(kimiRow).toBeVisible();

  await kimiRow.getByRole('button', { name: '编辑' }).click();
  const editDialog = page.getByRole('dialog', { name: '编辑模型' });
  await editDialog.getByLabel('模型版本').fill('Kimi 新版');
  await editDialog.getByRole('button', { name: '保存' }).click();
  await expect(page.getByRole('row').filter({ hasText: 'Kimi 新版' })).toBeVisible();

  const editedRow = page.getByRole('row').filter({ hasText: 'Kimi 新版' });
  await editedRow.getByRole('button', { name: '移除' }).click();
  await page.getByRole('button', { name: '移除', exact: true }).last().click();
  await expect(page.getByRole('row').filter({ hasText: 'Kimi 新版' })).toHaveCount(0);
});
