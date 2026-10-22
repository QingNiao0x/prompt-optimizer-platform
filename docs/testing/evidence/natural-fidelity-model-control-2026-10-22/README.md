# Pro检测模型单变量对照原件

本批已关闭，不采用Pro检测策略，默认仍为第四版。实际13次DETECT（REVIEW），全部HTTP 200、finish=stop，共9770 Token；PATCH、生成、Plan和作品均为0，不继续后续调用。

后续跨批复核补充[金标可信度审计](gold-confidence-audit.json)：真实AA的严重越界归因不足，旧N4的严重遗漏未定，两者后续只作质量观察；真实邻居稿的无依据用户知识状态及其下游传播证据明确，礼貌表达仍有价值。审计更正定性置信度，旧protocol、金标、结果和9/13机械计数不改，Pro仍不采用。旧assessment中的AA/legacy强归因应结合此审计阅读。

`model-control-pair-checks.json`逐对核对13份实际上游请求：**唯一变化是`/model`由deepseek-flash变为deepseek-v4-pro**。system、参数及user消息字符串完全相同，任务业务JSON包括其中的modelId也逐字保留deepseek:deepseek-flash。协议modelId及评测服务目录选择随测试模型改为Pro，这是外部路由控制，不是业务资料变化；没有发现其他请求差异。

`transports.jsonl`和`http-results.json`保留全部请求、首次响应；`fixture-detection-checks.json`保留产品域结果。结构预期9/13，前批Flash为8/13，**不是语义通过率或质量采用依据**。两模型五份正常稿均无问题；Pro将B4/B5合为不重叠定位，解决这两份的结构接受问题，但新增B1无依据用户状态漏报，真实邻居仍漏检；真实AA及旧N4按当时冻结金标也未命中，其严重错误归因已由后续审计降为未定。未获得确定复杂原件的实质检测收益。逐条比较见`manual-assessment.json`，检测门禁`fixture-detection-assessment.json`为false。

本次每模型每稿只有一次，不能按品牌或一次机械计数推断唯一根因。没有调用修订或作品，不宣称已解决覆盖、标点、过删、复制或成品质量。Token略少不能直接解释为费用更低：本目录没有核对模型费率。另已核对原AA题：V4/V15增强分别2421/5105 Token，作品4172/4000 Token，不能声称分组功能导致作品Token增加。传输累计耗时见`cost-and-fixture-checks.json`，不等于完整用户端到端延迟。

`source-manifest.json`保留1149条冻结指纹；`candidate-source/`保存9份关键主源码，与前批职责分离快照及本批指纹逐字一致，见`candidate-source-checks.json`。既有174项离线检查及46项重叠复查仅为同实现证据副本，本批没有重新运行，不相加成独立测试量。公开导出、冻结原件保留与常见凭证/隐藏推理检查见`privacy-and-integrity.json`。

总账见`summary.json`、`closure.json`和`final-call-audit.json`：累计1086次（基线70、候选1016）；本任务实际400、已分配816、已关闭未用416、尚未分配208。本批20次中未用7次关闭，不可转用。用户整体质量目标仍未完成，不变更默认模型、模型目录或增强策略。

公开目录不含私有登录凭据、Cookie、验证码或运行日志；固定合成账号没有替换或新建。批次已关闭，不得重新执行真实调用，失败原件完整保留。
