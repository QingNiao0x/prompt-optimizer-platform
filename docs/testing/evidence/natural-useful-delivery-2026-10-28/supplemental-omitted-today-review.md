8份真实代码作品的省略today补充执行诊断，0模型/API调用。所有生成业务代码仅在既有受限VM的隔离副本中运行；固定Asia/Shanghai本地日历2026-10-28，模块加载前冻结Date，不依赖宿主当前时间。共享checker、闭批原件及旧报告均未改。

| 作品 | 原门禁 | 显式today控制 | 省略today探针 | 新探针失败 |
| --- | --- | --- | --- | --- |
| C01_ORIGINAL_EXEC_R1 | 原PASS保留 | 通过 | 4/6 | omitted-create-future、omitted-import-future |
| C01_ORIGINAL_EXEC_R2 | 原PASS保留 | 通过 | 4/6 | omitted-create-future、omitted-import-future |
| C02_ORIGINAL_EXEC_R1 | 原PASS保留 | 通过 | 4/6 | omitted-create-future、omitted-import-future |
| C02_ORIGINAL_EXEC_R2 | 原PASS保留 | 通过 | 4/6 | omitted-create-future、omitted-import-future |
| C01_V51_EXEC_R1 | 原PASS保留 | 通过 | 4/6 | omitted-create-future、omitted-import-future |
| C01_V51_EXEC_R2 | 原PASS保留 | 通过 | 4/6 | omitted-create-future、omitted-import-future |
| C02_V51_EXEC_R1 | 原PASS保留 | 通过 | 4/6 | omitted-create-future、omitted-import-future |
| C02_V51_EXEC_R2 | 原PASS保留 | 通过 | 4/6 | omitted-create-future、omitted-import-future |

旧合同路径与本次风险必须分开：旧检查总会显式传入today，且已验证该参数独立于系统时钟；这些原PASS保持原义。本次分别实际调用新增/导入时省略第二参数，检查未来拒绝、当日允许、旧日期允许，没有向作品追加实现、文件或测试义务。

原始需求要求日期不能超过今天；所有作品都看到了README中的“执行者可传入同格式today以便确定性检查”。这没有明确说明today参数究竟必填还是省略时应默认本地当日，因此保留合同解释歧义。若接口允许省略today，则本报告中失败是直接的使用风险；若调用契约要求始终注入today，则风险属于接入前提未显式说明及原验收未覆盖该路径，不能据新探针追溯改判旧门禁。

逐项实际返回/异常、原件哈希、固定时钟与隔离副本的三处可信补丁见同名JSON；断言失败消息中的observed是VM内真实返回，不是文本推测。没有读取匿名映射，未更改独立盲审。
