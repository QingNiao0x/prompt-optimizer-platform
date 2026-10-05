{
  "entries": [
    {
      "blindId": "B018",
      "status": "REVIEWED",
      "scores": {
        "accuracy": 4,
        "completeness": 4,
        "ruleFidelity": 4,
        "usability": 3
      },
      "criticalErrors": [],
      "evidence": [
        "| **爽约** | 在观察窗口到期后，预约事件既未到诊（非 ARRIVED）也未在免责取消边界内取消（非 CANCELLED 或取消时间超出免责边界）的最终状态 |"
      ],
      "notes": "口径表、流程图、采集字段、报告目录、人工复核及隐私边界清单均覆盖交付要求。观察窗口、取消免责边界、主统计单位均明确列为待确认且未默认补全；UNKNOWN未计为爽约；24小时与12小时差异并列呈现；测试示例、旧版草稿与正式说明分开标注。结构清晰，但部分内容重复较多，如待确认项在正文、附表中多次出现，影响可读性，usability略扣。"
    },
    {
      "blindId": "B024",
      "status": "REVIEWED",
      "scores": {
        "accuracy": 4,
        "completeness": 4,
        "ruleFidelity": 4,
        "usability": 3
      },
      "criticalErrors": [],
      "evidence": [
        "| 爽约定义 | 仅当预约事件已过观察窗口且到诊状态经核验仍非 ARRIVED、非 CANCELLED 时，方可讨论是否构成爽约 |"
      ],
      "notes": "口径梳理、流程图说明、拟议采集字段、报告目录、人工复核及隐私边界清单齐全。观察窗口、取消免责边界、主统计单位均列为待确认且未擅自补全；UNKNOWN未直接计为爽约；取消规则差异单独呈现；测试示例与正式说明分开。结构清楚，但部分内容重复，如待确认项在口径表、流程图、报告目录、待确认清单中多次出现，usability略扣。"
    }
  ]
}