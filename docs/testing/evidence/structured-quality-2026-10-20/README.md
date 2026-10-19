# STRUCTURED 专属修复与复验原件

完整结论见[节点报告](../../结构化增强职责收敛与真实复验-2026-10-20.md)。本批已关闭：23／26次，累计607→630，余3次关闭不转移。所有原始作品、失败、辅助评审及分歧保留；不是新的调用授权。

| 文件 | 作用与边界 |
| --- | --- |
| `cases.json`、`protocol*.json` | 同一三题、材料、实际回答、模型参数及三阶段修订；C3只重验受影响Plan |
| `source-manifest*.json` | 各候选源码、策略、脚本和运行副本的哈希；后续修改不冒充旧候选 |
| `transports.jsonl`、`http-results.json` | 全部23次传输及业务结果；包含一次协议修复，未保存认证头或隐藏推理 |
| `*-native-copy.md`、`*-candidate*-native-copy.md` | 当时平台API的完整复制正文；没有另补答案 |
| `*-browser-copy.md`、`browser-checks.json` | 最终选用三份真实响应的受控浏览器原生复制，与API／执行输入／真实历史一致；回放非新增真实增强 |
| `structured-plan-result.png` | 最终Plan结果的本机受控浏览器截图，合成材料与合成账号 |
| `actual-confirmation.json`、`plan-result.json`、`history-checks*.json` | 实际Plan问题、用户已批准合成答案、原账号所有者历史核对 |
| `independent-checks*.json` | 严格JSON、字段／null／顺序及通知120汉字检查。日期展开仅作观察，不代替交付审阅 |
| `review-mapping*.json`、`review-audit.json` | 两轮Flash／Pro独立辅助评审，均为同供应商；严格格式及逐字引文审计 |
| `review-normalization-audit.json` | 对最终Pro单层JSON围栏的离线解释，保留协议失败及一条非逐字引文 |
| `compact-isolation.json` | 六组HEAD／候选旧COMPACT或自动模式指令哈希相同，不声称语义全场景证明 |
| `validation-summary.json` | 最新选定Java报告去重324项、8项受控浏览器测试、3份真实响应回放，以及原有COMPACT断言失败的明确边界 |
| `final-source-check.json`、`public-evidence-check.json` | 最终主源码与C3一致；公开JSON无凭据或隐藏推理字段 |
| `public-evidence-check.initial.json` | 初次审计误把公开预算授权枚举当作认证头的记录；后按固定文件及固定枚举排除，不扩大凭据白名单 |
| `budget-closure.json` | 23次实际调用、阶段用量、83,639 Token、关闭余额与0新增账号 |
| `budget-limit-audit.json` | 闭批后明确区分正式额度累加与本批最大实际累计，不借旧余额或基线补候选 |
| `environment-after.json` | 日常9000恢复健康并加载C3，632个class一致；记录首次启动环境失败，9002／5175已清理 |
| `secret-value-check.json` | 仅在内存将公开文件与既有用户模型／加密密钥作精确比对，零命中，不记录密钥值 |

`start-*`／`invoke-*`／Java入口是对应冻结候选实际使用的评测工具，依赖本机忽略目录、原合成账号及已冻结副本；不得在闭批后重新发请求。原有哈希清单已经存在，启动工具不能覆盖冻结版本。闭批后的安全复核使用：

```powershell
python docs/testing/evidence/structured-quality-2026-10-20/audit-offline.py
```

此命令只读原件，并幂等核对离线结果，不登录、不调用模型。原始／增强实际作品是独立抽样中的同三道题，多个候选不算新增独立题，不据此计算总体胜率。
