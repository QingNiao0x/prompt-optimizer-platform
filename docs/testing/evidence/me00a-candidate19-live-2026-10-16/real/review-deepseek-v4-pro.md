{
  "cases": [
    {
      "id": "requests-title",
      "scoresA": {
        "准确性": 4,
        "完整性": 5,
        "清晰度": 5,
        "可执行性": 5
      },
      "scoresB": {
        "准确性": 3,
        "完整性": 3,
        "清晰度": 4,
        "可执行性": 3
      },
      "verdict": "A胜",
      "severeFailures": [],
      "evidence": [
        {
          "work": "A",
          "quote": "def fetch_title(url: str, timeout: float = 10.0) -> dict"
        },
        {
          "work": "A",
          "quote": "except requests.exceptions.Timeout:\n        result[\"status\"] = \"timeout\""
        },
        {
          "work": "A",
          "quote": "class _TitleParser(HTMLParser):\n    def __init__(self) -> None:\n        super().__init__(convert_charrefs=True)"
        },
        {
          "work": "B",
          "quote": "response.raise_for_status()  # 检查 HTTP 错误"
        },
        {
          "work": "B",
          "quote": "title = soup.title.string.strip() if soup.title else '无标题'"
        },
        {
          "work": "B",
          "quote": "except requests.exceptions.RequestException as e:\n        return f'请求失败: {e}'"
        }
      ],
      "uncertainties": [
        "原始要求未指定是否允许额外依赖；A默认使用标准库，B依赖BeautifulSoup，但B也提供了正则备选。",
        "原始要求未指定错误处理方式；A返回结构化状态，B返回字符串或'无标题'，两者均可接受但A更明确。"
      ]
    },
    {
      "id": "mortality-2015-2025",
      "scoresA": {
        "准确性": 4,
        "完整性": 5,
        "清晰度": 5,
        "可执行性": 4
      },
      "scoresB": {
        "准确性": 4,
        "完整性": 4,
        "清晰度": 4,
        "可执行性": 3
      },
      "verdict": "A胜",
      "severeFailures": [],
      "evidence": [
        {
          "work": "A",
          "quote": "Joinpoint回归（推荐）：识别死亡率变化的转折点，计算APC（年度变化百分比）和AAPC"
        },
        {
          "work": "A",
          "quote": "Arriaga分解用于分解期望寿命差异，将两组（如城乡、男女、两个年份）之间的期望寿命差分解为：1. 直接效应 2. 间接效应 3. 交互效应"
        },
        {
          "work": "A",
          "quote": "YLL = \\sum_{i} d_i \\times (L - a_i)"
        },
        {
          "work": "B",
          "quote": "本稿不填入任何未经核实的数值、引用或结论；凡无法从现有资料推断者，均标注为待确认或无法从现有资料推断。"
        },
        {
          "work": "B",
          "quote": "参考寿命表的选择直接影响YLL计算结果。常用参考寿命表包括：全球疾病负担研究（GBD）标准寿命表、世界卫生组织（WHO）标准寿命表、某国家或地区期望寿命表、以及研究地区本地寿命表。"
        },
        {
          "work": "B",
          "quote": "Arriaga分解将期望寿命变化分解为各年龄组死亡率变化的贡献，并可进一步分解为不同死因的贡献。"
        }
      ],
      "uncertainties": [
        "原始要求未提供实际数据，两份作品均未编造具体数值，符合要求。",
        "A提供了更多具体公式和软件建议，B更强调未决条件；两者均未确认地区、数据来源等关键参数。"
      ]
    },
    {
      "id": "Q05-direct",
      "scoresA": {
        "准确性": 5,
        "完整性": 5,
        "清晰度": 5,
        "可执行性": 4
      },
      "scoresB": {
        "准确性": 4,
        "完整性": 4,
        "清晰度": 4,
        "可执行性": 4
      },
      "verdict": "A胜",
      "severeFailures": [],
      "evidence": [
        {
          "work": "A",
          "quote": "同信息公平对照须落实到信息流：先针对同一案例取得并封存实际确认信息，再让直接增强组与Plan组获得逐字相同的信息，且两组均可使用该信息。"
        },
        {
          "work": "A",
          "quote": "Plan组的问答交互终止条件尚未确认，拟议主方案为：当参与者确认已获得完成任务所需的全部关键信息，或连续两轮未产生新的有效信息时终止"
        },
        {
          "work": "A",
          "quote": "分歧处理拟议流程为：先计算两名评审在各维度上的评分差异，若差异不超过1分，取均值；若超过1分，进入协商讨论；协商后仍不一致的，由第三名资深评审仲裁。"
        },
        {
          "work": "B",
          "quote": "实验拟设四组。第一组为直接增强组，仅提供任务与基本指令。第二组为Plan组，在直接增强基础上加入Plan问答交互。第三组与第四组为公平对照：两组获得相同的确认信息，但一组以直接增强形式呈现，另一组以Plan形式呈现。"
        },
        {
          "work": "B",
          "quote": "评分拟采用有序量尺，例如1至5分，并附行为锚点。"
        },
        {
          "work": "B",
          "quote": "若未来数据近似连续且满足分布假设，可采用参数方法；若为有序分类或分布偏态，则采用非参数方法。"
        }
      ],
      "uncertainties": [
        "两份作品均未确认评分量尺具体等级，A提出7点并说明可调整，B提出1至5分；原始要求未指定。",
        "两份作品均未确认正文字数统计口径，A明确说明未包含标题摘要关键词，B未明确。"
      ]
    },
    {
      "id": "Q05-plan",
      "scoresA": {
        "准确性": 4,
        "完整性": 4,
        "清晰度": 4,
        "可执行性": 4
      },
      "scoresB": {
        "准确性": 5,
        "完整性": 5,
        "清晰度": 5,
        "可执行性": 4
      },
      "verdict": "B胜",
      "severeFailures": [],
      "evidence": [
        {
          "work": "A",
          "quote": "任务顺序采用区组随机化：将20个任务按类别分层，在每类内随机排列，再以随机顺序呈现给模型。"
        },
        {
          "work": "A",
          "quote": "若确认信息无法完全等同，则应在分析中将其作为协变量或分层因素处理。"
        },
        {
          "work": "B",
          "quote": "Plan不得改为模型自行生成执行计划再作答。"
        },
        {
          "work": "B",
          "quote": "条件C与条件D为同信息公平对照：两组接收并可使用同一原始任务和同一份实际确认信息，保留各组指定的增强流程，不改写任一组的回答、不另换任务，也不只增加某组修订轮次。"
        },
        {
          "work": "B",
          "quote": "重复指在同一条件、同一任务下独立生成3份提示词，不得复用中间产物。"
        },
        {
          "work": "B",
          "quote": "正文800至1200个汉字是否包含标题、摘要与关键词尚未确认，未确认前保持未决。"
        }
      ],
      "uncertainties": [
        "两份作品均未确认Plan终止条件、确认信息形式、任务分配顺序、评分量尺等参数，但B更明确地将这些列为未决参数。",
        "A未明确说明Plan不得改为模型自行生成执行计划，B明确禁止，但原始要求未明确此点。"
      ]
    }
  ]
}