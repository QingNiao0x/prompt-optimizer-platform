{
  "entries": [
    {
      "blindId": "B036",
      "status": "REVIEWED",
      "scores": {
        "accuracy": 4,
        "completeness": 4,
        "ruleFidelity": 4,
        "usability": 3
      },
      "criticalErrors": [],
      "evidence": [
        "| 历史保留期限 | 旧交接说明：历史永久保留 | 新需求草稿：历史保留 180 天，但尚未批准 | 未决，不擅自采用其中一项 |"
      ],
      "notes": "准确区分现状、待办、未知与冲突，未将待办写成可用功能，未编造日志平台或高可用保证。历史保留期限差异正确列为未决，未擅自采用。测试示例与正式说明分开标注。结构完整，但部分内容重复，如现状清单与模块职责表多处重复，影响可读性。"
    },
    {
      "blindId": "B051",
      "status": "REVIEWED",
      "scores": {
        "accuracy": 4,
        "completeness": 4,
        "ruleFidelity": 4,
        "usability": 4
      },
      "criticalErrors": [],
      "evidence": [
        "| 历史保留期限 | 旧交接说明：历史**永久保留**（`legacy-note.md`） | 新需求草稿：历史保留 **180 天**（`legacy-note.md` 所载草稿） | 新需求草稿**尚未批准**；两份材料口径与审批状态不同，**不擅自采用其中一项**，按本题说明核对 |"
      ],
      "notes": "准确区分现状、待办、未知与冲突，未将待办写成可用功能，未编造日志平台或高可用保证。历史保留期限差异正确列为未决，未擅自采用。测试示例与正式说明分开标注。结构清晰，检查表可执行，未证实能力给出核查入口，未默认补全本地调试章节。"
    }
  ]
}