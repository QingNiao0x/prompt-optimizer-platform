# 独立评阅收集与试用验收

两位评阅者各自阅读[匿名成稿](fresh-reader-packet.md)。只需逐篇写出首要阅读问题、哪些版本解决了它、A/B/C 的偏好先后或并列，以及有无重要语义变化。用代号区分两份记录。提交前分别完成，之后再解封版本。

接受文字答卷。需汇总时，把两份记录录入以下命令生成的空表：

```powershell
$evidence = 'docs/testing/evidence/text-polish-semantic-and-revision-2026-11-21'
$reviewTool = 'scripts/evaluation/text-polish-semantic/review-directed-holdout.cjs'
node $reviewTool template $evidence | Set-Content -Encoding utf8NoBOM tmp/directed-review-1.json
node $reviewTool template $evidence | Set-Content -Encoding utf8NoBOM tmp/directed-review-2.json
```

- `reviewer` 写代号，`resolved` 填解决主要阅读问题的匿名版本，例如 `["B"]`。没有则填 `[]`。
- `ranking` 从好到差排列，允许并列。例如 `[["B"],["A","C"]]` 表示 B 更好，A/C 并列。 `readingNote` 写出具体改好的句子或仍在的问题。
- `meaning` 对每份成稿标记 `retained`（关键含义保留）、`disputed`（待核）、`material`（重要变化）。后两项在 `note` 指明原文与成稿的对应位置。

```powershell
node $reviewTool summarize $evidence tmp/directed-review-1.json tmp/directed-review-2.json
```

汇总工具计算两人一致认可的核心改善和相对胜负，偏好分歧单独列出。任何语义疑点回到原文复核，再依据[冻结门槛](fresh-protocol.md)决定是否试用。工具只生成汇总，无工作台或模型访问。个人答卷仅保存在本地。

## 通过后从工作台验收

先在本地工作台启用仅覆盖「中文、轻度、用户明确提供首段编辑目标」的试用配置。使用现有正式合成账号，提交以下两项此前未送入模型的对照。

| 验收稿 | 设置 | 核对内容 |
| --- | --- | --- |
| 正例，机房临时卡交回 | 中文、轻度，请在首段先写交回时间和地点 | 首句包含周四 16 时前、交至东门服务台。代交仅限同组登记人员，回执核对后才签收。第二段的逾期处理范围及口径不被追加修订改变。 |
| 对照，同一中文通知 | 中文、轻度，补充要求只写「保留原段落」 | 实际执行记录应显示常规策略。生成、复制及历史可用。 |

正例使用的合成原文如下：

> 临时卡交回通知
>
> 为了便于完成本次临时卡的清点工作，现就归还有关事项进行说明。领用人须在周四 16 时前把临时卡交到东门服务台。本人无法到场的，只能请同组已登记人员代交。服务台核对回执后才签收。
>
> 到期未交回的卡只列入待核清单。是否停用另行确认。

验收时比较初稿和交付稿，核对一次追加修订及其耗时、复制的纯正文和历史保存的实际策略。重复提交同一请求标识要复用原结果。关闭试用后以同一输入核对常规路径。观察结果达到门槛后再另选语言和幅度的题集，独立验收对应范围。
