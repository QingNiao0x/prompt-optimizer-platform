# 工程脚本

规划放置格式化、契约校验、数据库迁移检查、测试和本地环境辅助脚本。脚本应保持幂等，不在未确认目标目录的情况下删除或覆盖文件。

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
