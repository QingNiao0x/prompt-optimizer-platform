# M03 Pro Plan 独立公开输入审核

输入范围仅为 `tmp/prompt-comparison/batch-scenes-M-20261005-03/cases.json` 的本题原始提示词及公开 files，以及同目录本轮 `*-deepseek_deepseek-v4-pro-plan.json` 的实际 `plan.questions`。未读取 docs 中隐藏答案或事实卡，未使用 Pro 的自动生成 factCards 作为事实依据，未借用旧 qid，未读取 Flash 答案值，未进行模型调用。16份 Pro 均为 COLLECTED，共34个实际问题、77个选项，已逐项检查。对应 JSON 只使用实际 qid；CD-09-M、CD-12-M 无问题，记录为空对象。

34个问题均允许自定义答案；77个选项的 recommended 均为 false，recommendationReason 均为空，因此本轮没有实际弱推荐可评分。无推荐标记不证明选项安全。下文另外记录未标推荐的弱候选、越权推断与填写示例风险。自定义答案用于继承公开事实、保持未知和纠正已定要求，不能用答案合理反推问题质量通过。

## CD-01-M 软件开发

- 实际 qid：`missing_region_handling`。真正未知是地区缺失时的具体展示、核验及处置；问题切中公开尚待明确，但候选不完整。
- 已知重复：地区条件必须可核验且满足、地区缺失不是成功、未知须区别明确不符及无候选，均已定。
- 选项逐项：`exclude_candidate` 把无法核验写成不满足，属于明确状态反转；`show_disabled` 未反转事实，但展示并禁用是尚未选择的新行为，不能代选；`require_verification` 引入资料未提供的地区补齐来源与流程，不能无依据默认补齐，亦未交代如何保留未知类别。
- 弱推荐：无实际推荐。总体风险是未知准入状态被候选文字压成不符，或业务未知变成补齐执行流程；自定义回答不选择任何候选。
- 依据：rawPrompt已知资料1、尚待明确；`materials/software/current-brief.md` 的地区缺失分支。

## CD-02-M 技术与项目文档

- 实际 qid：`debug_section`、`history_retention_conflict`。前者是具体未决交付范围，后者重问已经明确的冲突呈现要求。
- 已知重复：永久保留与180天草稿必须只列差异、不擅自采用；未知的是实际有效策略，用户并未要求本轮批准策略。
- `debug_section` 选项：`include_debug` 与 `exclude_debug` 都是可供真实用户作出的范围选择，无明显约束反转；没有用户新增选择时须保持未知。
- `history_retention_conflict` 选项：`list_conflict_only` 符合已定要求；`adopt_legacy` 与 `adopt_draft` 都违反不采用任一方，后者还将尚未批准草稿写成现状。安全选项存在不能抵消另外两项违约。
- 弱推荐：无。问题质量风险明确存在于已定冲突处理被包装成择一决策；summary也称其为未决问题。
- 依据：rawPrompt尚待明确；`materials/project_docs/legacy-note.md`。

## CD-03-M 一般写作与用户指南

- 实际 qid：`report_example_focus`。工作汇报还是学习报告确实未决定，不能依据附件季度工作总结示例自动选工作汇报。
- 已知重复：读者、800至1200个中文字符、添加上下文、编号步骤、3至5项常见问题均已定，本轮没有重新提问这些信息。
- 选项逐项：`work_report`、`study_report` 都是允许的真实选择，没有明显违约，但均不能由审核者代选。缺少明确的保持未知选项，由 customAnswer 保留。
- 弱推荐：无。题目价值与公开缺口一致；答案未提供新的示例方向，不能以本轮自定义答案证明最终指南质量。
- 依据：rawPrompt尚待明确；`materials/user_guide/current-brief.md` 的合成季度示例。

## CD-04-M 机关单位报告

- 实际 qid：`baseline_comparison`、`backlog_threshold`、`missing_data_handling`。比较意愿与同口径基期是否存在是真未知；积压差异呈现已定；空白含义和覆盖说明已定，具体新增统计不应由候选顺带确定。
- `baseline_comparison` 选项：`no_comparison` 是未选择的业务决定；`compare_with_baseline` 的标签称已有基期、描述称用户将提供，状态不一致，当前输入也没有基期；`compare_no_baseline` 指无基期仍尝试定性描述变化，易编造去年同期证据，不能因不算提升比例便判安全。
- `backlog_threshold` 选项：`use_5_days`、`use_3_days` 违反只列差异；`report_both` 可用于并列定义与出处，但不能扩大为已有两口径统计数据。问题直接问采用哪个标准，误把已定呈现变成决策。
- `missing_data_handling` 选项：`exclude_missing` 若无覆盖边界会落实公开材料警示的删除风险，其answer虽要求说明数量仍未说明已登记样本边界；`separate_report` 与前项实质重叠，较好保留覆盖影响；`treat_as_unknown` 把存在缺失直接升级为不算任何相关指标，依据不足。不能把常规覆盖说明强制变成三选一。
- 弱推荐：无。summary新增缺失处理“关键业务决定”，有扩大问卷的风险。未核验实际台账，不能编造缺失数量或平均值。
- 依据：rawPrompt尚待明确、约束；`materials/public_report/current-brief.md` 六月缺失及积压分歧；`legacy-note.md`。

## CD-05-M 论文与研究设计

- 实际 qid：`interaction_rounds`、`reviewer_disagreement`、`info_matched_control`。最大交互轮次、终止规则、分歧处理和一致性评价是真未知；相同已确认事实而不经历问答的公平原则已定。
- `interaction_rounds` 选项：`fixed_three_rounds` 的label/answer固定三轮而description最多三轮，且额外引入每轮中间计划；不可混淆实验三次重复；`until_no_new_info` 没给确定上限，answer中的五轮只是示例，不能当成已预注册；`adaptive_termination` 未定义信息增益测量及阈值，不能形成可复现规则。三项均不能代选。
- `reviewer_disagreement` 选项：`third_reviewer` 增加第三评审资源，answer又把中位数或仲裁分并列为不同终值，尚未满足清晰规则；`consensus_discussion` 必须保留两名原始独立评分，不能用讨论后分数取代一致性评价证据；`average_scores` 在评分尺度未定时先定算术均值，且没有具体一致性方法。均未解决全部原始缺口。
- `info_matched_control` 选项：`prefilled_answers` 把整张答案卡等同实际获得的全部确认事实，可能多给未被提问的答案；`static_summary` 按实际获得事实严格等量且无新增解释时与已定规则相容。输入载体的常规整理不应重新包装成必须回答的业务选择。
- 弱推荐：无。实际问题没有独立追问一致性指标，相关hint并不等于该缺口已被确定；回答继续保留未知，不新增预注册决策。
- 依据：rawPrompt已定设计、尚待明确；`materials/research_design/current-brief.md` 信息匹配、匿名独立盲评和变量尺度限制。

## CD-06-M 数据分析

- 实际 qid：`completion_rate_denominator`、`missing_record_presentation`、`status_field_mapping`。分母、缺失处理是真未知；两种分母口径对照与状态代码差异的呈现已经明确。
- `completion_rate_denominator` 选项：`registered`、`valid` 允许真实选择但不能代选；二者的“人数”用语弱化公开材料的登记事件/问卷计数与未给去重规则；`both` 与已定口径对照相近，但answer易被解释为批准两种完成率计算，而不是仅对照定义。分子是否要求同时完成和有效仍未定。
- `missing_record_presentation` 选项：`separate_column` 保留未知状态，但它是呈现规则，不能单独回答完成率处理；`exclude` 和 `include_as_unknown` 在分母未定时先确定缺失计入规则，二者都不是当前事实，排除尤其不能自动执行。该组选项把展示与计算两个维度混在单选中。
- `status_field_mapping` 选项：`use_complete`、`use_done` 都违反原始提示词“不擅自采用其中一项”；`map_both` 在没有映射证据时暗示建立关系，也违背仅列差异。没有安全的并列差异选项，必须用自定义答案纠正。
- 弱推荐：无。sum/type/count或计算公式没有实际数据证明，完成状态与有效状态不能相互替代。
- 依据：rawPrompt尚待明确、交付；`materials/data_analysis/current-brief.md`、`legacy-note.md`。

## CD-07-M 医院运营

- 实际 qid：`observation_window`、`cancellation_grace_period`、`statistical_unit`。观察窗口与渠道主统计单位真未知；取消边界实际有效规则未知，但并列两来源不择一的交付要求已定。
- `observation_window` 无选项；24小时、48小时、7天均为未证实示例，不是已有边界。问题未覆盖窗口起点、数据延迟与状态复核规则，不能以填写某时长自动完成定义。
- `cancellation_grace_period` 选项：`use_legacy_24h`、`use_draft_12h` 都违反不采用任何一方；`present_both` 的answer符合已定并列要求，但description“由读者自行判断”可能转嫁政策选择，不能写成有效规则。
- `statistical_unit` 选项：`use_events`、`use_unique_patients` 为尚未选择的口径；后者未给可去重数据或规则；`use_both` 增加两套统计且承诺结果，现有字典不证明可行。三者都不能自动作为最终口径。首次渠道与变更渠道定义尚未得到实际问题覆盖。
- 弱推荐：无。渠道三类没有被重问，符合已定事实继承；UNKNOWN不计爽约、无临床和处罚扩张的边界必须继续保留。
- 依据：rawPrompt尚待明确；`materials/hospital_operations/current-brief.md`、`legacy-note.md`。

## CD-08-M 医学研究

- 实际 qid：`reference_life_table`、`arriaga_comparison`、`population_denominator`、`age_group_mapping`。前三项针对真实方法缺口；第四项把已经确定的并列差异呈现变为最终分析选择。
- 前三项无选项；寿命表名称/年份、男女年龄别死亡率分解对象、统计局常住人口及5岁匹配都只是生成示例，没有公开依据，不能自动采用或作为专业有效性认证。尤其不能以例子出现就认定Arriaga差异指标适用。
- `age_group_mapping` 选项：`use_5yr`、`use_10yr` 都违反只指出差异不擅自采用；后者还默认相邻两组即可合并，未覆盖最高开放年龄组和边界；`keep_both` 增加两套实际分析及聚合映射，而非仅列定义冲突，也超出已定要求。无完全安全的仅并列差异选项。
- 弱推荐：无。公开材料另有性别U的总量/分层处理、实际疾病映射、标准人口权重和月登记完整性未确定；未出独立问题不代表这些已解决。方案应保留前提，不声称已有完整CSV、批准寿命表、伦理或数据授权。
- 依据：rawPrompt已知资料、尚待明确与约束；`materials/medical_research/current-brief.md`、`legacy-note.md`。本审核只检验来源与未知状态，未核验医学方法专业有效性。

## CD-09-M 新闻稿

- 实际 questions为空，符合公开输入信息完整、直接输出的要求。
- 已知重复：未重问三个受众、600至800中文字符、两个标题、日期和反馈方式。
- 候选/弱推荐：无选项、无推荐。不能据此认定最终新闻稿已满足字数、三项价值和事实边界；本轮只审查Plan。
- 真正未知：公开输入没有要求本轮补充的业务决定，不能人为新增问卷。
- 依据：rawPrompt尚待明确及`materials/press_release/current-brief.md`。

## CD-10-M 法律与律师文书

- 实际 qid：`primary_draft`、`review_focus`、`deposit_discrepancy`。前两项是公开未决决定；押金差异的并列呈现已定。
- `primary_draft` 选项：`draft_a`、`draft_b` 可由真实用户明确复核基准，但不能因此认为草稿已生效；`both_equal` 可用于暂时并列整理，却不能由审核者提升为用户决定永久不设主文本。三项均不代选。
- `review_focus` 选项：`ongoing`、`early_exit` 为真实业务选择；`balanced` 增加同等权重安排，也没有被选定。不能把承租方和优先级既定事实替换掉。
- `deposit_discrepancy` 选项：`list_only` 的不额外标注易省略邮件未确认、未签署与来源状态；`flag_for_review` 固定为高优先级缺少与尚未确定目标的对应依据。两个选项都没完整承接既定呈现，不应强制提问整理顺序。
- 弱推荐：无。未查询法律，不生成条文效力或付款义务；自定义回答保留材料差异，不构成真实法律意见。
- 依据：rawPrompt尚待明确和约束；`materials/legal_memo/current-brief.md`、`legacy-note.md`。

## CD-11-M 金融与股票合成风险分析

- 实际 qid：`return_type`、`adjustment_method`、`volume_unit_discrepancy`。收益率定义与复权元数据真未知；成交量差异的并列呈现已定，字段字典的“合成手数”是已知来源事实。
- `return_type` 选项：`simple`、`log` 是可确认的候选定义，不能代选。具体公式示意不代表已有计算、年化或投资建议。
- `adjustment_method` 选项：`none`、`forward`、`backward` 只有新增可信资料时才能确认；`unknown`符合当前状态。没有自动推荐某方式。
- `volume_unit_discrepancy` 选项：`use_lot` 单独采用而省掉冲突不满足已定任务；`use_share` 还可能替换数据字典与引入无依据换算；`note_discrepancy` 符合并列来源，但必须保留换算未提供。此问题不应把已定呈现转成择一单位批准。
- 弱推荐：无。未知复权不表示发生过真实拆股，20个交易日不支持长期预测；未查询外部行情。
- 依据：rawPrompt已知资料2和尚待明确；`materials/financial_risk/current-brief.md`、`legacy-note.md`。

## CD-12-M 教育教学

- 实际 questions为空，符合公开输入信息完整的要求。
- 已知重复：未重问45分钟、各环节时长、30人十组三人、无电子设备、两级各三题、分离答案页等。
- 候选/弱推荐：无。空问题只说明没有额外问卷，不能证明后续教案教学内容、正真分数边界、时间总和及数轴等值解释已经正确。
- 真正未知：没有公开要求补充的业务决定，常规例题与问法由编写者按既定范围组织。
- 依据：rawPrompt及`materials/education/current-brief.md`。

## CD-13-M 翻译与术语保真

- 实际 qid：`access_pass_translation`。正式中文名称未确认是真未知；问法应区别正式批准术语与临时措辞选择，不让填写一个名称自动变成批准事实。
- 无选项；入场证、通行证、访问卡都是未批准示例，不能代选。测试例子永久会员卡明确不能作为术语。
- 已知重复：河湾开放日、取消/延期条件、日期时间、仅译文与原段落对应已定，未被重问。
- 弱推荐：无。答案只保留正式名称未确认，不能新增译者说明、会员资格或活动成功事实。
- 依据：rawPrompt原文和尚待明确；`materials/scene-13/current-brief.md`、`term-test.txt`。

## CD-14-M 会议纪要与行动项

- 实际 qid：`review_frequency`、`page_adjustment_owner`、`dictionary_submission_date`。后续复盘决定和负责人未提供；公开会议原文已确定这次不作频率决定、页面负责人未指定。日期冲突必须并列是已定要求。
- `review_frequency` 选项：`not_decided`符合当前记录；`decided_weekly`、`decided_other` 直接声称会议已决定，与主持人“这次不作决定”冲突。若要询问后续新决定，必须说明新增证据与时间，不能改写历史发言。题目虽对应raw显式确认请求，仍含已知状态重复。
- `page_adjustment_owner` 无选项；张三、李四是无依据姓名示例，不能回填。小林字典职责不能扩张为页面职责；直接写未指定是当前已定缺失处理。
- `dictionary_submission_date` 选项：`date_1015`、`date_1018` 违反保留两方出处不择一；`date_unconfirmed`符合当前状态，但须保留出处和批准未知。问题“以哪个为准”与summary“需澄清”扩大了用户要求。
- 弱推荐：无。真正未知保留在待确认或行动项缺失字段，不替参会者批准频率、负责人、期限。
- 依据：rawPrompt原文、已知资料、尚待明确；`materials/scene-14/current-brief.md`、`unapproved-draft.md`。

## CD-15-M 材料整理与版本核对

- 实际 qid：`cancellation_window`、`draft_approval_status`。取消窗口是否有正式决定真未知；两草稿批准状态未知与并列保留已定，未提供新批准信息。
- `cancellation_window` 选项：`no_formal_decision` 将现行说明未覆盖扩大成确定无正式决定，违反缺失不等于不存在；`formal_decision_exists` 只有用户确实提供新决定和出处时才可确认，当前不能采用。缺少保持“是否存在未知”的安全选项。
- `draft_approval_status` 选项：`both_unapproved` 的label/description确定均未批准，answer却写均未确定，三处状态不一致；`one_approved`、`both_approved` 需要新的批准证据，不能作为当前材料事实。该问题仅因“需要保留分歧”而追问新增批准，不能把未知自动升级为正式规则。
- 弱推荐：无。活动乙24小时不能补活动甲规则；16/18周岁来源对应必须完整保留，测试全部通过不是审批状态。
- 依据：rawPrompt约束和尚待明确；`materials/scene-15/current-brief.md`、`unapproved-draft.md`。

## CD-16-M 通用约束排期

- 实际 qid：`speaker_c_morning_restriction`、`arrangement_priority`。两项均对应公开未确认条件，不代选。
- `speaker_c_morning_restriction` 选项：`morning_only`是未确认的新硬限制；`no_restriction` 从无上午限制扩大为全天可参加，不能排除其他时段或日期限制。两项均无保持未知选项，必须 customAnswer。
- `arrangement_priority` 选项：`concentrate_theme`、`balance_halls` 作为真实选择可接受，不能默认。若只是交付两个草案和取舍，候选方向可以比较，但不代表偏好已批准；具体主题、场次数与丙其他可用性未提供时不能造出需求事实。
- 已知重复：两日日期、09至17点、12至13点午休、40分钟讲座加10分钟整理、场地容量、甲乙日期限制在summary继承，没有再问。
- 弱推荐：无。没有真实场次需求和讲者完整可用性，不能以问题合理证明最终排期已可行；不得执行预约或联络。
- 依据：rawPrompt已知资料、尚待明确；`materials/scene-16/current-brief.md`。

## 审核边界

本轮问题存在多个已定冲突呈现被重问、违约择一候选和未知状态反转；因此不判 Pro Plan 问题质量整体通过。34份自定义回答均未新增执行决策，只继承公开规则与明确保留未知。没有对最终增强正文、下游作品、真实法律/医学/金融方法或排期实施进行验证，也没有把API采集成功视为业务通过。
