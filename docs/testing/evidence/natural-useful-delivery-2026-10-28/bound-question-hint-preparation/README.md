只准备两次真实请求的机制诊断；没有调用模型、创建53、修改产品或target。

两份输入来自已闭52 PREPARE请求，仅在原user JSON末尾追加`boundQuestionHints`。每条只含实际questionId、既有actualAnswers.N sourceId及对应真实PlanQuestion.hint原值。原system、消息数量和顺序、旧JSON字段及其顺序、完整材料、题答、模型和参数保持不变；不携带选项或作用域投影，不增加来源、事实、回答或权限。

`bundle.json`沿现有exact-approved EXEC入口：CONFIRMED、UNKNOWN各最多一次，Flash/.2/8192/thinking disabled/JSON/stream false。阶段上限EXECUTION=2，其余=0；没有协议修复、质量重试或默认writer阶段。

须先等待18作品阶段闭批，再由infra冻结并计数执行。每份结果交root检查；若任一状态发生语义失败则停止尚未开始的机制调用，不由脚本自动判断语义或继续writer。严格协议校验通过不能代替该检查。

事先判据：CONFIRMED的actions、response和requirements应共同表达真实回答更新后的边界，不并列落实旧冲突值；UNKNOWN不得把未选取值落实为当前实现义务；两者均保留独立要求、原权限与全部实际题答。缺失来源、未知字段、截断等仍按冻结52实际reader/resolve拒绝。失败原件及旧52结论保留，不能补写后改判。

`provenance.json`记录闭批原件及hint来源哈希；`request-equivalence-proof.json`证明移除唯一新增字段后恢复完整原user消息字节和捕获request整体。此处不声称恢复历史未保存的外层HTTP空白字节。

离线复核（不修改文件、不发网络请求）：

```powershell
node docs/testing/evidence/natural-useful-delivery-2026-10-28/bound-question-hint-preparation/build.mjs --verify
```

无参数构造只用于首次生成，已有文件会拒绝覆盖；不读取账号、环境配置或封存题。
