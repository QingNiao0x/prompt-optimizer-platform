# 51实际作品准备（未执行）

内容建议新增14个EXEC、0个GEN：Q01只采51两次，复用content-works46原始/31各两份完整作品；U02/U03每题原始/31/51各两次，31提示词复用completion47最终交付。U02/U03旧31虽有较大材料转述，也有具体处理要求；本次只读门核未发现须排除的严重目标、事实或硬条件错误。

Q01沿旧8192和逐字相同公开source pack；U02/U03各臂统一4500，提前固定，不因候选更长事后补采。所有新请求共用原通用执行system及Flash/.2/disabled/stream=false，只替换提示词，完整原材料对各臂相同，无答案卡。

该目录只有准备数据，不是freeze或live授权。normal与root产品门通过后，仍须root批准并由infra冻结。失败及截断保留，不补采、不称持平。旧50风险与全部失败原判不变。

详见bundle.json、reuse-provenance.json、preflight-review-plan.json。复用Q基线不能与新51随机交错，未暴露的模型权重版本也无法由请求证明；两次重复不作统计稳定主张，作品好不能替代提示词保真门，不推断真人修改分钟数。

代码另计划4个EXEC：C01/C02各51两次，复用code-works46原始臂各两份作品。原始需求、合同、白名单、完整fixture和执行profile均已核对；七份执行材料、8192预算及原system逐字沿用旧请求，79文件索引与既有业务/边界/误改检查器不变。C01可按需修改已有测试；C02不得新增文件或修改任何测试，检测器不以C02没有新增测试为失败。

代码目前只有code-planned-bundle.json的planned refs，jobs为空，没有伪造51 prompt。normal阶段闭批后可运行 `node docs/testing/evidence/natural-useful-delivery-2026-10-28/registry51-works-preparation/materialize-code.mjs registry51-normal`，只从锁定的C01/C02最终交付构造新code-bundle.json，不发模型请求。root仍须核对增强正文与原合同同范围，再决定freeze/live。旧45三文件失败与31未过产品门的代码臂保持原判，不补采。

合计最多18个新EXEC（内容14、代码4），0个新增GEN；当前全部仅准备，未执行。代码复用证明见code-reuse-provenance.json；后续直接调用原checkers/run-code-results.mjs，不另建评测框架。
