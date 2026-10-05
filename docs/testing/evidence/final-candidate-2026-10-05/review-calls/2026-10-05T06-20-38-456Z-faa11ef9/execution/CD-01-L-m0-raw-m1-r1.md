{
  "entries": [
    {
      "blindId": "B007",
      "status": "REVIEWED",
      "scores": {
        "accuracy": 4,
        "completeness": 4,
        "ruleFidelity": 4,
        "usability": 4
      },
      "criticalErrors": [],
      "evidence": [
        "候选请求未结束时再次修改姓名，迟到的旧请求必须失效"
      ],
      "notes": "方案完整覆盖两个模块、白名单、空值规则、排序、时序保护、详情编号校验、取消/异常分支及地区缺失待确认项。伪代码与测试表均能对应业务要求，结构清晰，未发现事实错误或规则反转。"
    },
    {
      "blindId": "B029",
      "status": "REVIEWED",
      "scores": {
        "accuracy": 4,
        "completeness": 4,
        "ruleFidelity": 4,
        "usability": 4
      },
      "criticalErrors": [],
      "evidence": [
        "地区缺失不是地区匹配成功"
      ],
      "notes": "方案完整覆盖两个模块的调用链路、共用处理步骤、字段写入与异常分支伪代码、测试表及未决前提。地区缺失处理保持为未决，未默认补全，符合要求。结构清晰，可执行性强。"
    },
    {
      "blindId": "B038",
      "status": "REVIEWED",
      "scores": {
        "accuracy": 4,
        "completeness": 4,
        "ruleFidelity": 4,
        "usability": 4
      },
      "criticalErrors": [],
      "evidence": [
        "职业类别必须使用空值比较，不能写成 form.occupation || detail.occupation"
      ],
      "notes": "方案完整覆盖所有业务细节，包括两个模块差异、时序保护、空值判断、白名单、排序、详情编号校验、取消/异常分支及地区缺失未决项。伪代码和测试表详细且可执行，未发现规则反转或事实错误。"
    },
    {
      "blindId": "B040",
      "status": "REVIEWED",
      "scores": {
        "accuracy": 4,
        "completeness": 4,
        "ruleFidelity": 4,
        "usability": 4
      },
      "criticalErrors": [],
      "evidence": [
        "地区信息缺失或无法核验的候选，单独标记为“无法核验”"
      ],
      "notes": "方案完整覆盖两个模块、白名单、空值规则、排序、时序保护、详情编号校验、取消/异常分支及地区缺失待确认项。伪代码和测试表均能对应业务要求，结构清晰，未发现事实错误或规则反转。"
    },
    {
      "blindId": "B046",
      "status": "REVIEWED",
      "scores": {
        "accuracy": 4,
        "completeness": 4,
        "ruleFidelity": 4,
        "usability": 4
      },
      "criticalErrors": [],
      "evidence": [
        "地区缺失不是地区匹配成功；查询接口返回顺序不是授权证据"
      ],
      "notes": "方案完整覆盖两个模块、白名单、空值规则、排序、时序保护、详情编号校验、取消/异常分支及地区缺失待确认项。伪代码和测试表均能对应业务要求，结构清晰，未发现事实错误或规则反转。"
    }
  ]
}