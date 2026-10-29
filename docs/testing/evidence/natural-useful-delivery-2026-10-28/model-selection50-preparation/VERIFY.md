# Native Pro模型选择核验器（仅离线准备）

未启动服务、未登录、未发送GET或POST、未调用模型。合成自测不是真实路由证据；正式profile/freezer/server由infra维护，本工具不修改它们。

```text
node verify-model-selection.mjs --help
node verify-model-selection.mjs <future-frozen-stage>
```

默认只读取本机冻结协议和作业进行预检。仅在root批准、infra已启动并LOGIN_READY且独占active-job时，才可追加`--execute-get`。`--label BEFORE`是默认；`--label AFTER`必须已有同阶段成功BEFORE结果，并验证默认模型ID未变化。AFTER应在关闭阶段前执行，不能重开闭批入口。

有限契约：`OFFICIAL_MODEL_SELECTION_ABLATION_V1`、public ID `deepseek:deepseek-v4-pro`、上游`deepseek-v4-pro`、routeKey `deepseek`、官方endpoint；50候选三份GEN作业，最多12次实际调用，其他阶段额度为零，参数保持8192/.2/thinking disabled/stream false。没有明确profile立即拒绝，不能靠目录里存在某个模型自行改路由。

未来真实执行只读三个loopback GET：`/api/v1/auth/me`、`/api/v1/models`、`/api/v2/evaluation/model-selection`。第三个GET使用第一冻结作业的header及相同active-job。没有任何POST、自动登录、外部fetch、模型调用、目录修改或trace写入。固定账号私有cookie只在内存使用，不打印、不写入证据；不保存完整身份或catalog响应。

只保存明确白名单的路由和核验布尔值到runtime `results/MODEL_SELECTION_CHECK.json`；AFTER为`MODEL_SELECTION_CHECK_AFTER.json`，均wx拒绝覆盖，后续由正常闭批归档。Pro的defaultModel无论true或false均如实保留，只要求与正常公开目录及before/after默认ID一致。核验通过仅证明实际catalog选择，不代表正文质量收益。
