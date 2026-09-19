# Web 前端工程

计划技术栈：Vue 3、Vite、TypeScript、Element Plus、Pinia、Vue Router、Axios。

当前已完成提示词工作台、项目文件上下文读取、Java/常见配置文件识别、`.xlsx/.xls` 表格文本提取、上下文感知 Plan Mode 和提示词增强页面。有文件时执行“初步检索与分析 → 弹窗确认 → 携带答案再次检索 → 最终生成”；无文件时直接进入计划提问。

## 本地启动

在项目根目录启动后端后，进入前端目录执行：

```powershell
cd apps\web
npm.cmd run dev
```

浏览器打开 `http://127.0.0.1:5173`。Vite 会把 `/api` 请求代理到 `http://localhost:8080`；如果后端使用其他端口，可设置 `VITE_API_PROXY_TARGET`。

## 前端验证

```powershell
npm.cmd test
npm.cmd run build
npm.cmd run test:e2e -- --project=desktop-chrome
```

端到端测试使用固定的模拟接口响应，不会调用真实模型或消耗 API 额度。

完整的前端与后端调用顺序见[上下文感知 Plan Mode 实现教学](../../docs/development/上下文感知Plan-Mode实现教学.md)。
