# Edge 窗口最小化与 Win+D 排查报告

## 1. 结论

截至 2026-08-11，提示词优化工具的前端页面已被排除为本问题的原因：项目没有使用网页全屏 API、没有阻止 `Win+D` 对应的键盘事件，也没有覆盖浏览器标题栏的自定义窗口组件。

浏览器右上角的最小化按钮和 Windows 的 `Win+D` 属于操作系统窗口管理能力。普通网页不能合法拦截或接管这两项能力。因此，不能通过修改 Vue 页面代码修复该现象；修改无关前端代码只会带来新的交互风险。

后续应在 Edge 启动模式、浏览器扩展、键盘映射/远程桌面软件或 Windows 窗口管理环境中定位。优先执行本文的“最小对照测试”。

## 2. 已验证的环境与复现范围

| 项目 | 结果 |
| --- | --- |
| 前端地址 | `http://127.0.0.1:5173` |
| 浏览器 | Microsoft Edge `151.0.4129.72` |
| 页面全屏状态 | 未进入网页全屏 |
| `html` / `body` 溢出状态 | 均为 `visible` |
| 根节点定位方式 | `body` 为 `static` |
| `Meta`、`Meta + D` 事件 | 未被页面阻止 |
| Edge 受控策略 | 未发现 Kiosk、全屏或强制安装扩展策略 |
| Edge 进程 | 排查时无正在运行的 Edge，无法读取故障窗口的启动参数 |

> 操作系统版本无法由当前受限诊断会话读取。请在 Windows 中按 `Win + R`，输入 `winver`，将显示的版本记录到问题反馈中。

## 3. 项目代码检查结果

### 3.1 全局窗口与全屏能力

已搜索 `apps/web/src`，未发现以下调用：

- `requestFullscreen`、`exitFullscreen`、`fullscreenchange`；
- 全局 `window` / `document` 的 `keydown` 或 `keyup` 监听；
- `window.open`、`window.close`；
- 全局 `preventDefault()`；
- 覆盖整页并接管鼠标事件的固定定位遮罩。

唯一的键盘快捷键位于 [PromptComposer.vue](../../apps/web/src/components/prompt/PromptComposer.vue)：

```vue
@keydown.ctrl.enter.prevent="emit('optimize')"
@keydown.meta.enter.prevent="emit('optimize')"
```

它只在“原始提示词”文本框聚焦时响应 `Ctrl/⌘ + Enter`，用于提交优化请求；不匹配 `Win + D`，也不可能影响浏览器标题栏最小化按钮。

页面顶栏采用 `position: sticky`，仅在网页内容区域内保持可见，不能覆盖操作系统标题栏。

### 3.2 可重复的网页诊断

以下命令会临时启动 Vite，使用本机 Microsoft Edge 检查网页状态，然后自动关闭 Vite。它不会操作或关闭用户正在使用的浏览器窗口：

```powershell
Set-Location apps\web
python <webapp-testing技能目录>\scripts\with_server.py `
  --server "npm.cmd run dev -- --host 127.0.0.1" `
  --port 5173 `
  -- node ../../scripts/browser-window-diagnostic.mjs
```

本次执行输出的关键结果为：

```text
browserChannel: msedge
browserVersion: 151.0.4129.72
fullscreen: false
bodyOverflow: visible
htmlOverflow: visible
keyboardShortcutPrevention: false
violations: []
```

脚本文件：[browser-window-diagnostic.mjs](../../scripts/browser-window-diagnostic.mjs)。

## 4. Edge 与扩展检查结果

已读取 Edge 的只读策略配置。当前策略仅涉及首次运行、后台模式、侧边栏和工具栏，不包含 Kiosk、全屏或强制扩展安装设置。

默认资料目录中存在多个扩展，其中应优先排查会向网页注入脚本或增加侧边栏的扩展，例如：

- Violentmonkey（扩展 ID：`eeagobfjdenkkddmbclomhiblgggliao`）；
- Tampermonkey（扩展 ID：`iikmkjmpaadaobahmlepeloendndfphd`）；
- ChatGPT Sidebar（扩展 ID：`minfmdkpoboejckenbchpjbjjkbdebdm`）；
- Vue.js devtools（扩展 ID：`olofadcdnkkjdfgjcmjaadnlehnnihnl`）；
- 其他 Markdown、JSON、开发调试类扩展。

这不代表这些扩展一定有问题，只说明它们是最有价值的对照对象。

## 5. 最小对照测试（请按顺序执行）

1. 确认 Edge 不是全屏：按一次 `F11`；若此时恢复正常，原因是浏览器全屏模式，而非项目代码。
2. 在 Edge 按 `Ctrl + Shift + N` 打开 InPrivate 窗口。默认情况下扩展不会在 InPrivate 中运行。
3. 在 InPrivate 窗口打开 `http://127.0.0.1:5173`。
4. 分别测试：浏览器标题栏右上角的最小化按钮，以及 `Win + D`。
5. 再用同一个 InPrivate 窗口打开 Edge 新标签页，并对记事本执行一次相同的 `Win + D` 测试。

### 结果判定

| 结果 | 原因方向 | 后续处理 |
| --- | --- | --- |
| InPrivate 正常，普通 Edge 异常 | 扩展或普通资料配置 | 打开 `edge://extensions`，先全部停用，再优先逐个恢复 Violentmonkey、Tampermonkey、ChatGPT Sidebar 等扩展进行定位。定位后保持该扩展禁用或更新，不需要改项目代码。 |
| InPrivate 与普通 Edge 都异常，但记事本正常 | Edge 启动模式或 Edge 配置 | 打开 `edge://version`，记录“命令行”一栏；确认不是 `--app`、`--kiosk`、`--fullscreen` 启动。并在 `edge://apps` 查看是否以应用模式打开。 |
| Edge 新标签页也异常，记事本正常 | Edge 或其扩展环境 | 保留 `edge://version`、`edge://extensions` 截图，按上面的扩展对照继续定位。 |
| 记事本也无法通过 `Win + D` 返回桌面 | Windows、远程桌面、键盘映射或窗口管理工具 | 检查是否正在使用远程桌面、PowerToys 键盘管理器、键盘宏/热键软件或窗口管理工具；此时不应修改本项目代码。 |
| 仅本项目在 InPrivate 仍异常 | 需要补充现场证据 | 提供控制台错误截图、`edge://version` 截图和从打开页面到异常发生的录屏，再继续排查。 |

## 6. 变通方案与安全边界

- 可先使用 `Alt + Space` 后按 `N` 最小化当前窗口，验证 Windows 的窗口菜单是否正常；这只是临时操作方式，不是根因修复。
- 不建议修改注册表、关闭安全软件、降级 Edge 或随意删除浏览器资料目录。这些操作与当前证据不匹配，且可能扩大影响范围。
- 若最终确认是某个扩展导致，处理方式应仅限于更新、禁用或调整该扩展的站点访问权限；项目不需要也不应尝试绕过浏览器扩展。

## 7. 当前处理状态

- [x] 项目源码与网页状态检查完成。
- [x] 本机 Edge 通道的自动化诊断通过。
- [x] Edge 策略与已安装扩展完成只读检查。
- [ ] 需要在故障实际发生时完成一次 InPrivate 最小对照测试。
- [ ] 根据对照结果选择扩展、Edge 启动模式或 Windows 环境的对应修复路径。

## 8. 2026-08-12 补充排查（新增现象：仅本页面为活动标签时复现）

### 8.1 新增现象描述

- 只有当提示词优化项目页面是 Edge 当前活动标签时，点击最小化或 Win+D 才会在约 1-2 秒后自动恢复窗口并重新获得焦点。
- 先切换到其他标签页或应用再执行最小化 / Win+D，则表现正常。

该现象指向“页面失去焦点/可见性时，有代码在异步执行会唤起窗口的操作”，是本次补充排查的核心线索。

### 8.2 自动化探针结果（本机已验证）

使用 Playwright + Edge 通道加载 `http://127.0.0.1:5173`，在页面加载前挂钩以下 API：

- `window.focus`、`window.alert`、`window.confirm`、`window.prompt`、`window.open`、`window.print`
- `document.requestFullscreen`、`Element.prototype.requestFullscreen`
- `Notification.requestPermission`

随后模拟失焦事件（`blur`、`visibilitychange`、`pagehide`）并等待 3 秒，与用户观察到的“1-2 秒后恢复”时间窗口对齐。结果：

```text
focusApiCalls: []                         // 抢焦点类 API 调用 0 次
focusRelatedListenerRegistrations: 2 项
  1. window.pagehide / document.visibilitychange -> vue-router 滚动位置保存
  2. document.visibilitychange -> Vite 开发客户端 HMR 重连等待
```

其中 vue-router 的监听器只调用 `history.replaceState` 保存滚动位置，Vite 客户端只等待页面重新可见后重连 WebSocket，两者都不会唤起浏览器窗口。探针脚本为 `E:\tmp\focus-steal-probe.mjs`（临时文件，定位完成后可删除）。

### 8.3 后端能力核查

已检索 `services/api/src`，未发现以下会影响客户端浏览器行为的能力：

- SSE（`SseEmitter`、`text/event-stream`）
- WebSocket / STOMP 推送
- 文件下载响应头（`Content-Disposition: attachment`）
- 服务端重定向（`sendRedirect`、`RedirectView`）

后端不参与本次现象。

### 8.4 扩展排查结果

已读取 Edge 默认配置目录中的扩展清单，以下扩展具备全站或按需注入能力，是当前最大嫌疑：

| 扩展 | 扩展 ID | 注入能力 |
| --- | --- | --- |
| Violentmonkey | `eeagobfjdenkkddmbclomhiblgggliao` | `content_scripts: <all_urls>`，可运行用户脚本 |
| Tampermonkey | `iikmkjmpaadaobahmlepeloendndfphd` | `content_scripts: <all_urls>`，可运行用户脚本 |
| Vue.js devtools | `olofadcdnkkjdfgjcmjaadnlehnnihnl` | `content_scripts: <all_urls>` |
| JSON-handle | `pogcfcgaeocmmoadpbcfnponnlidodjn` | `content_scripts: *://*/*` |
| ChatGPT Sidebar | `minfmdkpoboejckenbchpjbjjkbdebdm` | `sidePanel + scripting`，可按需向任意页面注入 |

其余扩展影响面较小（Markdown Viewer 仅匹配 `file:///*`，Edge 内置组件扩展不注入业务页面）。在 Violentmonkey、Tampermonkey、ChatGPT Sidebar 的本地存储中未检索到明确包含 `localhost` / `127.0.0.1` / `5173` 的用户脚本文本（LevelDB 二进制检索可能不完整），因此不能排除用户脚本，也不能据此断言具体脚本。

### 8.5 根因判断

1. 项目代码：已排除。前端源码中不存在全屏、弹窗、`window.open`、全局焦点/可见性监听、定时唤起窗口等逻辑；自动化探针也确认页面失焦后无任何抢焦点 API 调用。
2. 浏览器扩展或用户脚本：最可能。注入脚本可在页面 `blur` / `visibilitychange` 后调用 `window.focus()`、`alert()` 或打开弹窗，导致浏览器窗口 1-2 秒后重新获得焦点，与“仅活动标签时复现”完全吻合。
3. Edge 启动模式 / 应用模式：其次，需通过 `edge://version` 的命令行参数和 `edge://apps` 确认。
4. Windows 窗口管理或安全软件：可能性较低（现象按页面区分）；若 InPrivate 与全部扩展禁用后仍复现，再排查 PowerToys、远程桌面、窗口管理工具。

### 8.6 决定性对照测试（按顺序执行）

1. 按 `Ctrl + Shift + N` 打开 InPrivate 窗口，分别打开 `http://127.0.0.1:5173` 与 `http://localhost:5173`，测试最小化和 Win+D。
   - InPrivate 正常 → 扩展或普通资料配置导致，进入第 2 步。
   - InPrivate 仍异常 → 关闭 DevTools（F12）后重测；仍异常则记录 `edge://version` 命令行，并检查 `edge://apps` 是否以应用模式打开。
2. 打开 `edge://extensions` 全部禁用后重测；若恢复正常，逐个启用并复测，启用顺序建议：Violentmonkey → Tampermonkey → ChatGPT Sidebar → Vue.js devtools → JSON-handle。
3. 定位到具体扩展后，处理方式为更新、禁用或移除该扩展，不需要修改项目代码。
4. 可使用 Chrome 打开同一页面做对照：`PLAYWRIGHT_BROWSER_CHANNEL=chrome node scripts/browser-window-diagnostic.mjs`。

### 8.7 结论与处理状态

- 不需要修改项目前端或后端代码：代码中不存在任何可被修复的“抢焦点”逻辑。
- 定位扩展前可使用的临时规避：先切换到其他标签页或应用再最小化；或按 `Alt + Space` 后按 `N` 最小化当前窗口。
- 不建议修改注册表、关闭安全软件或降级浏览器。
- [x] 2026-08-12 自动化探针完成（无抢焦点 API 调用）
- [x] 2026-08-12 后端能力核查完成（无 SSE / WebSocket / 下载 / 重定向）
- [x] 2026-08-12 扩展注入能力核查完成
- [ ] 等待用户在 InPrivate 下执行最小对照测试
- [ ] 根据对照结果定位具体扩展并完成处理

### 8.8 恢复记录（2026-08-12）

- 用户反馈：未执行 8.6 的 InPrivate 对照测试，但在日常使用中该问题已不再复现。
- 由于缺少“禁用扩展 / 关闭 DevTools / 重启浏览器”的对照记录，本次恢复的具体原因尚未确认。
- 临时探针脚本 `E:\tmp\focus-steal-probe.mjs` 仍保留；若问题复现，可先按 8.6 执行对照测试，再决定是否深入定位具体扩展。
