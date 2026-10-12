# 冻结增强质量证据（2026-10-12）

对应上级《提示词增强质量修复与真实盲评》报告。样例均为本会话授权的七个合成任务，全部复用同一个普通测试账号；没有新增生产资料。**存在质量失败，不是上线批准。**

- `cases.json` 固定原始提示词、附件、确认答案、Plan／示例开关。`historical-unsupported-inference.md` 保留前轮收入推成份额／需求的实际失败；不冒充本轮基线复现。
- `baseline-r*` 是修改前记录。基线有效 Flash 在第二轮目录、Pro 在第一轮目录；第一次 Flash 路由标识错误也保留。stage／candidate 各目录均保留当时失败；`candidate5-r1/r2` 是最终冻结候选，不能挑选旧候选作品替换它。
- 每份完整链路保留 `input.json`、`platform.json`、`copied.md`、`history.json`、`work.md`、`execution.json`；失败时保存 `failure.json`。`traces/` 是增强和下游实际请求／返回，已去认证头和模型隐藏推理；平台 requestId 与下游 label 可关联。复制正文未经评测脚本补答案或润色。
- `source-hashes.json` 与 `baseline-source-hashes.json` 固定代码版本；`change-from-frozen-baseline.patch` 包含本轮相关源码变更。现有未提交的身份／SMTP 改动不属于本轮修复。数据库 V14 为未应用的注释迁移。
- `review/` 保存随机 A/B 包和两份独立 AI 辅助评审；评审期间只允许读取相应盲包，不提供映射。`*review-key.json` 和 `*blind-summary.json` 用于事后解盲。重复使用同一基线，不宣称统计显著；评审不是人工专家。
- `mechanical-audit.json` 按实际文件记录账号一致性、字数、格式、历史一致性、所有调用和供应商返回 Token；它不替代语义评审。`test-and-run-summaries.json` 仅摘录实际测试／采集状态，不包含登录或环境日志。
- `reproduce/` 保存采集、冻结、机械检查与盲包生成脚本。它们引用本地已正常登录的测试浏览器和专用 9001 实例，需要在授权环境重建；登录凭据、环境值、账号文件及运行时 classes 未归档。不可对新资料直接套用本轮发送授权。API 增强走正常编排与历史写入，下游只收到复制正文；实际模型是 `deepseek-flash`／`deepseek-v4-pro`，下游 temperature=0.2、max_tokens=8192、thinking disabled。
- 用量上限的调整及理由见三个追加协议。预算包括失败及有限重试，不是隐藏的无限采样。`manifest-sha256.json` 为其他归档文件的 SHA-256，便于复核完整性。
- 可在本仓库直接运行 `reproduce/validate-evidence.py` 复核归档校验和、源码冻结、账号数量、双评审完整性和报告链接；该命令不调用模型。其他采集脚本按原工作目录 `tmp/enhancement-quality-20261012` 重建，不能将归档目录直接当作运行环境。
