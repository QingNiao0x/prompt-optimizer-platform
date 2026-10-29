# 合成调查记录项目

本项目完全合成，不含真实业务代码或人员数据。重点记录模块位于 `src/views/records`，旧模块位于 `src/views/records-old`，二者互不替代。

新增通过 `createSurvey` 提交；导入通过 `importSurveys` 按行处理，允许部分成功，失败行返回从 1 开始的行号及稳定错误代码。两个入口共用 `validateSurveyDate`。现有日期校验只检查日期格式及日历有效性。

`surveyDate` 为不带时刻的 `YYYY-MM-DD` 日历日期，执行者可传入同格式 `today` 以便确定性检查。已有测试记录现有契约；本轮新要求的独立验收在项目副本之外运行。
