import type { Page } from '@playwright/test';

export const testUser = {
  userId: '00000000-0000-0000-0000-000000000102',
  tenantId: '00000000-0000-0000-0000-000000000101',
  workspaceId: '00000000-0000-0000-0000-000000000103',
  email: 'test@example.com', displayName: '测试用户',
};

/** 交互回归显式模拟身份；真实 Cookie/密码校验由独立联调用例验证。 */
export const mockAuthentication = async (page: Page, initiallyAuthenticated = true): Promise<void> => {
  let authenticated = initiallyAuthenticated;
  await page.route('**/api/v1/auth/**', async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith('/csrf')) {
      await route.fulfill({ status: 200, json: { data: { token: 'test-csrf' } } });
    } else if (path.endsWith('/login')) {
      if (route.request().postDataJSON().password !== 'test-password') {
        await route.fulfill({ status: 401, json: { error: { message: '邮箱或密码不正确。' } } });
        return;
      }
      authenticated = true;
      await route.fulfill({ status: 200, json: { data: testUser } });
    } else if (path.endsWith('/logout')) {
      authenticated = false;
      await route.fulfill({ status: 200, json: { data: null } });
    } else {
      await route.fulfill({ status: authenticated ? 200 : 401, json: { data: authenticated ? testUser : null } });
    }
  });
};
