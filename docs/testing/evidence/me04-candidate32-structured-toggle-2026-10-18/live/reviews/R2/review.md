{
  "cases": {
    "MAIL": {
      "scores": {
        "A": {
          "accuracy": 5,
          "completeness": 5,
          "clarity": 5,
          "executability": 5
        },
        "B": {
          "accuracy": 5,
          "completeness": 5,
          "clarity": 5,
          "executability": 5
        }
      },
      "evidence": {
        "A": [
          "“您好，想确认原定周五的交付能否按时完成。” 对应要求：礼貌询问原定周五交付能否按时完成。",
          "“如有困难，请回复预计完成时间。” 对应要求：如有困难请回复预计时间，且未将困难写成已发生。",
          "“谢谢！” 礼貌结束，未编造姓名、进度或新日期。",
          "全文33个汉字，不超过120字，无额外包装。"
        ],
        "B": [
          "“您好，想确认原定周五的交付能否按时完成。” 对应要求：礼貌询问原定周五交付能否按时完成。",
          "“如有困难，请回复预计完成时间，谢谢。” 对应要求：如有困难请回复预计时间，且未将困难写成已发生。",
          "未编造姓名、进度或新日期。",
          "全文33个汉字，不超过120字，无额外包装。"
        ]
      },
      "severeFailures": {
        "A": [],
        "B": []
      },
      "verdict": "TIE",
      "promptAmbiguities": [],
      "uncertainties": []
    },
    "TRANSLATION": {
      "scores": {
        "A": {
          "accuracy": 5,
          "completeness": 5,
          "clarity": 5,
          "executability": 5
        },
        "B": {
          "accuracy": 5,
          "completeness": 5,
          "clarity": 5,
          "executability": 5
        }
      },
      "evidence": {
        "A": [
          "第一行“{name}, the meeting has been moved to Friday.” 保留{name}，表达会议改到周五。",
          "第二行“Please reply whether you will attend.” 请求回复是否参加。",
          "恰好两行，无标题、解释或示例。"
        ],
        "B": [
          "第一行“{name}, the meeting has been moved to Friday.” 保留{name}，表达会议改到周五。",
          "第二行“Please reply whether you will attend.” 请求回复是否参加。",
          "恰好两行，无标题、解释或示例。"
        ]
      },
      "severeFailures": {
        "A": [],
        "B": []
      },
      "verdict": "TIE",
      "promptAmbiguities": [],
      "uncertainties": []
    }
  }
}