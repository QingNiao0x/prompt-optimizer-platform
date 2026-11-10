# 单次直接编辑对照

真实调用通过正式OpenAiTextPolishProvider执行，逐题只变strategy。证据与已闭批账本见docs/testing/文章祛AI味直接编辑策略实现与验收-2026-11-11.md。

run.ps1先加载既有受保护环境，inspect读取模型与账号归属，pilot执行预先冻结任务。每次预留两次以包含一次协议修复，缺少计量时保守结算并停止。闭批禁止续跑。新实验必须使用新证据目录、冻结源码和样本及适用授权，不重用旧目录运行inspect。

blind.py接收证据目录参数，随机排列作品并隐藏策略标签，生成blind-pairs与blind-key。已有盲包时拒绝重排。评阅者应先完成blind-review，再读blind-key。输入输出采用UTF-8。

本次两阶段共享100次授权，总结算以主证据summary.json为准，子账本上限不代表新增授权。
