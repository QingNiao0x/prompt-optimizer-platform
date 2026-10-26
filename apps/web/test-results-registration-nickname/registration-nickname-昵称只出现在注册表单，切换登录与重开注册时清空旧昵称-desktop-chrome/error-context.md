# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: registration-nickname.spec.ts >> 昵称只出现在注册表单，切换登录与重开注册时清空旧昵称
- Location: e2e\registration-nickname.spec.ts:71:1

# Error details

```
Test timeout of 60000ms exceeded.
```

```
Error: locator.click: Test timeout of 60000ms exceeded.
Call log:
  - waiting for getByRole('button', { name: '已有账号？使用手机号或邮箱登录', exact: true })
    - locator resolved to <button type="button" data-v-1357ea3a=""> 已有账号？使用手机号或邮箱登录 </button>
  - attempting click action
    2 × waiting for element to be visible, enabled and stable
      - element is not stable
    - retrying click action
    - waiting 20ms
    2 × waiting for element to be visible, enabled and stable
      - element is not stable
    - retrying click action
      - waiting 100ms
    - waiting for element to be visible, enabled and stable
    - element is not stable
  109 × retrying click action
        - waiting 500ms
        - waiting for element to be visible, enabled and stable
        - element is visible, enabled and stable
        - scrolling into view if needed
        - done scrolling
        - element is outside of the viewport
  - retrying click action
    - waiting 500ms

```

# Page snapshot

```yaml
- generic [ref=e1]:
  - generic [ref=e3]:
    - main:
      - region "登录提示"
    - button "切换为深色主题" [ref=e4] [cursor=pointer]
  - dialog [ref=e7]:
    - button "关闭登录弹窗" [ref=e8] [cursor=pointer]
    - document [ref=e9]:
      - banner [ref=e10]:
        - generic [ref=e11]:
          - paragraph [ref=e12]: PromptOptimizer
          - heading "创建账号" [level=2] [ref=e13]
        - button "关闭" [ref=e14] [cursor=pointer]: ×
      - generic [ref=e15]:
        - generic [ref=e16]:
          - generic [ref=e17]:
            - text: 邮箱/手机号
            - textbox "邮箱/手机号" [ref=e18]:
              - /placeholder: 请输入邮箱地址或手机号
            - generic [ref=e19]: 可使用邮箱或中国大陆 11 位手机号注册。
          - generic [ref=e20]:
            - text: 昵称（选填）
            - textbox "昵称" [active] [ref=e23]:
              - /placeholder: 请输入昵称（选填）
              - text: 尚未提交的昵称
            - generic [ref=e24]: 用于账户展示，最多80个字符；不填写将自动生成。
          - generic [ref=e25]:
            - text: 邮箱验证码
            - generic [ref=e26]:
              - textbox "邮箱验证码" [ref=e27]:
                - /placeholder: 请输入 6 位验证码
              - button "获取邮箱验证码" [disabled] [ref=e28]
          - generic [ref=e29]:
            - text: 密码
            - generic [ref=e30]:
              - textbox "密码 显示密码 已输入 0 个字符，至少 8 位，且须同时包含字母和数字。" [ref=e31]:
                - /placeholder: 请输入密码
              - button "显示密码" [ref=e32] [cursor=pointer]
            - generic [ref=e36]: 已输入 0 个字符，至少 8 位，且须同时包含字母和数字。
          - generic [ref=e37]:
            - text: 确认密码
            - generic [ref=e38]:
              - textbox "确认密码 显示确认密码" [ref=e39]:
                - /placeholder: 请再次输入密码
              - button "显示确认密码" [ref=e40] [cursor=pointer]
          - generic [ref=e44]:
            - checkbox "我已阅读并同意 用户协议 与 隐私政策" [ref=e45]
            - generic [ref=e46]:
              - text: 我已阅读并同意
              - link "用户协议" [ref=e47] [cursor=pointer]:
                - /url: /terms
              - text: 与
              - link "隐私政策" [ref=e48] [cursor=pointer]:
                - /url: /privacy
          - button "创建账号" [disabled] [ref=e49]
          - generic [ref=e50]: 创建账号前：请先填写邮箱地址或中国大陆 11 位手机号。
          - generic [ref=e51]: 验证码 5 分钟内有效，60 秒后可重发；密码至少 8 位，且须同时包含字母和数字。
        - complementary "微信扫码登录" [ref=e52]:
          - paragraph [ref=e53]: 微信扫码登录
          - paragraph [ref=e76]: 打开微信扫一扫
          - generic [ref=e77]: 扫码能力将在微信开放平台接入后启用
      - contentinfo [ref=e78]:
        - button "短信登录" [ref=e79] [cursor=pointer]
        - button "手机号密码登录" [ref=e81] [cursor=pointer]
        - button "已有账号？使用手机号或邮箱登录" [ref=e83] [cursor=pointer]
```

# Test source

```ts
  1  | import { expect, test, type Page } from '@playwright/test';
  2  | import { testUser } from './authFixture';
  3  | 
  4  | /** 注册界面只使用合成身份，全部API均拦截，不访问真实账号或验证码服务。 */
  5  | async function registrationEnvironment(page: Page) {
  6  |   let user: typeof testUser & { phoneBound?: boolean } | undefined;
  7  |   const submissions: Array<Record<string, string>> = [];
  8  |   let codes = 0;
  9  |   await page.route('**/api/**', async route => {
  10 |     const path = new URL(route.request().url()).pathname;
  11 |     const ok = (data: unknown) => route.fulfill({ json: { requestId: 'nickname-fixture', data } });
  12 |     if (path === '/api/v1/auth/me') return user ? ok(user) : route.fulfill({ status: 401, json: {} });
  13 |     if (path === '/api/v1/auth/csrf') return ok({ token: 'synthetic-csrf' });
  14 |     if (path === '/api/v1/auth/capabilities') return ok({ phoneRegistration: true, smsLogin: true, phoneBinding: false });
  15 |     if (path === '/api/v1/auth/captcha') return route.fulfill({ contentType: 'image/svg+xml',
  16 |       body: '<svg xmlns="http://www.w3.org/2000/svg" width="120" height="40"><text x="10" y="25">ABCD</text></svg>' });
  17 |     if (path === '/api/v1/auth/registration-code' || path === '/api/v1/auth/sms/challenges') {
  18 |       codes++;
  19 |       return ok({ challengeId: 'synthetic-challenge', expiresInSeconds: 300, resendAfterSeconds: 60 });
  20 |     }
  21 |     if (path === '/api/v1/auth/register' || path === '/api/v1/auth/phone/register') {
  22 |       const body = route.request().postDataJSON() as Record<string, string>;
  23 |       submissions.push(body);
  24 |       user = { ...testUser, email: body.email ?? '', phoneBound: !!body.phone,
  25 |         displayName: body.displayName || (body.phone ? '新用户' : 'nickname') };
  26 |       return ok(user);
  27 |     }
  28 |     if (path === '/api/v1/models') return ok([]);
  29 |     if (path.startsWith('/api/v1/analytics/')) return ok(path.endsWith('/context') ? { userId: user?.userId, loginSessionId: null } : null);
  30 |     return route.fulfill({ status: 503, json: { error: { code: 'UNMOCKED_TEST_PATH', message: '未模拟该路径。' } } });
  31 |   });
  32 |   return { submissions, codeCount: () => codes };
  33 | }
  34 | 
  35 | for (const kind of ['email', 'phone'] as const) {
  36 |   for (const withNickname of [false, true]) {
  37 |     test(`${kind}注册${withNickname ? '保存可选昵称' : '可不填写昵称'}并显示服务端返回的名称`, async ({ page }, testInfo) => {
  38 |       const state = await registrationEnvironment(page);
  39 |       await page.goto('/login?mode=register');
  40 |       const form = page.locator('.login-modal__form');
  41 |       const nickname = form.getByRole('textbox', { name: '昵称', exact: true });
  42 |       await expect(nickname).toBeVisible();
  43 |       await expect(nickname).not.toHaveAttribute('required');
  44 |       await expect(nickname).toHaveAttribute('maxlength', '80');
  45 |       await form.getByLabel('邮箱/手机号', { exact: true }).fill(kind === 'email' ? 'nickname@example.test' : '13800000000');
  46 |       if (kind === 'phone') await form.getByLabel('图形验证码', { exact: true }).fill('ABCD');
  47 |       await form.getByRole('button', { name: kind === 'email' ? '获取邮箱验证码' : '获取短信验证码', exact: true }).click();
  48 |       await expect.poll(state.codeCount).toBe(1);
  49 |       // 昵称属于可选资料，发码后编辑它不能使当前邮箱/手机号的挑战失效或额外发送验证码。
  50 |       if (withNickname) await nickname.fill('  星河🌟  ');
  51 |       await form.getByPlaceholder('请输入 6 位验证码').fill('123456');
  52 |       await form.getByPlaceholder('请输入密码', { exact: true }).fill('Password1');
  53 |       await form.getByPlaceholder('请再次输入密码', { exact: true }).fill('Password1');
  54 |       await form.getByRole('checkbox').check();
  55 |       if (withNickname && kind === 'email') {
  56 |         await page.screenshot({ path: testInfo.outputPath('registration-with-nickname.png'), animations: 'disabled' });
  57 |       }
  58 |       await form.getByRole('button', { name: '创建账号', exact: true }).click();
  59 |       await expect(page.getByRole('heading', { name: '账号创建成功，已自动登录', exact: true })).toBeVisible();
  60 |       expect(state.submissions).toHaveLength(1);
  61 |       if (withNickname) expect(state.submissions[0]?.displayName).toBe('星河🌟');
  62 |       else expect(state.submissions[0]).not.toHaveProperty('displayName');
  63 |       expect(state.codeCount()).toBe(1);
  64 |       await page.getByRole('button', { name: '进入提示词优化', exact: true }).click();
  65 |       await expect(page).toHaveURL(/\/workbench$/);
  66 |       await expect(page.locator('.topbar')).toContainText(withNickname ? '星河🌟' : kind === 'phone' ? '新用户' : 'nickname');
  67 |     });
  68 |   }
  69 | }
  70 | 
  71 | test('昵称只出现在注册表单，切换登录与重开注册时清空旧昵称', async ({ page }) => {
  72 |   await registrationEnvironment(page);
  73 |   await page.goto('/login?mode=register');
  74 |   await page.getByRole('textbox', { name: '昵称', exact: true }).fill('尚未提交的昵称');
> 75 |   await page.getByRole('button', { name: '已有账号？使用手机号或邮箱登录', exact: true }).click();
     |                                                                            ^ Error: locator.click: Test timeout of 60000ms exceeded.
  76 |   await expect(page.getByRole('textbox', { name: '昵称', exact: true })).toHaveCount(0);
  77 |   await page.getByRole('button', { name: '立即注册', exact: true }).click();
  78 |   await expect(page.getByRole('textbox', { name: '昵称', exact: true })).toHaveValue('');
  79 | });
  80 | 
  81 | test('浏览器昵称输入限制为80字符，不把超长内容静默提交到后端', async ({ page }) => {
  82 |   const state = await registrationEnvironment(page);
  83 |   await page.goto('/login?mode=register');
  84 |   const nickname = page.getByRole('textbox', { name: '昵称', exact: true });
  85 |   await nickname.fill('名'.repeat(81));
  86 |   expect((await nickname.inputValue()).length).toBeLessThanOrEqual(80);
  87 |   expect(state.submissions).toHaveLength(0);
  88 | });
  89 | 
```