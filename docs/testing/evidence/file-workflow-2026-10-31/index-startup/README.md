# Edge 本地索引启动复验

- `before.txt`：旧库准备尚未返回时，错误显示扫描的定向失败反例。
- `units.txt` 与 `heartbeat-unit.txt`：87 项共享检查及补充目录心跳类 13 项，其中新增 1 项；共 88 项不同检查。
- `edge-browser.txt`：环境 `PLAYWRIGHT_BROWSER_CHANNEL=msedge`，6 项原生浏览器存储、恢复及界面检查。项目名沿用 `desktop-chrome`，实际启动的是 Edge；界面登录使用现有测试夹具，慢准备为受控延迟。
- `native-edge-report.json`：独立 Edge 读取本项目实际目录句柄，完成统计并主动在 200 文件检查点暂停，不是全目录导入成功证据。
- `preparing.png`、`resumed.png`：当前准备阶段、取消后重新选择并完成的界面。
- `candidate.json`：源文件摘要与基线；`build.txt`：类型检查及构建结果。

`native-edge.mjs` 从仓库根目录运行，启动隔离浏览器和临时 Vite，不访问用户 Edge 配置或付费模型。用户原浏览器的约 6 GB 索引内容未复制、修改或删除；仅汇总其目录文件大小和存储活动作为故障诊断线索。原现场具体卡在哪一步尚无 JavaScript 调用栈，不能把独立 Edge 成功写成原用户大型旧库迁移耗时已解决。
