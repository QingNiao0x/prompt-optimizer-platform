# 来源保真检测与修订职责分离原件

本批停在检测门槛，NATURAL/18未采用，默认仍为第四版。实际13次DETECT（计入REVIEW），全部HTTP 200、finish=stop，共10125 Token；PATCH、生成、Plan和实际作品均为0。`fixture-detection-assessment.json`明确`semanticGatePassed=false`，因此没有开展条件修订，也没有执行计划内后续61次生成/Plan/作品调用。

`transports.jsonl`和`http-results.json`保留全部首次请求和响应；`fixture-detection-checks.json`保留产品域机械核对。**结构预期8/13不是语义通过率**：五份正常稿均无问题，说明本次检测不再触发四份已有正常稿的多余改写，新增来源类别守护也通过；但真实邻居、真实AA和旧N4缺目标稿均漏检。旧遗漏稿与完整目标N4正常稿继续分开保留。

B4、B5不是没有语义问题。B4同一句既强制分组又遗漏原总额均分目标；B5同一片段既扩大部署授权又把助手建议写成用户确认。模型识别这些真实偏差，却将多种问题指向同一文字，产品按“片段不得重叠或重复”拒绝整份。应区分语义识别、模型定位表达与产品接受契约，不能把两份简单归为模型漏检或反过来记作产品流程成功。逐条判断与归因见`manual-assessment.json`。

职责分工在本次五份正常稿上减少误改触发，但复杂失败原件仍未通过。未调用修订，所以不能宣称过删、重复、标点接缝、覆盖重建或最终作品已解决。本批停止继续Flash采样；若开展模型能力受控原件重放，须另行冻结同一来源、正文和协议，不由本批自动发起。

`preflight-protocol.json`、`preflight-source-manifest.json`、`preflight-revision.json`及前检环境原件保留。零付费调用前恢复来源类别边界并补两份守护，同时工具兼容产品支持的JSON围栏；不是模型质量重试。`preflight-preservation.json`记录前后指纹差异和兼容源文件。当前`source-manifest.json`保留1149条指纹，`candidate-source/`保存9份匹配的关键Java源码（含Findings），见`candidate-source-checks.json`。

`offline-validation.json`记录冻结前174项受影响检查与46项重叠边界复查，不能相加成独立测试数，也不代表真实语义通过。公开原件完整性和常见凭证/隐藏推理检查见`privacy-and-integrity.json`。本目录不含私有登录凭据、Cookie、验证码或运行日志。

`summary.json`、`closure.json`和`final-call-audit.json`记录：累计1073次（基线70、候选1003）；本任务实际387、已分配796、已关闭未用409、尚未分配228。本批120次中未用107次关闭，不可转用。整体质量目标仍未完成；批次已关闭，不得重新执行真实调用。
