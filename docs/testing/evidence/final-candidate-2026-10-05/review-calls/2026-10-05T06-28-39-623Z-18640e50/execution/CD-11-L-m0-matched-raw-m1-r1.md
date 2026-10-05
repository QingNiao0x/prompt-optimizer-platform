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
        "| 日收益率 | 相邻两个交易日收盘价之间的相对变化 |"
      ],
      "notes": "准确覆盖全部业务边界，复权、收益率口径、成交量单位冲突均保持待确认或冲突状态，未越界。交付物齐全且可追溯。结构清晰但篇幅偏长、重复较多，状态标记和逆向核对部分冗余，usability略扣。"
    },
    {
      "blindId": "B010",
      "status": "REVIEWED",
      "scores": {
        "accuracy": 4,
        "completeness": 4,
        "ruleFidelity": 4,
        "usability": 4
      },
      "criticalErrors": [],
      "evidence": [
        "| 日收益率 | 相邻两个交易日之间价格变动的比例 |"
      ],
      "notes": "准确落实全部约束和已确认决定，简单收益率作为展示口径与submittedAnswers一致，复权保持待确认，成交量单位冲突并列。交付物完整，结构紧凑，无明显冗余，可执行性强。"
    },
    {
      "blindId": "B037",
      "status": "REVIEWED",
      "scores": {
        "accuracy": 4,
        "completeness": 4,
        "ruleFidelity": 4,
        "usability": 4
      },
      "criticalErrors": [],
      "evidence": [
        "| 日简单收益率 | 公式：r_t = (P_t − P_{t−1}) / P_{t−1}。"
      ],
      "notes": "准确覆盖全部要求，材料对应表清晰区分正式说明、旧版草稿和测试示例。公式透明可复核，图表模板明确轴名称、单位、缺口和样本范围。结构清楚，无明显冗余，可执行性强。"
    }
  ]
}