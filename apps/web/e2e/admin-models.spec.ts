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

test('在统一目录的已停用模型行配置图像能力，不改变模型发布状态', async ({ page }, testInfo) => {
  await page.route('**/api/**', route => route.fulfill({ status: 501, json: { error: { message: '未登记测试请求' } } }));
  await mockAuthentication(page, true, true);
  const model: AdminModel = { id: 'disabled-model', publicId: 'fixture:vision/model', routeKey: 'fixture', upstreamModel: 'vision/model',
    displayName: '待启用视觉模型', enabled: false, defaultModel: false, sortOrder: 2 };
  let capability = { id: model.publicId, displayName: model.displayName, vision: false, maxImages: 1, evidence: '原有依据', mock: false };
  let catalogWrites = 0, capabilityWrites = 0;
  await page.route('**/api/v1/admin/models**', route => {
    if (route.request().method() !== 'GET') catalogWrites++;
    const data = new URL(route.request().url()).pathname.endsWith('/routes') ? [{ key: 'fixture', providerName: '测试路由' }] : [model];
    return route.fulfill({ json: { data } });
  });
  await page.route('**/api/v1/admin/image-prompt-models**', route => {
    if (route.request().method() === 'PUT') {
      const url = new URL(route.request().url()); expect(url.searchParams.get('modelId')).toBe(model.publicId);
      const body = route.request().postDataJSON() as { enabled: boolean; maxImages: number; evidence: string };
      capability = { ...capability, vision: body.enabled, maxImages: body.maxImages, evidence: body.evidence }; capabilityWrites++;
      return route.fulfill({ json: { data: null } });
    }
    return route.fulfill({ json: { data: [capability] } });
  });
  await page.goto('/admin/models', { waitUntil: 'domcontentloaded' });
  const row = page.getByRole('row').filter({ hasText: model.displayName });
  await row.getByRole('button', { name: '图像能力', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: `图像输入能力：${model.displayName}`, exact: true });
  await expect(dialog.getByText('该模型当前已停用。可先配置图像能力，启用后再供两个工作台选择。')).toBeVisible();
  await dialog.locator('.el-switch').click();
  await dialog.getByRole('textbox', { name: '视觉能力依据' }).fill('官方测试依据：支持单图');
  await dialog.getByRole('button', { name: '保存图像能力' }).click();
  await expect(dialog).not.toBeVisible(); expect(capabilityWrites).toBe(1); expect(catalogWrites).toBe(0);
  await expect(row).toContainText('已停用');
  await row.getByRole('button', { name: '图像能力', exact: true }).click();
  await expect(dialog.getByRole('switch', { name: '允许图片输入' })).toBeChecked();
  await expect(dialog.getByRole('textbox', { name: '视觉能力依据' })).toHaveValue('官方测试依据：支持单图');
  await expect(dialog.locator('.el-loading-mask')).not.toBeVisible();
  await page.screenshot({ path: testInfo.outputPath('unified-model-vision.png'), fullPage: true, animations: 'disabled' });
});
