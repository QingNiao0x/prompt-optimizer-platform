# 需求保真边界修复证据

只读验证：`python verify-archive.py`，不调用外部模型。

- `original-p1/`：原 P1 分层原件与旧审核标签，保留用于更正归因。
- `candidate-*`、`final-age-*`、`final-data-*`：实际增强及各次尝试；最终数据范围版本为 `final-data-*`。
- `release-age-*`、`release-method-*`：最终源码的完整请求一致重放，零网络调用。
- `works-*`：全部18份真实作品，含先前候选；最终12份的准确映射以 `final-work-input-proof.json` 为准。
- `real-call-audit.json`：只统计真实 transport，30次请求及119694 Token。
- `final-test-summary.json`、`surefire-reports/`：相关回归，1740通过、6跳过，并发类排除。
- `source-changes/`、`repair-source-delta.patch`：本轮增强修复源码及测试，与此前未提交P1区分。
- `release-candidate-source.json`：评测主源码摘要；外部邮箱存储改动见独立差异记录。
- `protocol*.json`：采样前登记的固定范围和发现新问题后的有限补验。

归档工具沿用原执行路径，付费重跑须使用已授权范围并另建输出，不能覆盖本目录。
详情及人工审核结论见上级需求保真边界报告，不能由HTTP成功推断业务通过。
