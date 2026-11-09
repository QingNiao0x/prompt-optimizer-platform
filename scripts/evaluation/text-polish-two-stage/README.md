# 文章两阶段编辑实验入口

完整原文在准备与改写两次请求中均保留，模型整理的清单作为editorNotes辅助材料，人工语义答案卡只供评阅。

当前执行协议位于docs/testing/evidence/text-polish-two-stage-2026-11-09/protocol.json，源码、指导和样本的哈希已冻结。修改实验代码或输入后须另建协议，原件保留。

## 执行

从仓库根目录使用Java21、Python3和PowerShell。沿用既有tmp/image-prompt-runtime-classpath.txt及services/api/target/classes，后端依赖或源码变化时先按仓库Maven设置重新编译并生成运行依赖路径。

```powershell
./scripts/evaluation/text-polish-two-stage/run.ps1 offline
./scripts/evaluation/text-polish-two-stage/run.ps1 pilot
```

pilot先检查预算，再启动隔离Spring环境。准备阶段曾因剩余5次低于最少9次而拒绝。随后用户授权本轮100次，authorization.json已记录新授权。本轮实际9次后按冻结门槛停止，ledger.json已关闭，入口拒绝续跑。报告和账本记录最终状态，新实验须重新冻结及核对适用授权。

脚本仅从本机既有受保护环境读取密钥，使用既有合成账号范围核验及平台当前模型准入。每次请求前预扣1次，持久化activeCall。没有协议修复或质量重试，故障时停止并保留预扣与原件，核账完成后再决定恢复。

成功完成pilot后逐项填写manual-review.json，引用实际输出证据，并保留基线判断。

```powershell
python scripts/evaluation/text-polish-two-stage/decide.py decide
./scripts/evaluation/text-polish-two-stage/run.ps1 expand
```

扩大条件包含人工语义条目全部保留、无新增事实、数字化及服务说明均有明确表达收益、对话文体兑现。自然短文允许原样保留。额外成本门槛由本轮开发者预先选择，两阶段端到端耗时及Token用量分别不得超过同题v3的3倍。门槛只适用于这次小范围实验，不代表产品全局SLA。

expand依赖pilot-review.json通过，仅覆盖商务与叙事的三种文体。初始样本统一使用CONVERSATIONAL与RESTRUCTURE，以覆盖已知失败路径。基线v3与候选的模型、温度、幅度和原文一致。仅改变编辑机制。

本实验调用现有受限RestClient、模型目录及并发门禁，独立组装准备与改写的JSON协议。评测代码对模型计划只验证结构和来源片段，语义完整性由人工答案卡检查。原始请求与响应保存于实验目录，技术日志保存在忽略的tmp路径。当前数据均为受控合成稿及用户提供的通用文章转录。


本轮已执行三篇9次真实调用，结果为PILOT_STOP，未扩大。逐项语义及成本结果见docs/testing/文章祛AI味两阶段编辑作品对照验收-2026-11-09.md。
