# 原值题答末尾数据通道：受控机制消融可行性

结论：现有EXEC消融入口可直接复用，不需要修改产品、模型目录、harness或默认31。当前只生成可审阅草稿，未freeze、启动服务或发模型；总数仍177/500，52旧失败及封存题均保持原状。

## 已准备的两个具体请求

草稿`prepare-bundle.draft.json`含2个EXECUTION作业，版本仍52、每题一次、阶段硬上限2；来源分别是52闭批`registry52-plan-completion/traces/001.json`和`003.json`的真实PREPARE请求。

完整请求唯一差异是末尾多一条`role:user`、纯JSON数据消息：

```json
{"boundPlanAnswers":[{"sourceId":"actualAnswers.0","questionId":"本题原ID","question":"本题原题干","answer":"本题原答案"}]}
```

草稿中的值来自各自实际原件，不使用上面的示意文字。`sourceId`取原目录的`id`，并核`kind=BOUND_PLAN_ANSWER`；其余三个值逐字复制完整actualAnswers。UNKNOWN仍是“今天是否允许尚未决定”，没有改名为confirmed、复制推荐或未选择项、加优先级／覆盖指令、隐去原文，亦未填写任何registry。

删除新增的最后消息后，整个请求对象与闭批原请求完全相等；前两条message内容字符串逐字不变。52system、完整raw/context/actualAnswers/taskSources、`deepseek-flash`、8192、0.2、json_object、thinking disabled、stream false全部保持。仅新增156／124个字符的题答数据。原件SHA已按52闭批closure核对。

- Bundle SHA256：`b4cff4f46749407739abc9255f0f3c58773fb1cc5fea55c5b8555bcab31c5295`
- Provenance SHA256：`2a065ee41c1f6529b2175c27dd61c0864700bb386aa48b63223e2159b3f945fd`
- 逐项对照见`prepare-provenance.json`、`draft-verification.json`；未构造后续writer请求或oracle结果。

## 复用入口与预算

现有`freeze-live.mjs`已接受52、EXECUTION、8192及逐字approvedModelRequest。`/api/v2/evaluation/execute`使用既有固定身份、公开Flash目录和官方路由；传输拦截逐字核整个payload，允许多条user消息，发请求前计数。它不经过产品有界生成修复，所以每个job只有一次真实调用，协议错误不会自动补采。无需新增profile。

| 阶段 | 新调用上限 | 开启与停止条件 |
| --- | ---: | --- |
| 原值题答通道PREPARE重放 | 2 EXEC | 根任务另行批准后才freeze/start；两已见状态各一次。任一新旧混合、未知被代选、重要要求遗漏或合法协议失败即止，不重试挑结果。 |
| 条件性writer验证 | 另2 EXEC | 两份prepare均经实际严格reader/resolve及独立语义核对正确，才构造并另冻；任何失败不补采。 |

第一层最多177→179；只有第一层全过、根任务批准第二层后才最多181。均计机制调用，不计实际作品或新题通过。截断／超时／HTTP失败均占次数；未完整响应单列，不伪装为已获得语义结论。沿现有60秒模型读取／240秒runner，不能失败后改cap或超时再重试。

第一层通过标准看完整当前有效要求：确认路径不再把旧严格范围当当前限制；未知路径不默选任一今日边界，同时原独立文件／入口／行为限制及真实题答不丢。可以引用原始旧句作为证据，不能把“含旧关键词”机械判失败；协议及引用通过不替代语义审查。

## 第二层必须来自实际新prepare

若第一层通过，使用同52冻结runtime的实际`requirementPreparationReader`读取原响应，经`Preparation.resolve(originalInput)`产生scope和稳定reqId；不是把手写正确答案塞给writer。可复用已有guard的网络禁用严格解析／resolve路径，或最小只读回放辅助入口，不更改产品。

writer以52闭批002／004的真实请求为基底，仅把`taskScope/preparedRequirements`换成上述实际归一化结果，并追加与第一层相同的原值题答数据消息；除这两个派生字段外，完整原输入、system与参数全部保持。随后按实际writer reader、normalize及原来源／全部QA／安全／原稿与组装覆盖保护验证。源响应、映射及变更清单先冻结；不能只靠EXEC HTTP200称产品保护通过。

## 与已失败消融的区别及限制

旧scope消融删派生scope，duty消融改system职责标签，protocol消融删正文内部任务协议；本假设既不删资料／协议／保护，也不改指导，只观察“重复原值实际题答到最后独立数据消息”是否改变准备取舍。历史失败不重判。

这同时改变了消息位置、分层和重复显著性，不能冒称只隔离了位置或已经证明普遍有效。BOUND_PLAN_ANSWER代表真实绑定来源，不等于答案已经确定；UNKNOWN是关键反向检查。以后若应用到恶意题答，重复末尾消息也可能放大其中的指令，不能因这两题成功就扩大信任或增加优先级。

本阶段是闭批完整模型请求的机制重放：不续期旧Plan、不重新宣称所有权、不是普通UI全流程，也不是下游作品；通过最多只支持继续第二层，不能直接采用、切默认或解封留出题。
