# 第四版补全指导收敛对照原件

第一批NATURAL/13，整体收敛指导，41次实际调用，未采用。

`protocol.json`与`cases.json`在首调用前冻结；`source-manifest.json`记录当时产品源码及隔离运行副本指纹。两批之间有明确候选修订，闭批后的源码变化不追写旧指纹。指导、输入、首次失败及作品原文保留在`transports.jsonl`和`http-results.json`。源码与临时启动入口分别编译，临时路由不进入产品包。

使用PowerShell7运行启动及调用脚本；固定账号凭据来自私有DPAPI引用，不在本目录。批次已关闭，不能重新执行真实调用。只读复算：

```powershell
python -X utf8 docs/testing/evidence/natural-concise-completion-2026-10-22/analyze-results.py
```

采用判据、人工核对和全部未通过项分别见`protocol.json`、`manual-assessment.json`、`closure.json`；成本及确定性检查见`cost-and-artifact-checks.json`，API/历史对照见`history-checks.json`。这不是专业用户或完整发布验收。
