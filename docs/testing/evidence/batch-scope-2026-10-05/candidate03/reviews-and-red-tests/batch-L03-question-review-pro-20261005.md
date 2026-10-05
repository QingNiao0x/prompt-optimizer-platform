# 候选03长提示词 Pro 实际Plan问题审核

- 仅使用本轮公开原始需求/资料与实际 `*-deepseek-v4-pro-plan.json`；没有读取隐藏答案卡。实际题干、完整候选的标签/说明/答案、示例及推荐字段均已核对。
- 16案例，15成功Plan共34条实际问题；CD-01-L为502 `RESULT_INVALID`、3次尝试，回答保留空对象表示无可审核题目，不伪造成功。
- 回答保存为 `tmp/batch-L03-reviewed-pro-20261005.json`，34条qid全部匹配，最长100字符。同轮两模型面对相同公开事实，语义相同的问题沿用同一事实边界，新增题单独审核。
- 所有实际选项的推荐标记均为false；这不证明候选答案均合法，也不证明没有低价值问题。专业验收尚未进行。
- 初期计划可能超过30分钟TTL，不能通过改写回答或覆盖首次失败解决；本文件只交审核结果，具体过期由真实执行记录保留。

|案例|实际问题ID与判断|本轮风险或边界|
|---|---|---|
|CD-01-L 软件|Plan失败，无题可答|502首轮证据保留，不改成“需求完整无问题”。|
|CD-02-L 技术文档|`history_retention_conflict`是已有对照要求；`local_debug_section`真未知范围|仍给采用旧说明或新草稿；回答保留永久/180天两方与草稿未批准，不在交接任务中决定生效制度。|
|CD-03-L 指南|`report_example_type`题面混合信息|先说类型未定、后给季度工作总结示例；沿用材料已有示例，不自造新的学习报告需求。没有再追问展示章节。|
|CD-04-L 机关报告|`backlog_threshold`已要求指出差异；`comparison_period`真未知|候选use_ledger把“未注明生效”改为“未生效”，为确定性事实失真；其他采用候选违背不择一。5/3工作日、同比是否需要及基期是否可用分别保留。|
|CD-05-L 论文|`interaction_rounds`、`rater_disagreement`真未知|例示3轮/每轮2题/2分阈值/第三人仲裁/kappa无公开批准，未被采用。固定20任务、3次重复、2名评审不改变。|
|CD-06-L 数据分析|`denominator_choice`、`completion_valid_condition`真未知；`missing_presentation`混合已知呈现与未知口径；`status_mapping`已知未映射|denominator的both说明要求展示计算结果，与本题不计算冲突。缺失当未完成改变未知状态。status_mapping三个候选全是择一或自建映射，缺少符合原任务的保留差异候选；必须自定义回答。|
|CD-07-L 医院运营|`observation_window`、`statistical_unit`真未知；`channel_definition`是新且有业务影响的未定归属；`cancellation_grace_period`混合未知边界与已要求保留的差异|旧24h/草稿12h采用候选仍越过未建立权威性的边界。首次渠道定义没有现行依据，例示首次创建不是已确认规则。电话/网页/窗口集合不重选。|
|CD-08-L 医学研究|`yll_life_table`、`arriaga_comparison`、`population_denominator`真未知；`age_group_aggregation`混合对照与新边界；`unknown_sex_handling`、`disease_code_mapping`真未知|aggregate_10yr答案直接指定85+，而公开资料未给最高开放年龄组，为候选中的事实补编；不能因为描述写“如85+”就把答案变成确定规则。ICD等FT示例仅示意，不能冒充已经批准的映射。|
|CD-09-L 新闻|无问题|与资料完整、只要两标题和正文一致；最终篇幅与成品事实另验。|
|CD-10-L 法律|`primary_draft`、`review_focus`真未知|不指定主文本时并列差异，不做确定合同摘要；承租方、高中低优先级不重问。保留未签署与证据不足边界。|
|CD-11-L 合成金融|`return_type`真未知；`adjustment_handling`已有未知状态；`volume_unit_conflict`已要求保留|仍提供假设未复权、采用字典单位，违背未知复权不得默认及单位不择一要求；自定义保持未知/来源。不做真实市场投资建议。|
|CD-12-L 教学|无问题|未重问既定条件，尚不构成课程专业有效性验收。|
|CD-13-L 翻译|`access_pass_name`是暂译与正式名状态|本轮明确暂译“入场凭证（正式名称待确认）”，不采用删除审核状态的use_generic，不虚构官方名称。未出现access pass仅词匹配的推荐。|
|CD-14-L 纪要|`review_frequency_decision`、`page_adjustment_owner`有原始需求明确Plan询问要求；`dictionary_date_conflict_resolution`重复询问已有保留要求|对前两题不武断记为系统新增无用提问；日期候选仍可择一，回答同时保留15/18和批准未知，不能把字典责任、期限传播到页面。|
|CD-15-L 材料对照|`cancel_window_status`真未知；`age_rule_resolution`询问可能新增批准结果|“是否已有新批准”是可影响结果的信息，但当前没有该证据，只能保持未知；不能把样例口头48h或草稿A已批/B撤回当事实。年龄与取消窗口分别处理。|
|CD-16-L 排期|`speaker_c_morning_only`、`arrangement_preference`真未知|二选题未给未知选项但允许自定义，因此保持未知。均衡说明“使两个场地同时有活动”可能过强，资料允许并行不要求满排；仍遵守资源和七主题边界。|

## 本轮主要风险与处理

1. 保留对照的任务继续变成选生效口径，跨文档原意仍需进入最终正文；已确认“对照”不应被问题的采用选项反转。
2. CD04未注明生效被写成未生效、CD08未给开放年龄边界被写成85+，属于直接可定位候选错误；未采纳也需保留为交互风险。
3. 缺失当未完成、自建COMPLETE/DONE映射、默认未复权等仍在候选中；推荐全部false不代表这些候选安全。
4. 真未知的观察窗口、主单位、首次渠道定义、人口分母、寿命表、性别U使用和编码映射均保留，未因过滤对照问题而删除。
5. 当前只交审核证据，不修改冻结代码，不进行追加API调用或用成功重跑覆盖首轮失败。早期PlanTTL是否已过由后续真实执行记录区分，不能算业务生成成功。
