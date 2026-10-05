```json
{
  "entries": [
    {
      "blindId": "B006",
      "status": "REVIEWED",
      "scores": {
        "accuracy": 4,
        "completeness": 4,
        "ruleFidelity": 4,
        "usability": 3
      },
      "criticalErrors": [],
      "evidence": [
        "日收益率使用简单收益率还是对数收益率？当前尚未决定，不得默认补全"
      ],
      "notes": "准确覆盖所有关键业务规则，复权、收益率口径、单位冲突均保持待确认或冲突状态，未越界给出投资建议。结构完整但篇幅冗长，多处重复同一条件，usability扣1分。"
    },
    {
      "blindId": "B010",
      "status": "REVIEWED",
      "scores": {
        "accuracy": 3,
        "completeness": 4,
        "ruleFidelity": 3,
        "usability": 4
      },
      "criticalErrors": [],
      "evidence": [
        "日收益率采用简单收益率作为展示口径，公式与单位明确"
      ],
      "notes": "将submittedAnswers中的简单收益率选择直接作为已确定口径写入正文，但task.json原始任务明确该口径尚未决定，属于将候选答案固化为已定参数，ruleFidelity扣分。其余交付物完整，结构清晰。"
    },
    {
      "blindId": "B018",
      "status": "REVIEWED",
      "scores": {
        "accuracy": 4,
        "completeness": 4,
        "ruleFidelity": 4,
        "usability": 4
      },
      "criticalErrors": [],
      "evidence": [
        "简单收益率或对数收益率尚未决定，不得默认"
      ],
      "notes": "submittedAnswers为空，作品正确保持收益率口径为待确认，未将候选答案固化为已定参数。所有交付物齐全，材料区分清晰，结构简洁，未发现越界或规则反转。"
    },
    {
      "blindId": "B028",
      "status": "REVIEWED",
      "scores": {
        "accuracy": 4,
        "completeness": 4,
        "ruleFidelity": 4,
        "usability": 3
      },
      "criticalErrors": [],
      "evidence": [
        "简单收益率与对数收益率的选择尚未决定，不得默认补全"
      ],
      "notes": "submittedAnswers为空，作品正确保持收益率口径为待确认。所有关键规则均落地，但篇幅过长，同一条件在多个章节重复出现，usability扣1分。"
    },
    {
      "blindId": "B037",
      "status": "REVIEWED",
      "scores": {
        "accuracy": 3,
        "completeness": 4,
        "ruleFidelity": 3,
        "usability": 4
      },
      "criticalErrors": [],
      "evidence": [
        "日简单收益率 | 公式：r_t = (P_t − P_{t−1}) / P_{t−1}"
      ],
      "notes": "将submittedAnswers中的简单收益率选择直接作为已确定口径写入风险指标定义表，但task.json原始任务明确该口径尚未决定，属于将候选答案固化为已定参数，ruleFidelity扣分。其余交付物完整，结构清晰。"
    }
  ]
}
```