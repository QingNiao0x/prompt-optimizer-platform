# 文章单次生成模型对照

从仓库根目录使用本机Java21和既有运行依赖文件tmp/image-prompt-runtime-classpath.txt，编译结果使用services/api/target/classes。运行入口从受保护的本机环境读取供应商配置，日志写入忽略的tmp目录。

```powershell
./scripts/evaluation/text-polish-model-comparison/run.ps1 inspect
./scripts/evaluation/text-polish-model-comparison/run.ps1 pilot
./scripts/evaluation/text-polish-model-comparison/run.ps1 expand
```

以上是本轮执行入口的归档说明。本轮账本已关闭，pilot拒绝续跑，expand还要求pilot-review.json通过。inspect仅用于初始目录探测，闭批原件应按manifest核对保留，新实验应另建目录与冻结协议。

本轮使用已有账号范围、文章模型准入和正式Provider。调用前原子持久化两次最大尝试预留，成功或失败依据标准对账事件结算。没有审计证据时保守计满预留并停止，崩溃遗留activeJob也阻止续跑。源码和输入的冻结哈希逐阶段复核，配置漂移阻止调用。实际执行6次后未达到收益及保真要求，扩大阶段被门禁阻止。

受影响路径通过真实6次调用及门禁检查。没有修改产品运行策略，未运行无关的页面或全仓库测试。详细作品、条件及限制见docs/testing/文章祛AI味单次生成模型对照验收-2026-11-10.md。
