# 文章祛AI味本机证据

2026-10-25，工程实现与限定真实评测。完整结论见[报告](../../文章祛AI味实现与真实评测-2026-10-25.md)，使用方式见[说明](../../../development/文章祛AI味实现与使用说明.md)。

- `protocol*.json`、`review-protocol.json`：各阶段执行前冻结题目、模型、参数和上限。`protocol.json` 原始 `closed` 字段按冻结原件保留，最终关闭状态以 `ledger.json` 为准。
- `ledger.json`：累计 48/50，生成 42、付费评审 6，0 失败／协议修复／质量重试；已关闭，未用 2 次释放，不能自动继续执行。
- `*-flash.json`、`*-pro.json`：输入、实际平台响应及逐次审计元数据。文件名前缀区分 v1、v2、v3；没有前缀的是 v1。
- `TextPolishInstructions-v*.java`：三个指导版本快照，不参与生产构建。v1 剩余任务停止，v2 承诺遗漏及 v3 目的省略均保留。
- `review-*.json`：六份付费匿名 AI 辅助评审，包含误判和相互矛盾，不能作为专家或随机双盲结论。
- `business.json`：真实编辑／继续润色／版本冲突／权限／删除检查。首次剪贴板检查 false 保留；`clipboard.json` 的最终浏览器原生读取规范化换行后通过。`clipboard-native.json` 的跨会话系统读取尝试未通过，不用于成功结论。
- `desktop-light.png`、`desktop-dark-final.png`、`mobile-dark-final.png`：实际现有主题及窄屏三档。早期 `desktop-dark.png` 的主题操作不完整，不作为最终视觉证据；`visual-final.json` 记录纠正后的检查。
- `statistics.json`：实际 Token 与样本内延迟。币种费用未知。
- `validation.json`、`verification/`：定向工程检查摘要与本轮保存的前端／构建／浏览器日志；共享浏览器 18 项来自同会话前序工具执行，未伪造原始日志。
- `harness/`：冻结的执行入口，仅引用本机已有账号与受保护环境，不包含密钥和凭据。依赖仓库开发工具及专用本机服务；原账已关闭，不应把归档脚本当作新调用授权。
- `manifest.json`：最终源码、配置、报告、验证入口及原件的 SHA-256，便于核对交付范围；不包含账号文件、密钥环境或私有历史备份。

测试服务使用本机 PostgreSQL、既有 Windows Redis 3.2.100；生产目标 Redis 7、正式部署与容量未在本批验证。全部文章为合成输入，账号引用仅保留不可逆范围摘要。
