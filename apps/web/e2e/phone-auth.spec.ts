import { expect, test, type Page } from '@playwright/test';
import { mockAuthentication, testUser } from './authFixture';

/** 只模拟协议及界面，不验证真实云投递；任何 /api 请求均不会离开本机测试。 */
const phoneEnvironment = async (page: Page, authenticated = false): Promise<void> => {
  await page.route('**/api/**', route => route.fulfill({ status: 200, json: { data: [] } }));
  await mockAuthentication(page, authenticated);
  await page.route('**/api/v1/auth/capabilities', route => route.fulfill({ status: 200,
    json: { data: { phoneRegistration: true, smsLogin: true, phoneBinding: true } } }));
  await page.route('**/api/v1/auth/sms/challenges', route => route.fulfill({ status: 200,
    json: { data: { challengeId: 'synthetic-challenge', expiresInSeconds: 300, resendAfterSeconds: 60 } } }));
};

const passwordLoginCases = [
  { name: '手机号', input: '13800000000', identifier: '+8613800000000', identityType: 'PHONE', usernameMode: false },
  { name: '邮箱', input: 'test@example.com', identifier: 'test@example.com', identityType: undefined, usernameMode: false },
  { name: '管理员用户名', input: 'admin', identifier: 'admin', identityType: undefined, usernameMode: false },
  { name: '形似手机号的数字用户名', input: '13800000000', identifier: '13800000000', identityType: undefined, usernameMode: true },
] as const;

for (const loginCase of passwordLoginCases) {
  test(`主登录表单正确提交${loginCase.name}身份且只请求一次`, async ({ page }) => {
    await phoneEnvironment(page);
    await page.goto('/login');
    await expect(page.getByRole('heading', { name: '手机号或邮箱登录' })).toBeVisible();
    await page.getByLabel('手机号或邮箱', { exact: true }).fill(loginCase.input);
    if (loginCase.usernameMode) {
      await page.getByRole('button', { name: '使用用户名登录', exact: true }).click();
      await expect(page.getByRole('heading', { name: '用户名登录' })).toBeVisible();
    }
    await page.getByPlaceholder('请输入密码').fill('test-password');
    await page.getByPlaceholder('请输入图中字符').fill('ABCD');
    let loginRequests = 0;
    await page.route('**/api/v1/auth/login', async route => {
      loginRequests += 1;
      expect(route.request().postDataJSON()).toEqual({
        identifier: loginCase.identifier, password: 'test-password', captcha: 'ABCD',
        ...(loginCase.identityType ? { identityType: loginCase.identityType } : {}),
      });
      await mockAuthentication(page, true);
      await route.fulfill({ status: 200, json: { data: testUser } });
    });
    await page.getByRole('button', { name: '登录', exact: true }).click();
    await expect(page).toHaveURL(/\/workbench$/);
    expect(loginRequests).toBe(1);
  });
}

test('手机号注册保持密码规则，隐藏密码可查看，发码后进入冷却', async ({ page }) => {
  await phoneEnvironment(page);
  await page.goto('/login');
  await page.getByRole('button', { name: '立即注册', exact: true }).click();
  const panel = page.locator('.login-modal__form');
  await panel.getByLabel('邮箱/手机号', { exact: true }).fill('12800000000');
  await expect(panel.getByText('请输入有效的邮箱地址或中国大陆 11 位手机号。', { exact: true })).toBeVisible();
  await panel.getByLabel('邮箱/手机号', { exact: true }).fill('13800000000');
  await panel.getByLabel('图形验证码', { exact: true }).fill('ABCD');
  const send = page.waitForRequest('**/api/v1/auth/sms/challenges');
  await panel.getByRole('button', { name: '获取短信验证码' }).click();
  expect((await send).postDataJSON()).toMatchObject({ phone: '+8613800000000', purpose: 'REGISTER' });
  await expect(panel.getByRole('button', { name: /秒后重发/ })).toBeDisabled();
  await panel.getByLabel('短信验证码', { exact: true }).fill('123456');
  await panel.getByPlaceholder('请输入密码').fill('Short1');
  await panel.getByPlaceholder('请再次输入密码').fill('Short1');
  await panel.getByRole('checkbox').check();
  await expect(panel.getByRole('button', { name: '创建账号', exact: true })).toBeDisabled();
  await panel.getByPlaceholder('请输入密码').fill('Abcdefg1');
  await panel.getByPlaceholder('请再次输入密码').fill('Abcdefg1');
  await expect(panel.getByRole('button', { name: '创建账号', exact: true })).toBeEnabled();
  await panel.getByRole('button', { name: '显示密码', exact: true }).click();
  await expect(panel.getByPlaceholder('请输入密码')).toHaveAttribute('type', 'text');
  await page.route('**/api/v1/auth/phone/register', async route => {
    expect(route.request().postDataJSON()).toEqual({ phone: '+8613800000000', challengeId: 'synthetic-challenge', verificationCode: '123456', password: 'Abcdefg1' });
    await mockAuthentication(page, true);
    await route.fulfill({ status: 200, json: { data: { ...testUser, email: '', phoneBound: true, maskedPhone: '+86 138****0000' } } });
  });
  await panel.getByRole('button', { name: '创建账号', exact: true }).click();
  await expect(page.getByRole('heading', { name: '账号创建成功，已自动登录' })).toBeVisible();
  await page.getByRole('button', { name: '进入工作台', exact: true }).click();
  await expect(page).toHaveURL(/\/workbench$/);
});

test('管理员短信拒绝保留明确提示，手机号密码登录显式指定身份类型', async ({ page }) => {
  await phoneEnvironment(page);
  await page.route('**/api/v1/auth/phone/login', route => route.fulfill({ status: 403,
    json: { error: { code: 'SMS_PASSWORD_LOGIN_REQUIRED', message: '该账户请使用密码登录。' } } }));
  await page.goto('/login');
  await page.getByRole('button', { name: '短信登录', exact: true }).click();
  const panel = page.locator('.phone-auth');
  await panel.getByLabel('手机号', { exact: true }).fill('13800000000');
  await panel.getByLabel('图形验证码', { exact: true }).fill('ABCD');
  await panel.getByRole('button', { name: '获取短信验证码' }).click();
  await panel.getByLabel('短信验证码', { exact: true }).fill('123456');
  await panel.getByRole('button', { name: '登录', exact: true }).click();
  await expect(panel.getByRole('alert').last()).toContainText('该账户请使用密码登录。');
  await page.getByRole('button', { name: '手机号密码登录', exact: true }).click();
  await panel.getByLabel('手机号', { exact: true }).fill('13800000000');
  await panel.getByLabel('密码', { exact: true }).fill('test-password');
  await panel.getByLabel('图形验证码', { exact: true }).fill('ABCD');
  const login = page.waitForRequest('**/api/v1/auth/login');
  await panel.getByRole('button', { name: '登录', exact: true }).click();
  expect((await login).postDataJSON()).toMatchObject({ identifier: '+8613800000000', identityType: 'PHONE' });
  await expect(page).toHaveURL(/\/workbench$/);
});

test('设置页提醒绑定，占用返回冲突且不发送目标 userId', async ({ page }) => {
  await phoneEnvironment(page, true);
  await page.route('**/api/v1/me/phone-binding/challenges', route => route.fulfill({ status: 200,
    json: { data: { challengeId: 'binding-challenge', expiresInSeconds: 300, resendAfterSeconds: 60 } } }));
  await page.route('**/api/v1/me/phone-binding', route => {
    expect(route.request().postDataJSON()).not.toHaveProperty('userId');
    return route.fulfill({ status: 409, json: { error: { code: 'PHONE_ALREADY_BOUND',
      message: '该手机号已绑定其他账号，无法绑定到当前账号。请使用原账号登录，或更换手机号。' } } });
  });
  await page.goto('/settings');
  await expect(page.getByRole('heading', { name: '账户安全' })).toBeVisible();
  await page.getByRole('button', { name: '绑定手机号', exact: true }).click();
  await page.getByLabel('当前密码', { exact: true }).fill('test-password');
  await page.getByLabel('待绑定手机号', { exact: true }).fill('13800000000');
  await page.getByLabel('图形验证码', { exact: true }).fill('ABCD');
  await page.getByRole('button', { name: '获取短信验证码' }).click();
  await page.getByLabel('短信验证码', { exact: true }).fill('123456');
  await page.getByRole('button', { name: '确认绑定' }).click();
  await expect(page.locator('.binding-form').getByRole('alert').last()).toContainText('该手机号已绑定其他账号');
  await expect(page).toHaveURL(/\/settings$/);
});

test('短信能力关闭时原邮箱入口可用，短信入口不展示', async ({ page }) => {
  await mockAuthentication(page, false);
  await page.goto('/login');
  await expect(page.getByPlaceholder('请输入手机号或邮箱')).toBeVisible();
  await expect(page.getByRole('button', { name: '短信登录', exact: true })).toHaveCount(0);
  await expect(page.getByRole('button', { name: '手机号注册', exact: true })).toHaveCount(0);
  await expect(page.getByRole('button', { name: '手机号密码登录', exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: '立即注册', exact: true }).click();
  const form = page.locator('.login-modal__form');
  await expect(form.getByText('当前仅支持邮箱验证码注册。')).toBeVisible();
  await form.getByLabel('邮箱', { exact: true }).fill('13800000000');
  await expect(form.getByText('短信服务未开启，当前仅支持邮箱注册。', { exact: true })).toBeVisible();
  await expect(form.getByLabel('图形验证码', { exact: true })).toHaveCount(0);
  await expect(form.getByRole('button', { name: '获取短信验证码' })).toHaveCount(0);
  await expect(form.getByRole('button', { name: '获取邮箱验证码' })).toBeDisabled();
  await form.getByLabel('邮箱', { exact: true }).fill('new@example.com');
  await expect(form.getByRole('button', { name: '获取邮箱验证码' })).toBeEnabled();
});

test('统一注册表单不能提交过期或其他手机号的短信挑战', async ({ page }) => {
  await page.clock.install();
  await phoneEnvironment(page);
  await page.goto('/login');
  await page.getByRole('button', { name: '立即注册', exact: true }).click();
  const form = page.locator('.login-modal__form');
  const account = form.getByLabel('邮箱/手机号', { exact: true });
  await account.fill('13800000000');
  await form.getByLabel('图形验证码', { exact: true }).fill('ABCD');
  await form.getByRole('button', { name: '获取短信验证码' }).click();
  await form.getByLabel('短信验证码', { exact: true }).fill('123456');
  await form.getByPlaceholder('请输入密码').fill('Abcdefg1');
  await form.getByPlaceholder('请再次输入密码').fill('Abcdefg1');
  await form.getByRole('checkbox').check();
  await expect(form.getByRole('button', { name: '创建账号', exact: true })).toBeEnabled();
  await page.clock.fastForward(301_000);
  await expect(form.getByText('创建账号前：短信验证码已过期，请重新获取。')).toBeVisible();
  await expect(form.getByRole('button', { name: '创建账号', exact: true })).toBeDisabled();
  await account.fill('13800000001');
  await expect(form.getByLabel('短信验证码', { exact: true })).toHaveValue('');
  await form.getByLabel('短信验证码', { exact: true }).fill('123456');
  await expect(form.getByText('创建账号前：请先获取当前手机号的短信验证码。')).toBeVisible();
  await expect(form.getByRole('button', { name: '创建账号', exact: true })).toBeDisabled();
});

test('绑定成功立即显示脱敏号码，不额外读取账户也不暴露完整号码', async ({ page }) => {
  await phoneEnvironment(page, true);
  await page.route('**/api/v1/me/phone-binding/challenges', route => route.fulfill({ status: 200,
    json: { data: { challengeId: 'binding-success', expiresInSeconds: 300, resendAfterSeconds: 60 } } }));
  await page.route('**/api/v1/me/phone-binding', route => route.fulfill({ status: 200,
    json: { data: { ...testUser, phoneBound: true, maskedPhone: '+86 138****0000' } } }));
  await page.goto('/settings');
  await page.getByRole('button', { name: '绑定手机号', exact: true }).click();
  await page.getByLabel('当前密码', { exact: true }).fill('test-password');
  await page.getByLabel('待绑定手机号', { exact: true }).fill('13800000000');
  await page.getByLabel('图形验证码', { exact: true }).fill('ABCD');
  await page.getByRole('button', { name: '获取短信验证码' }).click();
  await page.getByLabel('短信验证码', { exact: true }).fill('123456');
  await page.route('**/api/v1/auth/me', route => route.fulfill({ status: 503 }));
  await page.getByRole('button', { name: '确认绑定' }).click();
  await expect(page.getByText('已绑定手机号：+86 138****0000')).toBeVisible();
  await expect(page.locator('.binding-form')).toHaveCount(0);
  await expect(page.locator('.phone-security')).not.toContainText('13800000000');
});

test('普通账号短信登录不提交密码，成功后进入工作台', async ({ page }) => {
  await phoneEnvironment(page);
  await page.route('**/api/v1/auth/phone/login', async route => {
    expect(route.request().postDataJSON()).not.toHaveProperty('password');
    await mockAuthentication(page, true);
    await route.fulfill({ status: 200, json: { data: { ...testUser, phoneBound: true, maskedPhone: '+86 138****0000' } } });
  });
  await page.goto('/login');
  await page.getByRole('button', { name: '短信登录', exact: true }).click();
  const panel = page.locator('.phone-auth');
  await panel.getByLabel('手机号', { exact: true }).fill('13800000000');
  await panel.getByLabel('图形验证码', { exact: true }).fill('ABCD');
  await panel.getByRole('button', { name: '获取短信验证码' }).click();
  await panel.getByLabel('短信验证码', { exact: true }).fill('123456');
  await panel.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page).toHaveURL(/\/workbench$/);
});
