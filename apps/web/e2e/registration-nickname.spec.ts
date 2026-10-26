import { expect, test, type Page } from '@playwright/test';
import { testUser } from './authFixture';

/** 注册界面只使用合成身份，全部API均拦截，不访问真实账号或验证码服务。 */
async function registrationEnvironment(page: Page) {
  let user: typeof testUser & { phoneBound?: boolean } | undefined;
  const submissions: Array<Record<string, string>> = [];
  let codes = 0;
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname;
    const ok = (data: unknown) => route.fulfill({ json: { requestId: 'nickname-fixture', data } });
    if (path === '/api/v1/auth/me') return user ? ok(user) : route.fulfill({ status: 401, json: {} });
    if (path === '/api/v1/auth/csrf') return ok({ token: 'synthetic-csrf' });
    if (path === '/api/v1/auth/capabilities') return ok({ phoneRegistration: true, smsLogin: true, phoneBinding: false });
    if (path === '/api/v1/auth/captcha') return route.fulfill({ contentType: 'image/svg+xml',
      body: '<svg xmlns="http://www.w3.org/2000/svg" width="120" height="40"><text x="10" y="25">ABCD</text></svg>' });
    if (path === '/api/v1/auth/registration-code' || path === '/api/v1/auth/sms/challenges') {
      codes++;
      return ok({ challengeId: 'synthetic-challenge', expiresInSeconds: 300, resendAfterSeconds: 60 });
    }
    if (path === '/api/v1/auth/register' || path === '/api/v1/auth/phone/register') {
      const body = route.request().postDataJSON() as Record<string, string>;
      submissions.push(body);
      user = { ...testUser, email: body.email ?? '', phoneBound: !!body.phone,
        displayName: body.displayName || (body.phone ? '新用户' : 'nickname') };
      return ok(user);
    }
    if (path === '/api/v1/models') return ok([]);
    if (path.startsWith('/api/v1/analytics/')) return ok(path.endsWith('/context') ? { userId: user?.userId, loginSessionId: null } : null);
    return route.fulfill({ status: 503, json: { error: { code: 'UNMOCKED_TEST_PATH', message: '未模拟该路径。' } } });
  });
  return { submissions, codeCount: () => codes };
}

for (const kind of ['email', 'phone'] as const) {
  for (const withNickname of [false, true]) {
    test(`${kind}注册${withNickname ? '保存可选昵称' : '可不填写昵称'}并显示服务端返回的名称`, async ({ page }, testInfo) => {
      const state = await registrationEnvironment(page);
      await page.goto('/login?mode=register');
      const form = page.locator('.login-modal__form');
      const nickname = form.getByRole('textbox', { name: '昵称', exact: true });
      await expect(nickname).toBeVisible();
      await expect(nickname).not.toHaveAttribute('required');
      await expect(nickname).toHaveAttribute('maxlength', '80');
      await form.getByLabel('邮箱/手机号', { exact: true }).fill(kind === 'email' ? 'nickname@example.test' : '13800000000');
      if (kind === 'phone') await form.getByLabel('图形验证码', { exact: true }).fill('ABCD');
      await form.getByRole('button', { name: kind === 'email' ? '获取邮箱验证码' : '获取短信验证码', exact: true }).click();
      await expect.poll(state.codeCount).toBe(1);
      // 昵称属于可选资料，发码后编辑它不能使当前邮箱/手机号的挑战失效或额外发送验证码。
      if (withNickname) await nickname.fill('  星河🌟  ');
      await form.getByPlaceholder('请输入 6 位验证码').fill('123456');
      await form.getByPlaceholder('请输入密码', { exact: true }).fill('Password1');
      await form.getByPlaceholder('请再次输入密码', { exact: true }).fill('Password1');
      await form.getByRole('checkbox').check();
      if (withNickname && kind === 'email') {
        await page.screenshot({ path: testInfo.outputPath('registration-with-nickname.png'), animations: 'disabled' });
      }
      await form.getByRole('button', { name: '创建账号', exact: true }).click();
      await expect(page.getByRole('heading', { name: '账号创建成功，已自动登录', exact: true })).toBeVisible();
      expect(state.submissions).toHaveLength(1);
      if (withNickname) expect(state.submissions[0]?.displayName).toBe('星河🌟');
      else expect(state.submissions[0]).not.toHaveProperty('displayName');
      expect(state.codeCount()).toBe(1);
      await page.getByRole('button', { name: '进入提示词优化', exact: true }).click();
      await expect(page).toHaveURL(/\/workbench$/);
      await expect(page.locator('.topbar')).toContainText(withNickname ? '星河🌟' : kind === 'phone' ? '新用户' : 'nickname');
    });
  }
}

test('昵称只出现在注册表单，切换登录与重开注册时清空旧昵称', async ({ page }) => {
  await registrationEnvironment(page);
  await page.goto('/login?mode=register');
  await page.getByRole('textbox', { name: '昵称', exact: true }).fill('尚未提交的昵称');
  await page.getByRole('button', { name: '已有账号？使用手机号或邮箱登录', exact: true }).click();
  await expect(page.getByRole('textbox', { name: '昵称', exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: '立即注册', exact: true }).click();
  await expect(page.getByRole('textbox', { name: '昵称', exact: true })).toHaveValue('');
});

test('浏览器昵称输入限制为80字符，不把超长内容静默提交到后端', async ({ page }) => {
  const state = await registrationEnvironment(page);
  await page.goto('/login?mode=register');
  const nickname = page.getByRole('textbox', { name: '昵称', exact: true });
  await nickname.fill('名'.repeat(81));
  expect((await nickname.inputValue()).length).toBeLessThanOrEqual(80);
  expect(state.submissions).toHaveLength(0);
});
