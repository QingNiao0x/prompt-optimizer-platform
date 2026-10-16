```json
{
  "cases": [
    {
      "id": "requests-title",
      "scoresA": {
        "accuracy": 5,
        "completeness": 5,
        "clarity": 5,
        "executability": 5
      },
      "scoresB": {
        "accuracy": 4,
        "completeness": 4,
        "clarity": 4,
        "executability": 4
      },
      "verdict": "A胜",
      "severeFailures": [],
      "evidence": [
        {
          "work": "A",
          "quote": "使用 requests 库爬取网页标题。"
        },
        {
          "work": "A",
          "quote": "核心函数 fetch_page_title 负责单次抓取与解析，命令行入口 main 负责参数解析与结果输出。"
        },
        {
          "work": "A",
          "quote": "使用 requests.get 发起请求，设置 timeout=(connect, read) 与 User-Agent。"
        },
        {
          "work": "A",
          "quote": "检查状态码，非 2xx 抛出明确异常。"
        },
        {
          "work": "A",
          "quote": "用 html.parser 提取 <title> 文本，去空白。"
        },
        {
          "work": "A",
          "quote": "def fetch_page_title(url: str, *, timeout: tuple[float, float] = DEFAULT_TIMEOUT, session: requests.Session | None = None, headers: dict[str, str] | None = None) -> str:"
        },
        {
          "work": "A",
          "quote": "def extract_title(html: str) -> str | None:"
        },
        {
          "work": "A",
          "quote": "def main(argv: list[str] | None = None) -> int:"
        },
        {
          "work": "A",
          "quote": "使用 unittest.mock.patch(\"requests.get\") 或注入 session 假对象，避免真实网络。"
        },
        {
          "work": "B",
          "quote": "import requests\nfrom bs4 import BeautifulSoup"
        },
        {
          "work": "B",
          "quote": "response = requests.get(url, headers=headers, timeout=10)\nresponse.raise_for_status()"
        },
        {
          "work": "B",
          "quote": "soup = BeautifulSoup(response.text, 'html.parser')\ntitle = soup.title.string.strip() if soup.title else '无标题'"
        },
        {
          "work": "B",
          "quote": "except requests.exceptions.RequestException as e:\n    return f'请求失败: {e}'"
        },
        {
          "work": "B",
          "quote": "match = re.search(r'<title[^>]*>(.*?)</title>', resp.text, re.S | re.I)"
        }
      ],
      "uncertainties": [
        "原始要求未指定是否允许第三方依赖（如BeautifulSoup），A作品主方案使用标准库html.parser，B作品使用BeautifulSoup并额外提供正则版本，两者均满足使用requests的基本要求。",
        "原始要求未指定Python版本，A作品使用Python 3.9+语法（list[str]、str | None），B作品未明确版本要求。"
      ]
    },
    {
      "id": "mortality-2015-2025",
      "scoresA": {
        "accuracy": 4,
        "completeness": 5,
        "clarity": 4,
        "executability": 4
      },
      "scoresB": {
        "accuracy": 5,
        "completeness": 4,
        "clarity": 5,
        "executability": 4
      },
      "verdict": "A胜",
      "severeFailures": [],
      "evidence": [
        {
          "work": "A",
          "quote": "死亡数据：疾控中心死因监测系统、公安/民政死亡登记、医院病案首页（建议多源比对）"
        },
        {
          "work": "A",
          "quote": "ICD编码：心脑血管疾病建议采用 ICD-10 I00–I99"
        },
        {
          "work": "A",
          "quote": "Joinpoint回归：识别趋势转折点，计算APC（年度变化百分比）和AAPC"
        },
        {
          "work": "A",
          "quote": "时间序列分解：STL（Seasonal-Trend decomposition using Loess）"
        },
        {
          "work": "A",
          "quote": "YLL = \\sum_{i} d_i \\times (L - a_i)"
        },
        {
          "work": "A",
          "quote": "Arriaga分解用于量化各年龄组/死因对期望寿命差异的贡献"
        },
        {
          "work": "A",
          "quote": "R包：DemoDecomp（HMD官方推荐）、decompR"
        },
        {
          "work": "A",
          "quote": "COVID-19影响：2020–2022年数据需单独讨论，可做敏感性分析"
        },
        {
          "work": "B",
          "quote": "本报告所依据的研究地区、数据来源、年份覆盖范围、人群分组、亚类定义和参考寿命表尚未最终确认"
        },
        {
          "work": "B",
          "quote": "建议以年龄标化死亡率为主要长期趋势指标"
        },
        {
          "work": "B",
          "quote": "YLL 的基本计算口径为：对每一例死亡，计算其死亡年龄与参考寿命表中该年龄对应的预期剩余寿命之差"
        },
        {
          "work": "B",
          "quote": "Arriaga 分解用于将两组之间死亡率差异或预期寿命差异分解为年龄别死亡率和死因构成的贡献"
        },
        {
          "work": "B",
          "quote": "资料缺口将在文末集中说明一次"
        }
      ],
      "uncertainties": [
        "原始要求未提供具体地区、数据来源、人口数据、ICD编码版本等关键信息，A作品在未确认信息下给出了具体ICD编码范围（I00–I99）和推荐工具，B作品更谨慎地标注待确认。",
        "A作品在未确认信息下给出了具体公式和工具推荐，可能被视为过度具体化，但未编造实际数据或结论。",
        "B作品在完整性上未提供具体的ICD编码范围、具体工具推荐和论文写作框架，但更严格地遵守了不编造未确认信息的原则。"
      ]
    },
    {
      "id": "Q05-direct",
      "scoresA": {
        "accuracy": 5,
        "completeness": 5,
        "clarity": 5,
        "executability": 5
      },
      "scoresB": {
        "accuracy": 4,
        "completeness": 4,
        "clarity": 4,
        "executability": 4
      },
      "verdict": "A胜",
      "severeFailures": [],
      "evidence": [
        {
          "work": "A",
          "quote": "采用4类任务（如信息检索、方案设计、文本改写、数据分析），每类5个，共20个任务；每模式每任务独立重复3次。"
        },
        {
          "work": "A",
          "quote": "模式拟设四组：直接增强组、Plan组、同信息对照A组与同信息对照B组。"
        },
        {
          "work": "A",
          "quote": "直接增强组不主动发起问答，但可正常使用已提供的需求与确认信息；Plan组由增强器就关键缺口提出问答，用户回答后形成Plan再产出提示词，不得改为模型自行生成执行计划后作答。"
        },
        {
          "work": "A",
          "quote": "两组同信息对照接收同一原始任务与同一份实际确认信息，且均可使用该信息；A、B两组增强流程拟分别采用\"确认信息+结构化补全\"与\"确认信息+约束显化\""
        },
        {
          "work": "A",
          "quote": "确认信息拟由独立协调者在正式运行前通过统一问题清单取得，逐字记录并封存于只读文件"
        },
        {
          "work": "A",
          "quote": "任务与重复顺序拟分层随机：每类任务内先随机任务序号，再随机重复次序；四组呈现顺序按区组随机化"
        },
        {
          "work": "A",
          "quote": "评审材料去除组别、轮次与模型标识，仅保留任务编号、重复编号和提示词文本；揭盲在完成全部评分与分歧处理后进行。"
        },
        {
          "work": "A",
          "quote": "拟采用\"全评\"主方案，即每名评审对同一任务下四组三次重复的全部提示词评分"
        },
        {
          "work": "A",
          "quote": "准确性指提示词对任务目标、事实约束与输出要求的表述是否正确；完整性指是否覆盖必要信息、步骤与边界；清晰度指结构、指代与歧义控制；可执行性指下游可据此稳定执行的程度。"
        },
        {
          "work": "A",
          "quote": "拟采用5点量表（1=很差，2=较差，3=中等，4=良好，5=优秀），并配锚点"
        },
        {
          "work": "A",
          "quote": "分歧处理拟先由两名评审独立评分，差值≤1分取均值；差值≥2分进入协商，协商不成由第三名仲裁者盲评并取仲裁分。"
        },
        {
          "work": "A",
          "quote": "若数据近似正态且满足配对结构，采用重复测量方差分析或线性混合模型；若不满足正态性，采用Friedman检验配合事后Wilcoxon符号秩检验；若关注二分类化\"高质量\"阈值，采用广义线性混合模型或McNemar检验。"
        },
        {
          "work": "A",
          "quote": "正文统计口径拟为800至1200个汉字，不含标题、摘要、关键词、附表与代码"
        },
        {
          "work": "B",
          "quote": "四类任务拟覆盖不同认知需求，例如信息提取、方案生成、约束满足与多步推理等，但具体任务内容将在预注册或研究方案中另行明确。"
        },
        {
          "work": "B",
          "quote": "Plan组的问答轮次与终止方式、同信息对照的具体增强流程、确认信息的取得与封存方式等，均作为待定的设计参数"
        },
        {
          "work": "B",
          "quote": "评分形式拟采用有序评分量表，并辅以必要的文字说明；具体分值范围与锚点将在研究方案中预先设定。"
        },
        {
          "work": "B",
          "quote": "评审是否对同一任务的所有模式结果都评分，将依据实际可行性确定"
        },
        {
          "work": "B",
          "quote": "若采用完全交叉设计，则同一评审对同一任务的全部模式结果评分；若采用分片设计，则通过平衡分配确保每项结果均由两名评审独立评分。"
        }
      ],
      "uncertainties": [
        "原始要求明确要求说明随机化、盲法、准确性、完整性、清晰度与可执行性评价以及分歧处理，A作品对每项均给出了具体方案，B作品在多个关键设计参数上标注为待定。",
        "原始要求要求正文800至1200个汉字，A作品明确说明了统计口径，B作品未明确说明字数统计口径。",
        "原始要求要求不提供代码，A作品未提供代码，B作品也未提供代码。",
        "原始要求要求不得使用假设数据，A作品未使用假设数据，B作品也未使用假设数据。"
      ]
    },
    {
      "id": "Q05-plan",
      "scoresA": {
        "accuracy": 5,
        "completeness": 5,
        "clarity": 5,
        "executability": 5
      },
      "scoresB": {
        "accuracy": 4,
        "completeness": 4,
        "clarity": 4,
        "executability": 4
      },
      "verdict": "A胜",
      "severeFailures": [],
      "evidence": [
        {
          "work": "A",
          "quote": "设计以4类任务、每类5个、共20个任务为材料基础；每模式每任务独立重复3次"
        },
        {
          "work": "A",
          "quote": "比较条件包括直接增强与Plan两种模式，并另设两组获得相同确认信息的公平对照"
        },
        {
          "work": "A",
          "quote": "直接增强组不主动发起问答，但可使用已提供的需求与确认信息。Plan组按拟议流程进行问答：由增强器提出关键问题，用户回答，再将有效回答融入提示词优化。"
        },
        {
          "work": "A",
          "quote": "Plan组问答轮次与终止方式拟议为：最多3轮；当确认信息足以覆盖任务关键约束，或用户明确表示无补充时终止"
        },
        {
          "work": "A",
          "quote": "同信息对照设两组，均获得逐字相同的确认信息。信息流拟议为：先针对同一案例取得实际确认信息，封存于只读文档并计算校验值；两组在同一时间获得同一份逐字文本。"
        },
        {
          "work": "A",
          "quote": "任务与重复顺序拟采用区组随机化：以任务为区组，在区组内随机安排模式与重复顺序"
        },
        {
          "work": "A",
          "quote": "评审材料去除模式标签、组别、轮次与模型身份，仅保留任务、提示词及必要上下文；同一任务的所有模式结果拟由同一评审在盲态下评分"
        },
        {
          "work": "A",
          "quote": "准确性指提示词是否忠实反映任务需求与确认信息；完整性指是否覆盖关键约束与必要步骤；清晰度指表述是否明确、无歧义；可执行性指据此提示词能否直接开展任务。"
        },
        {
          "work": "A",
          "quote": "评分形式拟议采用7点李克特量表，并允许评审填写简短理由"
        },
        {
          "work": "A",
          "quote": "两名评审评分不一致时，先检查是否因材料理解或标准误用；若差异在预设范围内，取平均分；若超出范围，由第三名评审仲裁或双方复核后重新评分。"
        },
        {
          "work": "A",
          "quote": "候选方法包括：配对t检验或Wilcoxon符号秩检验，适用于同任务同重复的配对比较；混合效应模型，适用于任务与重复嵌套结构"
        },
        {
          "work": "A",
          "quote": "正文800至1200个汉字是否包含标题、摘要或关键词，拟议为仅计正文，不含标题、摘要、关键词、附表与代码"
        },
        {
          "work": "B",
          "quote": "Plan组的问答轮次与终止方式、同信息对照的具体增强流程、确认信息的取得与封存方式等，均作为待定的设计参数"
        },
        {
          "work": "B",
          "quote": "评分形式拟采用有序评分量表，并辅以必要的文字说明；具体分值范围与锚点将在研究方案中预先设定。"
        },
        {
          "work": "B",
          "quote": "评审是否对同一任务的所有模式结果都评分，将依据实际可行性确定"
        },
        {
          "work": "B",
          "quote": "若采用完全交叉设计，则同一评审对同一任务的全部模式结果评分；若采用分片设计，则通过平衡分配确保每项结果均由两名评审独立评分。"
        }
      ],
      "uncertainties": [
        "原始要求要求说明随机化、盲法、准确性、完整性、清晰度与可执行性评价以及分歧处理，A作品对每项均给出了具体方案，B作品在多个关键设计参数上标注为待定。",
        "原始要求要求正文800至1200个汉字，A作品明确说明了统计口径，B作品未明确说明字数统计口径。",
        "原始要求要求不提供代码，A作品未提供代码，B作品也未提供代码。",
        "原始要求要求不得使用假设数据，A作品未使用假设数据，B作品也未使用假设数据。"
      ]
    },
    {
      "id": "Q05-plan-pro",
      "scoresA": {
        "accuracy": 5,
        "completeness": 5,
        "clarity": 5,
        "executability": 5
      },
      "scoresB": {
        "accuracy": 4,
        "completeness": 4,
        "clarity": 4,
        "executability": 4
      },
      "verdict": "A胜",
      "severeFailures": [],
      "evidence": [
        {
          "work": "A",
          "quote": "设计采用2（增强模式）×2（信息条件）的配对结构：直接增强组与Plan组构成主比较；另设两个同信息对照组，分别按直接增强与Plan流程运行，但均获得与Plan组逐字相同的确认信息。"
        },
        {
          "work": "A",
          "quote": "拟定任务池包含4类提示词生成任务：文本生成、信息抽取、逻辑推理与格式转换，每类5个，共20个任务。"
        },
        {
          "work": "A",
          "quote": "每模式每任务独立重复3次，共产生240条提示词（4组×20任务×3重复）。"
        },
        {
          "work": "A",
          "quote": "直接增强组仅依据原始任务一次性生成提示词，不主动追问，但可使用任务中已提供的答案或示例。Plan组由增强器先提出关键澄清问题，研究者依据同一案例的真实信息作答，再生成提示词。"
        },
        {
          "work": "A",
          "quote": "先运行Plan组，记录其全部问答及研究者确认信息，将确认信息按任务封存；随后同信息直接组与同信息Plan组均获得该逐字相同的确认信息后执行各自流程。"
        },
        {
          "work": "A",
          "quote": "Plan组问答拟设每任务最多3轮，达到3轮或增强器表示信息充分即终止。"
        },
        {
          "work": "A",
          "quote": "任务与重复的呈现顺序拟采用分层随机：20个任务随机排列，每任务内4组×3重复按随机顺序呈现，同一任务的所有条件在同一区组内完成"
        },
        {
          "work": "A",
          "quote": "两名评审对同一任务的全部12条结果（4组×3重复）盲评。"
        },
        {
          "work": "A",
          "quote": "准确性、完整性、清晰度与可执行性——均针对提示词本身评分，评审所见材料为去标识化提示词文本及对应任务，不含组别与重复标签。"
        },
        {
          "work": "A",
          "quote": "评分拟采用7点Likert量表，每项单独计分，不合成总分"
        },
        {
          "work": "A",
          "quote": "两名评审评分不一致时，先计算各项指标评分差；若差值不超过1分，取均值。若差值超过1分，由两名评审就该项指标复核评分依据并讨论；仍不一致时，引入第三名具备提示词工程经验的评审独立裁定"
        },
        {
          "work": "A",
          "quote": "若各项评分近似正态且方差齐性，拟采用线性混合模型，以模式、信息条件及其交互为固定效应，任务与重复为随机效应；若评分呈明显偏态或离散，改用累积链接混合模型或对齐秩变换后的方差分析。"
        },
        {
          "work": "B",
          "quote": "Plan组的问答轮次与终止方式、同信息对照的具体增强流程、确认信息的取得与封存方式等，均作为待定的设计参数"
        },
        {
          "work": "B",
          "quote": "评分形式拟采用有序评分量表，并辅以必要的文字说明；具体分值范围与锚点将在研究方案中预先设定。"
        },
        {
          "work": "B",
          "quote": "评审是否对同一任务的所有模式结果都评分，将依据实际可行性确定"
        },
        {
          "work": "B",
          "quote": "若采用完全交叉设计，则同一评审对同一任务的全部模式结果评分；若采用分片设计，则通过平衡分配确保每项结果均由两名评审独立评分。"
        }
      ],
      "uncertainties": [
        "原始要求要求说明随机化、盲法、准确性、完整性、清晰度与可执行性评价以及分歧处理，A作品对每项均给出了具体方案，B作品在多个关键设计参数上标注为待定。",
        "原始要求要求正文800至1200个汉字，A作品明确说明了统计口径，B作品未明确说明字数统计口径。",
        "原始要求要求不提供代码，A作品未提供代码，B作品也未提供代码。",
        "原始要求要求不得使用假设数据，A作品未使用假设数据，B作品也未使用假设数据。"
      ]
    }
  ]
}
```