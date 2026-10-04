# 工程脚本

规划放置格式化、契约校验、数据库迁移检查、测试和本地环境辅助脚本。脚本应保持幂等，不在未确认目标目录的情况下删除或覆盖文件。

## 本地后端进程接管与重启

`manage-backend-local.ps1` 管理本仓库的本地 Java 后端。它继承当前进程环境变量，不读取受保护配置文件，不输出密钥或启动参数，也不会停止前端、浏览器、数据库或 Redis。

```powershell
# 查看端口、进程归属、启动时间和健康状态；不改变进程。
.\scripts\manage-backend-local.ps1 -Action Status

# 先编译并准备运行时依赖，再停止已核实属于本仓库的旧后端并启动新版本。
.\scripts\manage-backend-local.ps1 -Action Restart

# 已明确完成编译时可跳过重复编译；仍准备并核对运行时依赖。
.\scripts\manage-backend-local.ps1 -Action Restart -SkipCompile

# 只检查接管条件，不编译、不停止、不启动。
.\scripts\manage-backend-local.ps1 -Action Restart -CheckOnly
```

默认端口为 `9000`。脚本要求 Java 21、Maven，以及当前进程已注入 `MODEL_API_KEY`、`API_KEY_ENCRYPTION_SECRET`；优先沿用旧后端的 Java 路径，无旧进程时使用 `JAVA_HOME`。它先运行 `java/BackendNioPreflight.java` 核对 NIO 选择器及本机回环连接；版本、连接检查、配置或编译失败时不停止旧服务。监听者必须是命令行同时包含本仓库路径和应用主类的 Java 进程，否则拒绝接管；停止前再次核对 PID 和创建时间，避免 PID 复用导致误停。

脚本使用带 BOM 的 UTF-8，兼容 Windows PowerShell 5.1 与 PowerShell 7；修改时保留编码，避免中文字符串在 5.1 中被按系统代码页解析。此机默认临时目录中的 JDK NIO socket 连接失败，独立仓库目录对照通过，因此前置检查及新后端均使用 `tmp/backend-local-managed/nio` 作为 `jdk.net.unixdomain.tmpdir`。该参数仅影响这些本地子进程，不改变全局临时目录、选择器实现或业务配置。2026-10-04 已用 Windows PowerShell 5.1 完成多次自动重启、健康检查、登录检查及重复 Start 的幂等验证；最终候选重启后沿用原有登录完成三轮真实复验，未再要求用户手工加载后端。

每次运行保留独立的 `tmp/backend-local-managed/<runId>/` 日志和 `launch.json`，不覆盖旧证据。自动启动显式禁用 Flyway，数据库迁移仍需单独确认和执行。接管后的服务由脚本管理，IDE 的旧运行进程会结束；以后可直接调用同一脚本重启。重启会中断当前后端请求及进程内临时文档任务，因此应在没有进行中的上传、Plan 或模型调用时执行；Redis 登录会话与持久化历史是否仍可用须在启动后实际核对。

## 真实 Plan 业务验收

`plan-business-acceptance.mjs` 通过临时 Chrome 的正常登录会话运行原有 11 个跨行业样例，兼容当前图形验证码。先启动本地 API 与 Vite 前端，再在仓库根目录执行：

```powershell
$env:PLAN_EVAL_BASE_URL = 'http://127.0.0.1:5175'
node scripts/plan-business-acceptance.mjs
```

地址必须是本地前端地址；脚本使用 `apps/web` 已安装的 Playwright 和本机 Chrome，不安装依赖。操作者在浏览器输入密码及验证码，凭据不写入脚本或证据文件。登录后调用平台已发布默认模型，会产生正常模型额度消耗及优化历史。脚本不会扫描本机目录、修改配置或自动删除历史。

每次结果保存在独立的 `tmp/plan-business-acceptance/<runId>/`；JSON 含脱敏的合成问答、实际 Provider、Request ID、检查结果和最终提示词，拒绝覆盖旧证据。`plan-business-cases.mjs` 另提供审批冲突、通知渠道和 React→Vue 的固定夹具，供脚本导出的会话接口编排对照；这些额外场景不包含在默认 11 例 CLI 中。

`--interactive` 仅供本机受控调试：保持临时浏览器，并在回环地址 `127.0.0.1:9325` 开放调试连接；结束后关闭该临时浏览器。普通验收不需要此参数，不得对外暴露调试端口。

原 `plan-quality-eval.mjs` 的 `--release` 门槛及人工评审记录保持不变，其直接登录尚未适配当前验证码。真实运行与自动断言不能代替双人业务评审；当前失败与复现证据见 [2026-10-01 验收报告](../docs/testing/Plan-Mode真实业务验收-2026-10-01.md)。

- `browser-window-diagnostic.mjs`：检查前端是否进入网页全屏、锁定页面滚动或拦截快捷键。默认使用 Microsoft Edge；可通过 `PLAYWRIGHT_BROWSER_CHANNEL=chrome` 切换到 Chrome。它只读取页面状态，不会操作或关闭用户的浏览器窗口。
- `local-smoke-test.ps1`：检查后端健康接口和上下文分析接口；增加 `-RunOptimization` 才会调用提示词增强接口。默认只允许 Mock Provider，必须显式增加 `-AllowRealModel` 才允许真实模型请求。

## 本地联调

后端启动后，在项目根目录执行：

```powershell
.\scripts\local-smoke-test.ps1
```

如果已经用 Mock Provider 启动后端，可以执行完整接口冒烟测试：

```powershell
.\scripts\local-smoke-test.ps1 -RunOptimization
```

真实模型联调必须明确确认会消耗 API 额度：

```powershell
.\scripts\local-smoke-test.ps1 -RunOptimization -AllowRealModel
```

如果 API 不在 8080 端口：

```powershell
.\scripts\local-smoke-test.ps1 -ApiBaseUrl http://127.0.0.1:9090
```
# 本地脚本

## `local-smoke-test.ps1`

该脚本用于后端已经监听端口后的本地冒烟验证，默认只检查健康接口和上下文分析接口。

```powershell
.\scripts\local-smoke-test.ps1
```

需要验证优化接口时，必须先使用 `local-mock` 配置启动后端：

```powershell
.\scripts\local-smoke-test.ps1 -RunOptimization
```

脚本会确认响应来自 Mock Provider。只有明确要消耗真实模型额度时，才使用 `-AllowRealModel`；该参数不会替你配置 API Key，也不会绕过模型服务错误。

## 本地启动后端（安全加载 API Key）

可以在项目根目录创建 `.env.local`（已被 `.gitignore` 忽略），也可以直接配置 Windows 用户环境变量。两种方式至少需要：

```text
MODEL_API_KEY=sk-你的密钥
API_KEY_ENCRYPTION_SECRET=一串随机的加密密钥
```

`API_KEY_ENCRYPTION_SECRET` 可用 PowerShell 生成：

```powershell
[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
```

然后启动后端：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\start-backend-local.ps1
```

如果已经配置了 Windows 用户环境变量，`.env.local` 可以不存在，启动脚本会直接使用当前用户环境变量。修改用户环境变量后，需要重新打开 PowerShell 或 IDEA，使新进程继承最新值。

如果选择在 IDEA 的运行配置里填 Environment variables，密钥会保存在 `.idea` 目录（同样已被 Git 忽略）。上传 GitHub 前请先执行 `git status` 确认没有把 `.env.local`、`.idea` 或任何含密钥的文件加入暂存区。
