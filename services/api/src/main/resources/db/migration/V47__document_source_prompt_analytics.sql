-- 素材工作台沿用图像事件代码与历史，新增阶段细分只用于独立统计，不能重复累计关键操作。
-- event_type 无数据库 CHECK：这里记录当前含义，不将应用枚举误写为数据库强制值域。
COMMENT ON COLUMN audit_event.event_type IS
'审计事件代码，当前无数据库 CHECK 枚举。素材转提示词兼容 IMAGE_PROMPT_SUBMITTED（提交尝试）、IMAGE_PROMPT_PLAN_CREATED（计划就绪）、IMAGE_PROMPT_GENERATED（可恢复结果），新增 IMAGE_PROMPT_DIRECT_SUBMITTED（直接提交细分）、IMAGE_PROMPT_PLAN_COMPLETED（按计划去重的最终生成完成）、IMAGE_PROMPT_REOPTIMIZED（再次优化成功细分）；细分事件不重复累计关键操作。RESULT_EXPORTED 为登录用户成功复制操作，重放由稳定事件编号去重。其他事件由对应业务模块定义，不存资料内容或文件路径。';

-- 原视觉记录与通用资料记录共用存储，仅扩展应用JSON协议及注释，不改旧记录或数据库约束。
COMMENT ON TABLE image_prompt_record IS
'当前用户、租户与工作区内私有的素材提示词记录；包含原视觉还原/创作及代码、文档与混合资料任务；保存文字结果、已选且安全处理的来源证据与实际题答，不保存原件或图像字节；业务删除采用逻辑删除，所有读写核对用户、租户和工作区';
COMMENT ON COLUMN image_prompt_record.mode IS
'必填业务模式，无默认值，CHECK允许REFERENCE_RECONSTRUCTION=关闭Plan：视觉任务还原所选参考内容、通用资料任务按明确目标直接增强；CUSTOM_CREATION=开启Plan：视觉任务辅助创作、通用资料任务确认重要未知后生成。通用任务由payload.input.sourceTask区分，旧记录缺该对象时沿用视觉定义；无预留代码';
COMMENT ON COLUMN image_prompt_record.payload IS
'必填JSON对象，无数据库默认值；当前顶层键为operationId/recordId/mode/optimizedPrompt/analysis/warnings/input/sources/questions/answers/modelId/modelName/mock/editRevision/historySaved/persistentHistory/createdAt/materialSources/evidenceWarnings/sourceSources。input含mode/modelId/images/creationIntent/exactText/context/language/targetTool/outputType/materials/relations/sourceTask；sourceTask可为空，缺省或null表示旧视觉任务，否则含taskType(CODE=代码页面、DOCUMENT=文档内容、OTHER=其他任务)、sources引用数组(assetId/unitIds/track/role)及短时consentId；track为REFERENCE或REPLACEMENT，文字role为BASELINE/REFERENCE/FACTS/CONSTRAINTS/REPLACEMENT。outputType允许IMAGE/VIDEO/PPT，通用及视觉还原沿用兼容值，实际交付以用户目标和sourceTask为准。sourceSources为所选且已安全处理的文字证据数组，元素含assetId/name/kind(TEXT/CODE/DOCUMENT/COLLECTION)/track/role/units/warnings，units含id/path/label/text；缺省或null由应用读取为空数组，无数据库默认。sources为规范化图片元数据数组；materialSources为assetId/name/kind(IMAGE/VIDEO/PPTX)/track/role/units/warnings数组，units含id/page(1起)/timestampMs(源视频毫秒)/text/masks，masks为x/y/width/height归一遮挡矩形，遮挡单位不携带原结构文字；analysis为来源观察数组，questions/answers为实际题答数组，relations保存初始保留/替换/移除/融合要求。evidenceWarnings为独立来源风险字符串数组，兼容旧记录时按可确定的素材静态风险及UNCERTAIN观察恢复；显式空数组保持为空。数据库仅校验顶层对象，不强制内部键、枚举或结构；不保存原件、图像字节、Base64或公开素材URL；文字证据仍可能敏感，当前可复制正文和修订以独立列为准';
