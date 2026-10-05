# 两轮下游作品独立 AI 辅助审阅汇总

本轮为 AI_ASSISTED 匿名审阅；行业专业验收仍为 PENDING，releaseApproval=false。两名审阅者分别审阅不同作品，不是每件作品的双人独立评分。

104 次计划生成，103 次成功作品均已审阅，输出、映射、原审阅及冻结输入哈希已核对；23+24=47 件严重违例作品。原始组也有违例，不能将47件全部归因于平台。原文件未覆盖。

|提示词组|计划作品|成功审阅|严重违例作品|
|---|---:|---:|---:|
|raw|24|23|11|
|direct|24|24|9|
|plan|24|24|12|
|raw_matched|16|16|6|
|direct_matched|16|16|9|

Plan细分：同信息 matched 为7/16严重违例；无新增回答的 initial 为5/8。同信息原始组6/16，direct_matched为9/16。数字只代表此批样本。

质量门槛比较：任何critical优先判不通过；双方有critical为均失败。仅加权分比较不作放行依据。所有配对仅使用同模型、同题、同信息条件。

|优化器|增强组/信息|可比/计划|质量门槛 胜/负/平/均失败/未可比|仅加权分 胜/负/平（样本）|
|---|---|---:|---|---|
|deepseek:deepseek-flash|direct/initial|12/12|2/3/2/5/0|4/5/3（12）|
|deepseek:deepseek-flash|plan/initial|4/4|1/2/0/1/0|2/2/0（4）|
|deepseek:deepseek-flash|plan/matched|8/8|2/4/0/2/0|2/5/1（8）|
|deepseek:deepseek-flash|direct_matched/matched|8/8|2/4/0/2/0|2/6/0（8）|
|deepseek:deepseek-v4-pro|direct/initial|11/12|7/1/0/3/1|9/2/0（11）|
|deepseek:deepseek-v4-pro|plan/initial|3/4|1/0/0/2/1|3/0/0（3）|
|deepseek:deepseek-v4-pro|plan/matched|8/8|5/0/0/3/0|6/2/0（8）|
|deepseek:deepseek-v4-pro|direct_matched/matched|8/8|2/2/1/3/0|3/4/1（8）|

两轮共64组计划配对，62组可比；一件raw作品失败同时影响direct与Plan的两个配对，保留未可比。具体执行模型分布在JSON及各原生报告中。

新闻冻结主口径：正文600–800个Han字符，两个标题及正文标签另计；数字/拉丁文/标点不计。非空白全字符只作敏感性记录，不更换主口径。23件成功新闻作品中8件篇幅不达标，另2件遗漏本地MVP事实；1件原始新闻生成失败。原题的“中文字符/字”与Han是否等价存在定义边界，新批次应事前明确；不能事后换口径免除旧失败。

|运行/作品|正文Han|正文非空白全字符|主口径合格|严重错误|
|---|---:|---:|---|---|
|2026-10-05T13-39-22-405Z-3f1c05df/CV-NEWS-M-raw-m1-r1|645|718|是|MISSING_REQUIRED_MVP_STAGE|
|2026-10-05T13-39-22-405Z-3f1c05df/CV-NEWS-M-direct-m1-r1|586|654|否|NEWS_BODY_LENGTH_OUT_OF_RANGE|
|2026-10-05T13-39-22-405Z-3f1c05df/CV-NEWS-M-plan-m1-r1|559|627|否|NEWS_BODY_LENGTH_OUT_OF_RANGE|
|2026-10-05T13-39-22-405Z-3f1c05df/CV-NEWS-M-raw-m2-r1|650|725|是|无|
|2026-10-05T13-39-22-405Z-3f1c05df/CV-NEWS-M-direct-m2-r1|619|689|是|无|
|2026-10-05T13-39-22-405Z-3f1c05df/CV-NEWS-M-plan-m2-r1|598|656|否|NEWS_BODY_HAN_LENGTH_OUT_OF_RANGE|
|2026-10-05T13-39-22-405Z-3f1c05df/OP-NEWS-L-raw-m1-r1|680|758|是|无|
|2026-10-05T13-39-22-405Z-3f1c05df/OP-NEWS-L-direct-m1-r1|705|785|是|无|
|2026-10-05T13-39-22-405Z-3f1c05df/OP-NEWS-L-plan-m1-r1|695|775|是|无|
|2026-10-05T13-39-22-405Z-3f1c05df/OP-NEWS-L-raw-m2-r1|744|829|是|无|
|2026-10-05T13-39-22-405Z-3f1c05df/OP-NEWS-L-direct-m2-r1|717|800|是|无|
|2026-10-05T13-39-22-405Z-3f1c05df/OP-NEWS-L-plan-m2-r1|749|840|是|无|
|2026-10-05T13-40-21-502Z-d01fb88f/CV-NEWS-M-raw-m1-r1|610|674|是|MISSING_REQUIRED_MVP_STAGE|
|2026-10-05T13-40-21-502Z-d01fb88f/CV-NEWS-M-direct-m1-r1|729|815|是|无|
|2026-10-05T13-40-21-502Z-d01fb88f/CV-NEWS-M-plan-m1-r1|502|563|否|NEWS_BODY_LENGTH_OUT_OF_RANGE_BOTH_METHODS|
|2026-10-05T13-40-21-502Z-d01fb88f/CV-NEWS-M-raw-m2-r1|546|605|否|NEWS_BODY_HAN_LENGTH_OUT_OF_RANGE|
|2026-10-05T13-40-21-502Z-d01fb88f/CV-NEWS-M-direct-m2-r1|597|661|否|NEWS_BODY_LENGTH_OUT_OF_RANGE|
|2026-10-05T13-40-21-502Z-d01fb88f/CV-NEWS-M-plan-m2-r1|553|612|否|NEWS_BODY_HAN_LENGTH_OUT_OF_RANGE|
|2026-10-05T13-40-21-502Z-d01fb88f/OP-NEWS-L-raw-m1-r1|692|768|是|无|
|2026-10-05T13-40-21-502Z-d01fb88f/OP-NEWS-L-direct-m1-r1|687|761|是|无|
|2026-10-05T13-40-21-502Z-d01fb88f/OP-NEWS-L-plan-m1-r1|680|755|是|无|
|2026-10-05T13-40-21-502Z-d01fb88f/OP-NEWS-L-raw-m2-r1|无作品|无作品|未执行成功|FAILED|
|2026-10-05T13-40-21-502Z-d01fb88f/OP-NEWS-L-direct-m2-r1|733|812|是|无|
|2026-10-05T13-40-21-502Z-d01fb88f/OP-NEWS-L-plan-m2-r1|559|626|否|NEWS_BODY_LENGTH_OUT_OF_RANGE|

一次生成、AI辅助评分和小样本不能证明增强稳定胜出；这份报告验证证据关联、呈现分布，不执行代码修复或行业专业放行。

- 原生配对报告：tmp/prompt-output-evaluation/2026-10-05T13-39-22-405Z-3f1c05df/reports/2026-10-05T14-55-44-810Z-853e2534.md
- 原生配对报告：tmp/prompt-output-evaluation/2026-10-05T13-40-21-502Z-d01fb88f/reports/2026-10-05T14-55-43-885Z-4fdb3033.md
