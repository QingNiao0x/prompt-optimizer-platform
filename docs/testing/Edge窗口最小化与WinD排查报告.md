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

## 9. 2026-08-14 全面复查与防御性修改

### 9.1 复查范围

本次按窗口管理异常重新审查以下文件：

- `apps/web/src/pages/PromptWorkbenchPage.vue`
- `apps/web/src/components/prompt/PromptComposer.vue`
- `apps/web/src/App.vue`
- `apps/web/vite.config.ts`
- `apps/web/index.html`
- `apps/web/e2e/prompt-workbench.spec.ts`

并全量搜索 `window.focus`、`element.focus()`、`autofocus`、`requestFullscreen`、`fullscreenElement`、`moveTo`、`resizeTo`、`setInterval`、`requestAnimationFrame`、`serviceWorker`、`beforeunload`、`visibilitychange`、全局 `addEventListener` 等模式。

### 9.2 复查结果

| 检查项 | 结果 |
| --- | --- |
| 窗口 API（focus/moveTo/resizeTo/open/close） | 应用代码未使用 |
| 全屏 API | 未使用 |
| 定时器与动画循环 | 未使用 `setInterval`，未使用 `requestAnimationFrame` 循环 |
| Service Worker / PWA Manifest | 未配置 |
| 全局键盘监听与 `preventDefault` | 不存在；仅文本域内的 `Ctrl/⌘ + Enter` 快捷提交 |
| 全屏遮罩、整页固定定位接管鼠标 | 不存在 |
| E2E 自动化对窗口的调用 | 仅测试内对下拉框执行一次 `focus()`，不进入生产页面 |
| `document.title` 修改 | 仅路由切换时更新标题，不涉及窗口管理 |

结论：与 2026-08-11、2026-08-12 两次排查一致，业务代码中不存在能够阻止 Windows 最小化或 `Win+D` 的窗口/焦点/全屏逻辑。浏览器内容区无法可靠接管操作系统级窗口管理。

### 9.3 唯一页面级焦点管理因素与防御性修改

Element Plus 的确认弹窗默认启用：

- 模态遮罩；
- 焦点陷阱（Focus Trap）；
- `lock-scroll`，即打开时给 `body` 设置 `overflow: hidden`。

这是页面内唯一主动改变全局焦点和滚动状态的机制。为排除“弹窗打开期间焦点陷阱干扰 Edge 窗口焦点”的可能性，已将 [PromptWorkbenchPage.vue](../../apps/web/src/pages/PromptWorkbenchPage.vue) 的确认弹窗改为非模态：

```vue
:modal="false"
:lock-scroll="false"
:append-to-body="false"
```

该修改不改变“应用/放弃”确认交互，只移除遮罩、焦点陷阱和全局滚动锁。若异常仅在确认弹窗打开时出现，此修改可消除页面侧变量；若与弹窗无关，则问题仍位于 Edge 扩展、Edge 启动模式或 Windows 窗口管理环境。

### 9.4 现场复现与定位要求

代码侧无法在自动化环境复现 Windows 最小化/Win+D 的故障窗口行为。请在异常发生时按第 8.6 节执行最小对照测试，并补充以下任一材料：

- `edge://version` 截图（重点记录“命令行”一栏）；
- `edge://extensions` 截图；
- 从打开页面到点击最小化、再执行 `Win+D` 的录屏；
- 异常时刻 Edge 开发者工具 Console/Network 面板截图。

得到上述材料后，才能区分扩展注入脚本、Edge 应用模式或系统窗口管理，避免把环境问题归因于项目代码。

### 9.5 2026-08-15 补充：InPrivate 正常、Chrome 正常、普通 Edge 仍异常

用户补充对照结果：

- InPrivate 窗口打开本页面：最小化与 Win+D 正常；
- Chrome 打开本页面：正常；
- 普通 Edge 即使禁用全部扩展：仍然异常。

该结果把问题范围缩小到“普通 Edge 配置/资料”，而不是页面业务代码。为兼容用户脚本或注入逻辑在页面失焦后调用 `window.focus/open/alert/confirm/prompt` 重新唤起窗口的行为，前端增加了页面隐藏期间的原生调用防护：

- 新增 `apps/web/src/windowFocusGuard.ts`；
- 在 `apps/web/src/main.ts` 中于应用挂载前加载；
- 页面 `document.hidden` 为真时吞掉上述原生调用；页面可见时行为不变。

该防护只能拦截页面同源世界里的调用，无法拦截浏览器扩展的隔离世界（Isolated World）调用。若修改后仍复现，优先执行下列普通 Edge 资料级修复：

1. 完全退出 Edge，并在任务管理器中确认没有 `msedge.exe` 残留后重新打开；扩展停用需要完整重启才彻底生效。
2. 打开 `edge://flags`，点击“重置所有设置”后重启 Edge。
3. 在“设置 → 系统与性能”中关闭：启动加速、关闭 Edge 后继续运行后台扩展和应用、效率模式；并在“保存资源”中把 `127.0.0.1` 加入“从不睡眠”列表。
4. 在 `edge://settings/content/all` 中分别清除 `http://127.0.0.1:5173` 与 `http://localhost:5173` 的站点数据。
5. 打开 `edge://apps`，确认本页面没有以 Edge 应用窗口方式安装。
6. 打开 `edge://version`，记录“命令行”一栏，确认没有 `--app`、`--kiosk`、`--fullscreen` 参数。
7. 用 `npm run preview` 打开生产构建再做一次对照，排除 Vite 开发服务器热更新客户端的影响。

复现时保持开发者工具 Console 打开：若出现 `[window-focus-guard] ignored window.xxx while page hidden`，说明确实有脚本在页面隐藏后尝试抢焦点，防护已将其拦截；若没有该日志但窗口仍恢复，则属于 Edge 资料级或系统级窗口管理问题，需要提供 `edge://version` 截图继续定位。

### 9.6 2026-08-15 暴力猴根因确认

用户对照测试确认：禁用 Violentmonkey（暴力猴）后，最小化和 Win+D 恢复正常；Chrome 无此问题；InPrivate 正常。

对 Edge 默认资料中的暴力猴扩展 `eeagobfjdenkkddmbclomhiblgggliao`（版本 2.46.0）进行只读检查，得到以下证据：

| 项目 | 结果 |
| --- | --- |
| `manifest.json` 的 `content_scripts` | `matches: <all_urls>`，`run_at: document_start` |
| 注入文件 | `injected-web.js`、`injected.js` |
| `injected-web.js` 对 `window.focus` 的处理 | 将其替换为向扩展后台发送 `TabFocus` 消息的桥接函数 |
| 已启用的用户脚本 | “解除网站不允许复制限制 1.1.7”（greasyfork 549231）、“全网VIP 3.1.9”（greasyfork 537189） |
| 上述脚本对 `localhost` / `127.0.0.1` / `5173` 的匹配 | 无匹配规则 |

结论：不是这两个用户脚本注入到本项目页面，而是暴力猴扩展本体在每个页面注入全局桥接。该桥接把 `window.focus` 转发为 `TabFocus` 消息；页面失焦后若发生一次 `window.focus` 调用，扩展后台可能重新激活标签页窗口，与“最小化后 1-2 秒窗口自动恢复”的现象吻合。

项目侧已通过 `windowFocusGuard.ts` 在页面隐藏时吞掉 `window.focus` 等原生调用，阻止桥接消息发出。开发模式下被拦截时会连同调用栈打印到 Console：

```text
[window-focus-guard] ignored window.focus while page hidden
```

验证方法：

1. 保持暴力猴启用，刷新本项目页面（确保使用最新构建）。
2. 打开 F12 Console，复现“点击最小化后窗口自动恢复”。
3. 若 Console 出现上述拦截日志，即证明桥接链路已被页面防护切断。
4. 若没有日志但窗口仍恢复，说明调用发生在暴力猴的隔离世界，页面无法拦截；此时处理方式是更新暴力猴，或在暴力猴设置中把 `127.0.0.1` / `localhost` 加入站点排除，而不是要求用户禁用插件。

### 9.7 2026-08-15 补充：隔离世界结论与边界

用户复测结果：启用暴力猴并加载最新页面后，Console 没有出现 `[window-focus-guard]` 拦截日志，但窗口仍会恢复。

该结果与扩展架构一致：

- Violentmonkey 的 `injected-web.js`、`injected.js` 是 Manifest V3 的 Content Script，默认运行在 Isolated World。
- `window.focus` 的重写和 `TabFocus` 消息桥接发生在 Isolated World；页面主世界（Main World）的 `windowFocusGuard.ts` 无法观察或拦截该世界里的调用，因此不会打印日志。
- 网页代码也没有权限调用 `chrome.tabs.update` 等扩展后台 API，因此无法阻止扩展后台重新激活窗口。

结论：

1. 项目业务代码仍不是根因；根因是 Violentmonkey 2.46.0 的全局注入桥接与 Edge 窗口焦点管理之间的交互。
2. 页面侧防护只能覆盖 Main World；Isolated World 与扩展后台行为超出网页能力边界，任何网站都无法“彻底修复”该类扩展行为。
3. 对本机的兼容处理是：更新 Violentmonkey；或在 Violentmonkey 的站点规则中为 `http://127.0.0.1:5173`、`http://localhost:5173` 增加排除，不需要整体禁用扩展。
4. 对最终用户：生产域名与本地开发地址不同，且页面侧防护会在 Main World 生效；若个别用户因同类扩展复现，仍应引导其更新扩展，而不是修改网站代码。
