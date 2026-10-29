# 冻结后追加简略委托题

正式解封依据为父目录`completion-holdout-release.json`，manifest及四文件逐字校验见`seal-verification.json`。追加决定发生在原四题任何真实模型调用前，原因是原题全部充分，不能独自验证有用补全；没有替换或删除原四题。

运行映射为U01/U02/U03，对应原封存C01/C02/C03，原题不改。`prepare-ui-inputs.mjs`仅白名单提取`originalPrompt`、`materials.title/text`，把原title作为Markdown标题，text逐字保留，按原顺序制作共享附件。答案卡、机会项、评审和隔离记录均不进入实际请求。来源映射与哈希见`case-index.json`。

`captures/<Uxx>/`保存真实工作台文件上传后的DTO、截图和校验。实际材料各1/1/2份，无execution-only材料；最终POST被明确409拦截，未模拟增强成功、未调用模型。受控身份只用于零模型捕获，真实模型与固定合成账号由冻结阶段执行。

`generation-bundle.json`提供31/47六个增强作业，GENERATION上限24；生成输出8192。候选指导不根据这些题调优。逐题产品门必须先审核原目标、事实、条件及是否真实生成正文，通过后才运行：

```text
node completion-holdout-run/build-works-bundle.mjs <增强阶段> <产品门JSON绝对路径>
```

产品门结构为`{"cases":[{"id":"U01","versions":{"31":{"allow":true,"reason":"实际审阅依据"},"47":{"allow":true,"reason":"实际审阅依据"}}}]}`，其它题同理。不是仅看HTTP200或模型自评。作品原始/31/47各一次，2600输出，同附件文本/顺序/边界，不给答案卡；严重失败不得继续付费挑成功结果。

作品完成后用`materialize-blind-review.mjs <作品阶段>`生成匿名packet，评审只读`anonymous/`，不读`coordinator/`映射。封存卡中的机会项和示例不变成额外硬要求，不按更长、更多章节或特定方案给分。评价重点是原任务完成、有用设计、事实保真与用户具体还须补改什么。充分需求持平不算实质补全收益；这三题只是有限内部证据。
