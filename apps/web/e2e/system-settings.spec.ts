import { expect, test, type Page } from '@playwright/test';
import { testUser } from './authFixture';

type SettingsLocale = 'zh-CN' | 'en';
interface SettingsView {
  displayName: string;
  email: string | null;
  username: string | null;
  locale: SettingsLocale;
  emailBindingAvailable: boolean;
  accountDeletionAvailable: boolean;
}
interface SettingsCall { path: string; method: string; body: Record<string, unknown> }

const password = 'synthetic-current1';
const localeKey = 'prompt-optimizer.locale.v1';
const challengeId = '00000000-0000-4000-8000-000000000201';

/** 所有API在浏览器内拦截；状态模拟同一普通账号，禁止真实注册、验证码投递或模型调用。 */
async function environment(page: Page, authenticated = true) {
  const state = {
    authenticated,
    settings: { displayName: '设置测试用户', email: 'settings@example.test', username: 'original.user', locale: 'zh-CN',
      emailBindingAvailable: true, accountDeletionAvailable: true } as SettingsView,
    calls: [] as SettingsCall[],
    unexpected: [] as string[],
    emailTarget: '',
    emailConflict: false,
    usernameConflict: false,
    preferenceFailure: false,
    deactivateFailure: false,
  };
  await page.route('**/api/**', async route => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const method = request.method();
    const body = method === 'GET' ? {} : (request.postDataJSON() ?? {}) as Record<string, unknown>;
    state.calls.push({ path, method, body });
    const ok = (data: unknown = null) => route.fulfill({ json: { requestId: 'system-settings-fixture', data } });
    const failure = (status: number, code: string, message: string) => route.fulfill({ status,
      json: { requestId: 'system-settings-fixture', error: { code, message, retryable: status >= 500, details: {} } } });
    const user = () => ({ ...testUser, email: state.settings.email ?? '', displayName: state.settings.displayName,
      phoneBound: false, platformAdmin: false });
    if (path === '/api/v1/auth/me') return state.authenticated ? ok(user()) : failure(401, 'AUTHENTICATION_REQUIRED', '请先登录。');
    if (path === '/api/v1/auth/csrf') return ok({ token: 'synthetic-csrf' });
    if (path === '/api/v1/auth/capabilities') return ok({ phoneRegistration: true, smsLogin: true, phoneBinding: true });
    if (path === '/api/v1/auth/captcha') return route.fulfill({ contentType: 'image/svg+xml',
      body: '<svg xmlns="http://www.w3.org/2000/svg" width="120" height="40"><text x="10" y="25">ABCD</text></svg>' });
    if (path === '/api/v1/auth/logout') { state.authenticated = false; return ok(); }
    if (path === '/api/v1/models') return ok([]);
    if (path.startsWith('/api/v1/analytics/')) return ok(path.endsWith('/context') ? { userId: testUser.userId, loginSessionId: null } : null);
    if (path.startsWith('/api/v1/me/settings')) {
      if (!state.authenticated) return failure(401, 'AUTHENTICATION_REQUIRED', '请先登录。');
      if (path === '/api/v1/me/settings' && method === 'GET') return ok(state.settings);
      if (path.endsWith('/profile') && method === 'PATCH') {
        state.settings.displayName = String(body.displayName).trim(); return ok(state.settings);
      }
      if (path.endsWith('/preferences') && method === 'PATCH') {
        if (state.preferenceFailure) return failure(503, 'SETTINGS_UNAVAILABLE', '语言设置暂时无法保存，请稍后重试。');
        state.settings.locale = body.locale === 'en' ? 'en' : 'zh-CN'; return ok(state.settings);
      }
      if (body.currentPassword !== password) return failure(403, 'CURRENT_PASSWORD_INVALID', '当前密码错误，请重试。');
      if (path.endsWith('/email-challenges') && method === 'POST') {
        state.emailTarget = String(body.email).trim().toLowerCase();
        return ok({ challengeId, expiresInSeconds: 300, resendAfterSeconds: 60 });
      }
      if (path.endsWith('/email') && method === 'POST') {
        if (body.challengeId !== challengeId || body.verificationCode !== '123456'
          || String(body.email).trim().toLowerCase() !== state.emailTarget) {
          return failure(400, 'EMAIL_CODE_INVALID', '验证码已失效或不属于当前邮箱。');
        }
        if (state.emailConflict) return failure(409, 'EMAIL_ALREADY_BOUND', '该邮箱已绑定其他账号，无法绑定。');
        state.settings.email = state.emailTarget; state.authenticated = false; return ok(state.settings);
      }
      if (path.endsWith('/username') && method === 'PATCH') {
        if (state.usernameConflict) return failure(409, 'USERNAME_ALREADY_BOUND', '该用户名已被其他账号占用。');
        state.settings.username = String(body.username).trim().toLowerCase(); state.authenticated = false; return ok(state.settings);
      }
      if (path.endsWith('/logout-all') && method === 'POST') { state.authenticated = false; return ok(); }
      if (path.endsWith('/deactivate') && method === 'POST') {
        if (body.confirmation !== 'DELETE') return failure(400, 'CONFIRMATION_REQUIRED', '请确认注销账号。');
        if (state.deactivateFailure) return failure(503, 'SETTINGS_UNAVAILABLE', '账号暂时无法注销，请稍后重试。');
        state.authenticated = false; return ok();
      }
    }
    state.unexpected.push(path);
    return failure(501, 'UNMOCKED_TEST_PATH', '本场景未模拟该接口。');
  });
  const writes = (suffix: string) => state.calls.filter(call => call.path === '/api/v1/me/settings' + suffix && call.method !== 'GET');
  return { state, writes };
}

async function openSettings(page: Page) {
  await page.goto('/settings');
  await expect(page.getByRole('region', { name: '系统设置', exact: true })).toBeVisible();
  await expect(page.getByRole('textbox', { name: '显示名称', exact: true })).toHaveValue('设置测试用户');
}

const form = (page: Page, kind: 'profile' | 'email' | 'username' | 'security') => page.getByTestId(`system-${kind}-form`);
const safetyDialog = (page: Page, name: string) => page.getByRole('dialog', { name, exact: true });

test.beforeEach(async ({ page }, testInfo) => {
  if (testInfo.project.name.startsWith('mobile')) await page.setViewportSize({ width: 375, height: 812 });
});

test('普通用户可访问系统设置，原手机号和本地索引功能保留且窄屏无横向溢出', async ({ page }, testInfo) => {
  const { state } = await environment(page);
  await openSettings(page);
  await expect(page.getByRole('heading', { name: '通用设置', exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { name: '账号管理', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '绑定手机号', exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { name: '项目上下文与隐私', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '清除本地索引', exact: true })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
  expect(state.calls.filter(call => call.path.startsWith('/api/v1/admin/'))).toHaveLength(0);
  expect(state.unexpected).toEqual([]);
  await page.screenshot({ path: testInfo.outputPath('system-settings.png'), fullPage: true, animations: 'disabled' });
});

test('中英切换同步账号及本机，刷新和清除本机偏好后仍恢复账号语言', async ({ page }) => {
  const { state, writes } = await environment(page);
  await openSettings(page);
  // Element Plus 选择器的显示文本盖在只读输入上；从真实可点的包装层展开。
  await page.locator('.el-select').filter({ has: page.getByRole('combobox', { name: '界面语言', exact: true }) }).click();
  await page.getByRole('option', { name: 'English', exact: true }).click();
  await expect.poll(() => writes('/preferences').length).toBe(1);
  await expect(page.getByRole('region', { name: 'System settings', exact: true })).toBeVisible();
  await expect(page.locator('html')).toHaveAttribute('lang', 'en');
  expect(state.settings.locale).toBe('en');
  expect(await page.evaluate(key => localStorage.getItem(key), localeKey)).toContain('en');
  await page.reload();
  await expect(page.getByRole('heading', { name: 'General settings', exact: true })).toBeVisible();
  await page.evaluate(key => localStorage.removeItem(key), localeKey);
  await page.reload();
  await expect(page.getByRole('region', { name: 'System settings', exact: true })).toBeVisible();
  expect(writes('/preferences')).toHaveLength(1);
});

test('语言保存失败不会伪报成功或覆盖已保存的语言', async ({ page }) => {
  const { state, writes } = await environment(page);
  state.preferenceFailure = true;
  await openSettings(page);
  await page.locator('.el-select').filter({ has: page.getByRole('combobox', { name: '界面语言', exact: true }) }).click();
  await page.getByRole('option', { name: 'English', exact: true }).click();
  await expect.poll(() => writes('/preferences').length).toBe(1);
  await expect(page.getByText('语言设置暂时无法保存，请稍后重试。', { exact: true }).first()).toBeVisible();
  expect(state.settings.locale).toBe('zh-CN');
  await expect(page.locator('html')).toHaveAttribute('lang', 'zh-CN');
});

test('修改显示名称只提交可编辑资料并刷新当前身份，重载后仍显示已保存名称', async ({ page }) => {
  const { state, writes } = await environment(page);
  await openSettings(page);
  const profile = form(page, 'profile');
  await profile.getByRole('textbox', { name: '显示名称', exact: true }).fill('  星河🌟  ');
  await profile.getByRole('button', { name: '保存显示名称', exact: true }).click();
  await expect.poll(() => writes('/profile').length).toBe(1);
  expect(writes('/profile')[0]?.body).toEqual({ displayName: '星河🌟' });
  expect(state.authenticated).toBe(true);
  await expect(page.locator('.account-menu')).toContainText('星河🌟');
  await page.reload();
  await expect(page.getByRole('textbox', { name: '显示名称', exact: true })).toHaveValue('星河🌟');
});

test('邮箱挑战绑定目标且发送有冷却，修改邮箱不能继续提交旧挑战', async ({ page }) => {
  const { writes } = await environment(page);
  await openSettings(page);
  const email = form(page, 'email');
  await email.getByRole('textbox', { name: '邮箱地址', exact: true }).fill(' NewAddress@Example.Test ');
  await email.getByLabel('邮箱操作当前密码', { exact: true }).fill(password);
  await email.getByRole('button', { name: '获取邮箱验证码', exact: true }).click();
  await expect.poll(() => writes('/email-challenges').length).toBe(1);
  expect(writes('/email-challenges')[0]?.body).toEqual({ email: 'newaddress@example.test', currentPassword: password });
  await expect(email.getByRole('button', { name: /^\d+s$/ })).toBeDisabled();
  await email.getByRole('textbox', { name: '邮箱验证码', exact: true }).fill('123456');
  await email.getByRole('textbox', { name: '邮箱地址', exact: true }).fill('other@example.test');
  await email.getByRole('textbox', { name: '邮箱验证码', exact: true }).fill('123456');
  await expect(email.getByRole('button', { name: '绑定邮箱', exact: true })).toBeDisabled();
  expect(writes('/email')).toHaveLength(0);
});

test('邮箱占用明确报错，重试保持冲突且双方身份不变', async ({ page }) => {
  const { state, writes } = await environment(page);
  state.emailConflict = true;
  await openSettings(page);
  const email = form(page, 'email');
  await email.getByRole('textbox', { name: '邮箱地址', exact: true }).fill('new@example.test');
  await email.getByLabel('邮箱操作当前密码', { exact: true }).fill(password);
  await email.getByRole('button', { name: '获取邮箱验证码', exact: true }).click();
  await email.getByRole('textbox', { name: '邮箱验证码', exact: true }).fill('123456');
  await email.getByRole('button', { name: '绑定邮箱', exact: true }).click();
  await expect(page.getByText('该邮箱已绑定其他账号，无法绑定。', { exact: true }).first()).toBeVisible();
  await expect(page).toHaveURL(/\/settings$/);
  expect(state.settings.email).toBe('settings@example.test');
  await expect(email.getByRole('textbox', { name: '邮箱地址', exact: true })).toHaveValue('new@example.test');
  await email.getByLabel('邮箱操作当前密码', { exact: true }).fill(password);
  await email.getByRole('textbox', { name: '邮箱验证码', exact: true }).fill('123456');
  await email.getByRole('button', { name: '绑定邮箱', exact: true }).click();
  await expect.poll(() => writes('/email').length).toBe(2);
  await expect(page).toHaveURL(/\/settings$/);
  expect(state.settings.email).toBe('settings@example.test');
  expect(state.authenticated).toBe(true);
});

test('邮箱验证成功只提交目标与挑战凭据并要求重新登录', async ({ page }) => {
  const { state, writes } = await environment(page);
  await openSettings(page);
  const email = form(page, 'email');
  await email.getByRole('textbox', { name: '邮箱地址', exact: true }).fill('new@example.test');
  await email.getByLabel('邮箱操作当前密码', { exact: true }).fill(password);
  await email.getByRole('button', { name: '获取邮箱验证码', exact: true }).click();
  await email.getByRole('textbox', { name: '邮箱验证码', exact: true }).fill('123456');
  await email.getByRole('button', { name: '绑定邮箱', exact: true }).click();
  await expect(page).toHaveURL(/\/login(?:\?|$)/);
  expect(state.settings.email).toBe('new@example.test');
  expect(state.authenticated).toBe(false);
  expect(writes('/email').at(-1)?.body).toEqual({ email: 'new@example.test', challengeId, verificationCode: '123456', currentPassword: password });
});

test('用户名占用不覆盖账号，合法用户名规范化保存并退出当前认证', async ({ page }) => {
  const { state, writes } = await environment(page);
  state.usernameConflict = true;
  await openSettings(page);
  const username = form(page, 'username');
  await username.getByRole('textbox', { name: '用户名', exact: true }).fill('  Mixed.User  ');
  await username.getByLabel('用户名操作当前密码', { exact: true }).fill(password);
  await username.getByRole('button', { name: '保存用户名', exact: true }).click();
  await expect(page.getByText('该用户名已被其他账号占用。', { exact: true }).first()).toBeVisible();
  expect(state.settings.username).toBe('original.user');
  await expect(page).toHaveURL(/\/settings$/);
  state.usernameConflict = false;
  await username.getByLabel('用户名操作当前密码', { exact: true }).fill(password);
  await username.getByRole('button', { name: '保存用户名', exact: true }).click();
  await expect(page).toHaveURL(/\/login(?:\?|$)/);
  expect(state.settings.username).toBe('mixed.user');
  expect(writes('/username').at(-1)?.body).toEqual({ username: 'Mixed.User', currentPassword: password });
});

test('登出所有设备密码失败保持登录，确认成功后清空本机身份', async ({ page }) => {
  const { state, writes } = await environment(page);
  await openSettings(page);
  const security = form(page, 'security');
  await security.getByLabel('安全操作当前密码', { exact: true }).fill('wrong-current');
  await security.getByRole('button', { name: '登出所有设备', exact: true }).click();
  const dialog = safetyDialog(page, '确认登出所有设备');
  await expect(dialog).toBeVisible();
  await dialog.getByRole('button', { name: '登出所有设备', exact: true }).click();
  await expect(page.getByText('当前密码错误，请重试。', { exact: true }).first()).toBeVisible();
  await expect(page).toHaveURL(/\/settings$/);
  expect(state.authenticated).toBe(true);
  await security.getByLabel('安全操作当前密码', { exact: true }).fill(password);
  await security.getByRole('button', { name: '登出所有设备', exact: true }).click();
  await dialog.getByRole('button', { name: '登出所有设备', exact: true }).click();
  await expect(page).toHaveURL(/\/login(?:\?|$)/);
  expect(state.authenticated).toBe(false);
  expect(writes('/logout-all').at(-1)?.body).toEqual({ currentPassword: password });
});

test('注销必须输入确认词并二次确认，取消不写入，失败保留页面，成功后退出', async ({ page }) => {
  const { state, writes } = await environment(page);
  await openSettings(page);
  const security = form(page, 'security');
  await security.getByLabel('安全操作当前密码', { exact: true }).fill(password);
  const deactivate = security.getByRole('button', { name: '注销账号', exact: true });
  await expect(deactivate).toBeDisabled();
  await security.getByRole('textbox', { name: '注销确认词', exact: true }).fill('DELETE');
  await deactivate.click();
  const dialog = safetyDialog(page, '确认注销账号');
  await expect(dialog).toBeVisible();
  expect(writes('/deactivate')).toHaveLength(0);
  await dialog.getByRole('button', { name: '取消', exact: true }).click();
  expect(writes('/deactivate')).toHaveLength(0);
  state.deactivateFailure = true;
  await deactivate.click();
  await dialog.getByRole('button', { name: '注销账号', exact: true }).click();
  await expect(page.getByText('账号暂时无法注销，请稍后重试。', { exact: true }).first()).toBeVisible();
  expect(state.authenticated).toBe(true);
  await expect(page).toHaveURL(/\/settings$/);
  state.deactivateFailure = false;
  await security.getByLabel('安全操作当前密码', { exact: true }).fill(password);
  await security.getByRole('textbox', { name: '注销确认词', exact: true }).fill('DELETE');
  await deactivate.click();
  await dialog.getByRole('button', { name: '注销账号', exact: true }).click();
  await expect(page).toHaveURL(/\/\?account=deactivated$/);
  expect(writes('/deactivate').at(-1)?.body).toEqual({ currentPassword: password, confirmation: 'DELETE' });
  expect(state.authenticated).toBe(false);
});

test('协议与隐私链接可查看，匿名仍能阅读条款而系统设置要求注册登录', async ({ page }) => {
  const { state } = await environment(page);
  await openSettings(page);
  const panel = page.getByRole('region', { name: '系统设置', exact: true });
  await expect(panel.getByRole('link', { name: /用户.*协议/ })).toHaveAttribute('href', '/terms');
  await expect(panel.getByRole('link', { name: '隐私政策', exact: true })).toHaveAttribute('href', '/privacy');
  state.authenticated = false;
  await page.goto('/terms');
  await expect(page.getByRole('heading', { name: '用户协议', exact: true })).toBeVisible();
  await page.goto('/privacy');
  await expect(page.getByRole('heading', { name: '隐私政策', exact: true })).toBeVisible();
  const before = state.calls.filter(call => call.path === '/api/v1/me/settings').length;
  await page.goto('/settings');
  await expect(page).toHaveURL(/\/login(?:\?|$)/);
  expect(state.calls.filter(call => call.path === '/api/v1/me/settings')).toHaveLength(before);
});
