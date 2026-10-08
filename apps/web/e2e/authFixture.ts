import type { Page } from '@playwright/test';

export const testUser = {
  userId: '00000000-0000-0000-0000-000000000102',
  tenantId: '00000000-0000-0000-0000-000000000101',
  workspaceId: '00000000-0000-0000-0000-000000000103',
  email: 'test@example.com', displayName: '测试用户', platformAdmin: false,
};

const analyticsIdentities = new WeakMap<Page, { userId: string; authenticated: () => boolean }>();

/** 交互回归显式模拟身份；真实 Cookie/密码校验由独立联调用例验证。 */
export const mockAuthentication = async (
  page: Page, initiallyAuthenticated = true, platformAdmin = false,
): Promise<void> => {
  let authenticated = initiallyAuthenticated;
  const currentUser = { ...testUser, platformAdmin };
  await page.route('**/api/v1/auth/**', async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith('/csrf')) {
      await route.fulfill({ status: 200, json: { data: { token: 'test-csrf' } } });
    } else if (path.endsWith('/capabilities')) {
      await route.fulfill({ status: 200, json: { data: { phoneRegistration: false, smsLogin: false, phoneBinding: false } } });
    } else if (path.endsWith('/captcha')) {
      // 登录表单会先加载图形验证码；模拟图片，不把该请求落到真实认证服务。
      await route.fulfill({ status: 200, contentType: 'image/svg+xml',
        body: '<svg xmlns="http://www.w3.org/2000/svg" width="120" height="40"><text x="8" y="26">ABCD</text></svg>' });
    } else if (path.endsWith('/registration-code')) {
      await route.fulfill({
        status: 200,
        json: { data: { resendAfterSeconds: 60, expiresInSeconds: 300 } },
      });
    } else if (path.endsWith('/register')) {
      const body = route.request().postDataJSON();
      if (body.verificationCode !== '123456') {
        await route.fulfill({ status: 400, json: { error: { message: '验证码错误。' } } });
        return;
      }
      authenticated = true;
      await route.fulfill({ status: 200, json: { data: { ...currentUser, email: body.email } } });
    } else if (path.endsWith('/login')) {
      if (route.request().postDataJSON().password !== 'test-password') {
        await route.fulfill({ status: 401, json: { error: { message: '邮箱或密码不正确。' } } });
        return;
      }
      authenticated = true;
      await route.fulfill({ status: 200, json: { data: currentUser } });
    } else if (path.endsWith('/logout')) {
      authenticated = false;
      await route.fulfill({ status: 200, json: { data: null } });
    } else {
      await route.fulfill({ status: authenticated ? 200 : 401, json: { data: authenticated ? currentUser : null } });
    }
  });
  const analyticsAlreadyInstalled = analyticsIdentities.has(page);
  analyticsIdentities.set(page, { userId: currentUser.userId, authenticated: () => authenticated });
  if (!analyticsAlreadyInstalled) {
    // 所有模拟业务页面都会安装采集队列；新增契约不能落到真实服务并触发 401 跳转。
    // 默认路由每页面只注册一次：切换模拟身份不能覆盖恢复专项用例后注册的故障路由。
    await page.route('**/api/v1/analytics/context', (route) => {
      const identity = analyticsIdentities.get(page)!;
      return route.fulfill({ status: identity.authenticated() ? 200 : 401,
        json: { data: identity.authenticated() ? { userId: identity.userId, loginSessionId: null } : null } });
    });
    await page.route('**/api/v1/analytics/events', (route) => route.fulfill({
      status: analyticsIdentities.get(page)!.authenticated() ? 200 : 401,
      json: { data: null },
    }));
  }
};
