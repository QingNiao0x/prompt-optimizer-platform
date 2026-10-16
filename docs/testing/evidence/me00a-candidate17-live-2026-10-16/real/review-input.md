你是独立AI辅助评审，不是人工领域专家。比较同一要求和信息条件下的匿名作品。准确性、完整性、清晰度、可执行性分别按1—5评分：1无法使用，2需大改，3基本可用但有缺口，4满足要求仅小改，5充分且精确。不要奖励长度或标题。原交付、事实、明确规则、篇幅、代码行为和格式严重失败独立记录，不能被平均分抵消。资料不足不允许编造，也不应把整篇退回问卷或空表。仅按用户原要求及实际信息评价，不新增代码、示例、量表或资料要求；作品自报合规不是证据。给每对比较A胜/B胜/平/双方失败，引用所属作品的完整原句，说明不确定性和无法判定之处。仅输出JSON：cases数组，每项含id、scoresA、scoresB、verdict、severeFailures、evidence（含work与quote）及uncertainties。不要补写作品或答案。

案例requests-title
原始要求：
使用requests库爬取网页标题。
实际确认信息：
无
作品A：
# 使用 requests 库爬取网页标题 — 实现方案

## 一、已知事实、用户确认信息与必要假设

**已知事实**
- 目标：使用 Python `requests` 库爬取网页标题。
- 原始需求未提供：目标 URL、输出形式、解析方式、异常处理口径。

**用户确认信息**
- 无（原始需求仅给出目标）。

**必要假设（拟议设计，待确认）**
- 输入：函数参数传入，支持单个 URL（`str`）与 URL 列表（`Iterable[str]`）两种入口。
- 输出：默认返回结构化 `list[dict]`，字段为 `url`、`status`、`title`、`error`；可选 `as_json=True` 时返回 JSON 字符串。
- 解析：拟议主方案使用标准库 `html.parser`（零额外依赖）；候选方案为 `BeautifulSoup`（需额外依赖，容错更强）。
- 限速：多 URL 时默认串行 + 可配置 `delay`（默认 0 秒，避免对目标站点造成压力）。
- 超时：默认 `timeout=(5, 10)`（连接 5s，读取 10s）。
- User-Agent：默认使用一个通用 UA 字符串，可配置覆盖。

> 以上均为拟议默认，未与用户确认，落地前需确认。

---

## 二、整体思路与关键流程

1. 校验输入（URL 类型、非空、scheme 合法性）。
2. 对每个 URL：
   - 使用 `requests.get(url, headers=..., timeout=..., allow_redirects=True)` 发起 GET。
   - 检查状态码，非 2xx 记录错误并跳过解析。
   - 检查 `Content-Type` 是否包含 `text/html` 或 `application/xhtml+xml`，否则标记为非 HTML。
   - 按 `response.encoding`（requests 依据响应头/内容推断）解码；若为 `None` 或解码失败，回退到 `response.apparent_encoding`，再回退到 `utf-8` 并 `errors="replace"`。
   - 用 `html.parser` 提取 `<title>` 文本，去除首尾空白与内部多余空白。
   - 空标题视为无有效标题。
3. 多 URL 时按顺序遍历，每个请求之间 `time.sleep(delay)`。
4. 汇总为结构化结果返回。

**关键流程伪代码**
```
for url in urls:
    validate(url) -> 非法则记录 error
    try:
        resp = requests.get(...)
    except Timeout -> error="timeout"
    except ConnectionError -> error="connection_error"
    except RequestException -> error="request_error"
    if not 2xx: error=f"http_{status}"
    if not html: error="non_html"
    title = extract_title(decode(resp))
    if title is None: error="no_title"
    elif title == "": error="empty_title"
    append result
    sleep(delay)
```

---

## 三、模块划分与接口契约

### 新增模块：`web_title_fetcher.py`

```python
from typing import Iterable, Union, Optional
import json
import time
import requests
from html.parser import HTMLParser

DEFAULT_UA = "Mozilla/5.0 (compatible; TitleFetcher/1.0)"
DEFAULT_TIMEOUT = (5, 10)


class TitleExtractionError(Exception):
    """标题提取相关异常（保留给需要抛错的调用方）。"""


class _TitleParser(HTMLParser):
    def __init__(self) -> None: ...
    def handle_starttag(self, tag, attrs) -> None: ...
    def handle_endtag(self, tag) -> None: ...
    def handle_data(self, data) -> None: ...
    @property
    def title(self) -> Optional[str]: ...


def extract_title(html_text: str) -> Optional[str]:
    """
    从 HTML 文本中提取 <title> 文本。
    返回：去除首尾空白后的标题字符串；无 <title> 返回 None；空标题返回 ""。
    """


def _decode_response(resp: requests.Response) -> str:
    """
    按响应声明编码解码；失败时回退 apparent_encoding，再回退 utf-8(errors='replace')。
    """


def fetch_title(
    url: str,
    *,
    timeout: tuple = DEFAULT_TIMEOUT,
    user_agent: str = DEFAULT_UA,
    session: Optional[requests.Session] = None,
) -> dict:
    """
    抓取单个 URL 的标题。
    入参：
        url: 目标 URL（str，非空，需含 http/https scheme）
        timeout: (connect, read) 秒
        user_agent: UA 字符串
        session: 可选 requests.Session，便于复用连接与测试注入
    返回：
        {"url": str, "status": int | None, "title": str | None, "error": str | None}
    异常：
        不抛出网络异常；所有可预期异常转为 error 字段。
        仅当 url 非法（None/空/非 str/无 scheme）时抛 ValueError。
    """


def fetch_titles(
    urls: Union[str, Iterable[str]],
    *,
    timeout: tuple = DEFAULT_TIMEOUT,
    user_agent: str = DEFAULT_UA,
    delay: float = 0.0,
    as_json: bool = False,
    session: Optional[requests.Session] = None,
) -> Union[list, str]:
    """
    批量抓取标题。
    入参：
        urls: 单个 URL 字符串，或 URL 可迭代对象
        delay: 每个请求之间的间隔秒数（>=0）
        as_json: True 时返回 JSON 字符串（ensure_ascii=False）
    返回：
        list[dict] 或 JSON 字符串
    异常：
        urls 为 None 或空集合时抛 ValueError。
    """
```

### 接口契约要点

| 项 | 约定 |
|---|---|
| 输入 URL | 必须为 `str`，非空，含 `http://` 或 `https://`；否则 `ValueError` |
| 单 URL 返回 | `dict`，字段固定为 `url/status/title/error` |
| 多 URL 返回 | `list[dict]`，顺序与输入一致 |
| 网络异常 | 不抛出，写入 `error` 字段 |
| 非法输入 | 抛 `ValueError`（调用方错误，非运行时错误） |
| 日志 | 不打印 URL 中的敏感 query（如 token），仅记录 host 与 path |

---

## 四、关键代码

```python
import json
import time
from html.parser import HTMLParser
from typing import Iterable, Optional, Union
from urllib.parse import urlparse

import requests

DEFAULT_UA = "Mozilla/5.0 (compatible; TitleFetcher/1.0)"
DEFAULT_TIMEOUT = (5, 10)


class _TitleParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self._in_title = False
        self._parts: list[str] = []
        self._found = False

    def handle_starttag(self, tag, attrs):
        if tag.lower() == "title":
            self._in_title = True
            self._found = True

    def handle_endtag(self, tag):
        if tag.lower() == "title":
            self._in_title = False

    def handle_data(self, data):
        if self._in_title:
            self._parts.append(data)

    @property
    def found(self) -> bool:
        return self._found

    @property
    def title(self) -> Optional[str]:
        if not self._found:
            return None
        return " ".join("".join(self._parts).split())


def extract_title(html_text: str) -> Optional[str]:
    parser = _TitleParser()
    try:
        parser.feed(html_text)
        parser.close()
    except Exception:
        # 解析异常视为无标题，不吞掉原始网络错误
        return None
    return parser.title


def _decode_response(resp: requests.Response) -> str:
    encoding = resp.encoding
    if encoding:
        try:
            return resp.content.decode(encoding, errors="strict")
        except (LookupError, UnicodeDecodeError):
            pass
    fallback = resp.apparent_encoding or "utf-8"
    try:
        return resp.content.decode(fallback, errors="strict")
    except (LookupError, UnicodeDecodeError):
        return resp.content.decode("utf-8", errors="replace")


def _validate_url(url) -> str:
    if not isinstance(url, str) or not url.strip():
        raise ValueError("url must be a non-empty string")
    parsed = urlparse(url)
    if parsed.scheme not in ("http", "https") or not parsed.netloc:
        raise ValueError(f"invalid url: {url!r}")
    return url


def fetch_title(
    url: str,
    *,
    timeout: tuple = DEFAULT_TIMEOUT,
    user_agent: str = DEFAULT_UA,
    session: Optional[requests.Session] = None,
) -> dict:
    url = _validate_url(url)
    result = {"url": url, "status": None, "title": None, "error": None}
    http = session or requests
    try:
        resp = http.get(
            url,
            headers={"User-Agent": user_agent},
            timeout=timeout,
            allow_redirects=True,
        )
    except requests.exceptions.Timeout:
        result["error"] = "timeout"
        return result
    except requests.exceptions.ConnectionError:
        result["error"] = "connection_error"
        return result
    except requests.exceptions.RequestException as exc:
        result["error"] = f"request_error: {exc.__class__.__name__}"
        return result

    result["status"] = resp.status_code
    if not (200 <= resp.status_code < 300):
        result["error"] = f"http_{resp.status_code}"
        return result

    ctype = (resp.headers.get("Content-Type") or "").lower()
    if "text/html" not in ctype and "application/xhtml+xml" not in ctype:
        result["error"] = "non_html"
        return result

    html_text = _decode_response(resp)
    title = extract_title(html_text)
    if title is None:
        result["error"] = "no_title"
    elif title == "":
        result["error"] = "empty_title"
    else:
        result["title"] = title
    return result


def fetch_titles(
    urls: Union[str, Iterable[str]],
    *,
    timeout: tuple = DEFAULT_TIMEOUT,
    user_agent: str = DEFAULT_UA,
    delay: float = 0.0,
    as_json: bool = False,
    session: Optional[requests.Session] = None,
) -> Union[list, str]:
    if urls is None:
        raise ValueError("urls must not be None")
    if isinstance(urls, str):
        url_list = [urls]
    else:
        url_list = list(urls)
        if not url_list:
            raise ValueError("urls must not be empty")
    if delay < 0:
        raise ValueError("delay must be >= 0")

    results = []
    for i, u in enumerate(url_list):
        try:
            results.append(fetch_title(u, timeout=timeout,
                                       user_agent=user_agent, session=session))
        except ValueError as exc:
            results.append({"url": u, "status": None,
                            "title": None, "error": f"invalid_url: {exc}"})
        if delay and i < len(url_list) - 1:
            time.sleep(delay)

    if as_json:
        return json.dumps(results, ensure_ascii=False, indent=2)
    return results
```

**说明**
- 单 URL 非法输入直接抛 `ValueError`（调用方错误）；批量入口对单个非法 URL 转为 `error` 字段，避免一个坏 URL 中断整批。
- 未在日志/响应中输出任何凭据；URL 原样返回，若 URL 含敏感 query 由调用方自行脱敏。
- 未读取 `.env`、`*.pem`、`*.key` 或生产配置。

---

## 五、解析方式候选与拟议主方案

| 方案 | 适用条件 | 依赖代价 | 可调整处 |
|---|---|---|---|
| **`html.parser`（拟议主方案）** | 结构规范的 HTML，标题在 `<title>` 内 | 标准库，零依赖 | 可替换为 `lxml` 提升速度 |
| `BeautifulSoup` + `html.parser` | 需要容错、CSS 选择器 | 需 `beautifulsoup4` | 可换 `lxml` 后端 |
| `lxml.html` | 大规模、性能敏感 | 需 `lxml`（C 扩展） | 需处理安装环境 |
| 正则 | 极简场景 | 零依赖 | 对畸形 HTML 不可靠，不推荐 |

> 主方案为拟议，未与用户确认；若目标站点 HTML 畸形严重，建议切换 `BeautifulSoup`。

---

## 六、异常与边界行为

| 场景 | 行为 |
|---|---|
| 网络超时 | `error="timeout"`，`status=None` |
| 连接错误（DNS/拒绝） | `error="connection_error"` |
| 其他 requests 异常 | `error="request_error: <类名>"` |
| 非 2xx | `error="http_<code>"`，保留 `status` |
| 非 HTML Content-Type | `error="non_html"` |
| 无 `<title>` | `error="no_title"` |
| 空标题（`<title></title>` 或全空白） | `error="empty_title"` |
| 重定向 | 默认跟随；最终状态码与内容参与判断 |
| 编码异常 | 依次尝试 `resp.encoding` → `apparent_encoding` → `utf-8(errors="replace")` |
| 非法 URL（单入口） | 抛 `ValueError` |
| 非法 URL（批量入口） | 该条 `error="invalid_url: ..."`，不中断 |
| `urls=None` / 空集合 | 抛 `ValueError` |
| `delay < 0` | 抛 `ValueError` |

**不吞错原则**：网络与解析异常均以明确 `error` 字段返回，不伪造标题；调用方错误（非法入参）直接抛异常。

---

## 七、测试方案

**测试范围**：`extract_title`、`_decode_response`、`fetch_title`、`fetch_titles`。

**Mock 方式**：使用 `unittest.mock.patch` 替换 `requests.get`（或注入 `session` 的 `Mock`），构造 `Mock(status_code=..., headers=..., content=..., encoding=..., apparent_encoding=...)`，不发起真实网络请求。

**用例清单**

正常：
1. 有效 URL + 标准 HTML → 返回正确标题，`error=None`。
2. 标题含首尾空白与内部多空白 → 归一化为单空格。
3. 标题含 HTML 实体（`&amp;`）→ 正确解码。
4. 批量输入多个有效 URL → 顺序一致，全部成功。
5. `as_json=True` → 返回合法 JSON 字符串。

异常：
6. `requests.get` 抛 `Timeout` → `error="timeout"`。
7. 抛 `ConnectionError` → `error="connection_error"`。
8. 抛其他 `RequestException` → `error` 以 `request_error:` 开头。
9. 状态码 404 → `error="http_404"`，`status=404`。
10. `Content-Type: application/json` → `error="non_html"`。
11. HTML 无 `<title>` → `error="no_title"`。
12. `<title></title>` → `error="empty_title"`。
13. 单入口传入 `""` / `None` / `"ftp://x"` → 抛 `ValueError`。
14. 批量入口含非法 URL → 该条 `error` 以 `invalid_url:` 开头，其余正常。
15. `urls=None` 或 `[]` → 抛 `ValueError`。

边界：
16. 重定向：mock 返回 200 且最终内容含标题 → 正常返回。
17. 编码异常：`encoding` 为 `None`，`apparent_encoding="utf-8"`，内容含非 ASCII → 正确解码。
18. `encoding` 为非法值（如 `"x-unknown"`）→ 回退到 `apparent_encoding`。
19. 内容含非法字节 → 回退 `utf-8(errors="replace")`，不抛异常。
20. `delay>0` 时验证 `time.sleep` 被调用次数为 `n-1`（mock `time.sleep`）。
21. 标题跨多行 → 归一化为单行。

**兼容性**：新增模块独立，不修改既有模块接口；若需接入现有代码，仅新增调用点。

---

## 八、待确认事项

1. 输入方式：函数参数 vs 配置文件/CLI？
2. 输出形式：`list[dict]` vs JSON vs 纯标题列表？
3. 解析库：是否允许引入 `BeautifulSoup`/`lxml`？
4. 多 URL 限速默认值与并发需求？
5. 是否需要 CLI 入口与日志输出？

以上未确认前，均按拟议默认实现。
作品B：
# 使用 requests 库爬取网页标题

下面是一个完整的示例，演示如何使用 `requests` 库配合 `BeautifulSoup` 爬取网页标题。

## 基础版本

```python
import requests
from bs4 import BeautifulSoup

def get_page_title(url):
    """获取网页标题"""
    try:
        # 发送 GET 请求
        headers = {
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) '
                          'AppleWebKit/537.36 (KHTML, like Gecko) '
                          'Chrome/120.0.0.0 Safari/537.36'
        }
        response = requests.get(url, headers=headers, timeout=10)
        response.raise_for_status()  # 检查 HTTP 错误
        
        # 自动处理编码
        response.encoding = response.apparent_encoding
        
        # 解析 HTML
        soup = BeautifulSoup(response.text, 'html.parser')
        title = soup.title.string.strip() if soup.title else '无标题'
        return title
        
    except requests.exceptions.RequestException as e:
        return f'请求失败: {e}'


if __name__ == '__main__':
    url = 'https://www.python.org'
    print(f'网页标题: {get_page_title(url)}')
```

## 批量爬取多个网页

```python
import requests
from bs4 import BeautifulSoup
from concurrent.futures import ThreadPoolExecutor

HEADERS = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) '
                  'AppleWebKit/537.36 (KHTML, like Gecko) '
                  'Chrome/120.0.0.0 Safari/537.36'
}

def fetch_title(url):
    try:
        resp = requests.get(url, headers=HEADERS, timeout=10)
        resp.raise_for_status()
        resp.encoding = resp.apparent_encoding
        soup = BeautifulSoup(resp.text, 'html.parser')
        return url, soup.title.string.strip() if soup.title else '无标题'
    except Exception as e:
        return url, f'错误: {e}'


if __name__ == '__main__':
    urls = [
        'https://www.python.org',
        'https://www.baidu.com',
        'https://github.com',
        'https://www.zhihu.com',
    ]
    
    # 使用线程池并发爬取
    with ThreadPoolExecutor(max_workers=5) as executor:
        for url, title in executor.map(fetch_title, urls):
            print(f'{url}\n  → {title}\n')
```

## 关键点说明

| 要点 | 说明 |
|------|------|
| **User-Agent** | 伪装成浏览器，避免被网站拒绝 |
| **timeout** | 设置超时，防止程序卡死 |
| **raise_for_status()** | 遇到 4xx/5xx 状态码时抛出异常 |
| **apparent_encoding** | 自动识别网页编码，避免中文乱码 |
| **BeautifulSoup** | 解析 HTML，提取 `<title>` 标签内容 |

## 仅用 requests + 正则（不依赖 bs4）

如果不想安装 `BeautifulSoup`，可以用正则：

```python
import re
import requests

def get_title(url):
    headers = {'User-Agent': 'Mozilla/5.0'}
    resp = requests.get(url, headers=headers, timeout=10)
    resp.encoding = resp.apparent_encoding
    match = re.search(r'<title[^>]*>(.*?)</title>', resp.text, re.S | re.I)
    return match.group(1).strip() if match else '无标题'

print(get_title('https://www.python.org'))
```

## 安装依赖

```bash
pip install requests beautifulsoup4
```

## 注意事项

1. **遵守 robots.txt**：爬取前查看目标网站的爬虫协议
2. **控制频率**：加 `time.sleep()` 避免给服务器造成压力
3. **合法合规**：仅用于学习研究，不要爬取敏感或受版权保护的内容
4. **异常处理**：网络请求容易失败，务必做好异常捕获

需要我扩展某个方向吗？比如**异步爬取（aiohttp）**、**保存到 CSV**、或**处理 JavaScript 渲染的页面（Selenium/Playwright）**。

案例mortality-2015-2025
原始要求：
我是一名科研工作者,我想要撰写2015-2025年某地区心脑血管疾病死亡率特征分析,我需要从以下几个方面进行分析:
1.时间序列的长期趋势和季节性趋势
2.分性别\地区\分人群\分亚类去进行比较
3.进行减寿分析:年度的\性别的yll和yll率
4.采用Arriaga分解方法去分析
实际确认信息：
无
作品A：
# 2015-2025年某地区心脑血管疾病死亡率特征分析——研究框架与实施指南

作为科研工作者，您提出的分析框架非常系统。下面我为您提供一份**完整的研究设计、方法学要点、统计实现路径和论文写作框架**，供您直接参考或调整使用。

---

## 一、研究总体设计

### 1.1 研究类型
基于人群的**回顾性描述性流行病学研究**，结合**时间序列分析**与**寿命损失分析**。

### 1.2 数据来源建议
| 数据类别 | 来源 | 用途 |
|---|---|---|
| 死亡数据 | 死因监测系统/疾控中心/公安户籍 | 死亡率、YLL |
| 人口数据 | 统计局/公安 | 分母、标化 |
| 标准人口 | 2010年全国人口普查 / WHO世界标准人口 | 标化死亡率 |
| ICD编码 | ICD-10（I00-I99，细分I60-I69脑血管、I20-I25缺血性心脏病等） | 亚类分析 |

### 1.3 分析维度
- **时间**：2015-2025年（年度、月份/季度）
- **人群**：性别（男/女）、年龄组（0-14、15-44、45-59、60-74、75+）
- **地区**：城/乡、不同行政区划
- **亚类**：缺血性心脏病（I20-I25）、脑血管病（I60-I69）、高血压性心脏病（I10-I15）、其他

---

## 二、时间序列长期趋势与季节性趋势

### 2.1 长期趋势分析

**（1）指标计算**
- 粗死亡率（CDR）= 某年死亡数 / 年平均人口 × 100,000
- 年龄标化死亡率（ASMR）：直接法，以2010年全国普查人口为标准
- 标化率95%CI：采用Poisson近似或Gamma法

**（2）趋势检验方法**
| 方法 | 适用场景 | 说明 |
|---|---|---|
| **Joinpoint回归** | 识别拐点、分段趋势 | 推荐首选，NCI Joinpoint软件 |
| **APC模型** | 年龄-时期-队列效应 | 需注意出生队列共线性 |
| **Cochran-Armitage趋势检验** | 简单线性趋势 | 分类资料 |
| **Mann-Kendall检验** | 非参数单调趋势 | 时间序列 |

**Joinpoint模型核心参数**：
- APC（年度变化百分比）：$APC = (e^{\beta}-1)\times100\%$
- AAPC（平均年度变化百分比）：加权平均

**（3）结果呈现**
- 折线图：2015-2025年ASMR变化趋势
- 表格：各时间段APC、AAPC及95%CI

### 2.2 季节性趋势分析

**（1）方法选择**
| 方法 | 优势 | 软件 |
|---|---|---|
| **X-13-ARIMA-SEATS** | 官方标准，分离趋势/季节/随机 | R `seasonal`包 |
| **STL分解** | 稳健，适合非线性 | R `stats::stl()` |
| **循环分布法** | 计算季节集中度 | 圆形统计 |
| **负二项/泊松回归+周期项** | 可调整协变量 | R `glm.nb()` |

**（2）关键指标**
- 季节指数（Seasonal Index）：>100%为高发月
- 季节集中度（$r$值，圆形分布）：$r>0.3$提示明显季节性
- 高峰月/低谷月

**（3）模型示例（R）**
```r
library(seasonal)
ts_data <- ts(mortality_rate, start=c(2015,1), frequency=12)
fit <- seas(ts_data)
summary(fit)
plot(fit)
```

**（4）结果呈现**
- 季节分解图（趋势+季节+残差）
- 月度死亡率热力图
- 圆形分布玫瑰图

---

## 三、分性别、地区、人群、亚类的比较

### 3.1 分层分析策略

**（1）性别差异**
- 男女ASMR比较：率比（RR）及95%CI
- 趋势差异：Joinpoint分性别拟合，比较APC
- 统计检验：Poisson回归交互项（性别×年份）

**（2）地区差异**
- 城乡、区域间ASMR比较
- 空间聚集性：Moran's I、Getis-Ord Gi*
- 地图可视化（ArcGIS / R `sf`+`ggplot2`）

**（3）年龄人群差异**
- 年龄别死亡率（Age-specific mortality rate）
- 年龄组构成比变化
- 期望寿命分解（见第四部分）

**（4）亚类差异**
- 缺血性心脏病 vs 脑血管病 vs 高血压性心脏病
- 各亚类ASMR趋势、构成比变化
- 亚类间RR比较

### 3.2 统计方法
| 目的 | 方法 |
|---|---|
| 率比较 | χ²检验、Poisson回归 |
| 趋势比较 | Joinpoint平行性检验 |
| 多因素调整 | 负二项回归、Poisson回归 |
| 空间分析 | Moran's I、空间扫描统计 |

### 3.3 结果呈现
- 分层ASMR趋势图（多线图）
- 森林图：各亚组RR
- 表格：分性别/地区/年龄/亚类的ASMR及APC

---

## 四、减寿分析（YLL与YLL率）

### 4.1 核心概念

**潜在减寿年数（YLL）**：
$$YLL = \sum_{i} d_i \times (L - a_i)$$

其中：
- $d_i$：某年龄组死亡数
- $L$：目标生存年龄（通常取70岁、75岁或期望寿命）
- $a_i$：死亡年龄组中值

**YLL率**：
$$YLL率 = \frac{YLL}{人口数} \times 1000 \text{ 或 } 100,000$$

**标化YLL率**：以标准人口年龄结构标化

### 4.2 分析维度
1. **年度YLL和YLL率**：2015-2025年逐年计算
2. **分性别YLL和YLL率**：男女分别计算
3. **分年龄组**：识别主要减寿年龄段
4. **分亚类**：各亚类YLL贡献

### 4.3 计算工具
- **WHO YLL计算模板**（Excel）
- **R包**：`DALY`、`epiR`
- **GBD研究标准方法**：采用GBD 2019标准期望寿命表

### 4.4 结果呈现
- YLL率年度趋势图
- 分性别YLL率对比柱状图
- 年龄组YLL贡献堆叠图
- 亚类YLL构成饼图

---

## 五、Arriaga分解方法

### 5.1 方法原理

Arriaga分解用于**量化各年龄组/死因对期望寿命差异的贡献**。核心公式：

**年龄组n对期望寿命差异的贡献**：
$$n\Delta_x = \frac{l_x}{l_0}\left[\frac{L_x^n}{l_x^n} - \frac{L_x^o}{l_x^o}\right] + \frac{T_{x+n}}{l_0}\left[\frac{l_x^n}{l_x^o} - \frac{l_{x+n}^n}{l_{x+n}^o}\right]$$

其中：
- $l_x$：生存人数
- $L_x$：人年数
- $T_x$：总人年数
- 上标n/o：新/旧时期

**直接效应**（死亡率变化）+ **间接效应**（存活人数变化）= 总贡献

### 5.2 分析应用

**（1）时间维度分解**
- 比较2015年 vs 2025年期望寿命差异
- 分解各年龄组、各死因的贡献

**（2）性别维度分解**
- 男女期望寿命差异分解
- 心脑血管疾病贡献占比

**（3）地区维度分解**
- 城乡/区域间期望寿命差异
- 心脑血管疾病贡献

### 5.3 软件实现
| 工具 | 说明 |
|---|---|
| **R包 `DemoDecomp`** | 官方推荐，支持Arriaga |
| **R包 `LifeTable`** | 生命表构建 |
| **Excel模板** | Arriaga原始方法 |
| **Stata `arriaga`** | 用户自编命令 |

**R示例**：
```r
library(DemoDecomp)
# 构建两个时期生命表
# 使用 arriaga() 函数分解
decomp <- arriaga(life_table_2015, life_table_2025, 
                  age = age_groups, 
                  cause = cause_data)
```

### 5.4 结果呈现
- 贡献分解表：各年龄组/死因贡献值（年）
- 瀑布图：贡献正负方向
- 堆叠柱状图：分性别/地区贡献

---

## 六、论文写作框架建议

### 6.1 结构
```
1. 引言
   - 心脑血管疾病全球/区域疾病负担
   - 研究地区背景
   - 研究目的与创新点

2. 资料与方法
   2.1 数据来源
   2.2 质量控制
   2.3 统计分析
       - 标化率计算
       - Joinpoint回归
       - 季节性分解
       - YLL计算
       - Arriaga分解
   2.4 伦理审查

3. 结果
   3.1 总体趋势（长期+季节性）
   3.2 分层比较（性别/地区/年龄/亚类）
   3.3 YLL分析
   3.4 Arriaga分解结果

4. 讨论
   - 主要发现
   - 与国内外研究比较
   - 机制解释
   - 政策建议
   - 局限性

5. 结论
```

### 6.2 创新点建议
1. **方法整合**：将Joinpoint、季节性分解、YLL、Arriaga整合于同一研究
2. **长时间跨度**：2015-2025年11年数据，覆盖COVID-19前后
3. **多维度分解**：时间+性别+地区+亚类四维分解
4. **政策转化**：为精准防控提供证据

---

## 七、关键注意事项

### 7.1 数据质量
- **死因编码一致性**：ICD-10版本变化
- **漏报调查**：使用捕获-再捕获法校正
- **人口分母**：注意流动人口
- **2020-2022年COVID影响**：需单独讨论

### 7.2 方法学陷阱
| 问题 | 对策 |
|---|---|
| 年龄标化标准不一致 | 统一使用2010年全国普查 |
| Joinpoint拐点过多 | 限制最大拐点数（通常≤5） |
| YLL目标年龄选择 | 敏感性分析（70/75/期望寿命） |
| Arriaga分解死因竞争 | 使用多死因分解方法 |
| 季节性假阳性 | 结合圆形分布+ARIMA |

### 7.3 敏感性分析
- 不同标化人口
- 不同YLL目标年龄
- 排除COVID年份
- 不同Joinpoint参数

---

## 八、推荐软件与工具汇总

| 分析任务 | 推荐工具 |
|---|---|
| 数据清洗 | R `tidyverse`、Python `pandas` |
| 标化率 | R `epitools`、`dsr` |
| Joinpoint | NCI Joinpoint、R `segmented` |
| 季节性 | R `seasonal`、`forecast` |
| YLL | WHO模板、R `DALY` |
| Arriaga | R `DemoDecomp` |
| 空间分析 | ArcGIS、R `sf`+`spdep` |
| 可视化 | R `ggplot2`、`patchwork` |
| 报告 | R Markdown / Quarto |

---

## 九、下一步行动建议

1. **明确研究地区**：不同地区数据可及性差异大
2. **确定目标年龄L**：建议70岁（中国常用）或期望寿命
3. **伦理审批**：死因数据需伦理委员会批准
4. **预分析**：先做2015-2019年，验证方法后再扩展
5. **合作**：与疾控中心、统计局建立数据共享

---

如果您能提供**具体地区、数据可及性、目标期刊**等信息，我可以进一步帮您：
- 定制统计分析计划（SAP）
- 撰写R/Python代码
- 设计图表模板
- 起草论文方法学部分

是否需要我针对某一模块（如Arriaga分解的R代码、Joinpoint操作步骤）展开详细说明？
作品B：
# 2015—2025年某地区心脑血管疾病死亡率特征分析

## 一、分析目的与总体框架

本文旨在对某地区2015—2025年心脑血管疾病（cardiovascular and cerebrovascular diseases，以下简称CVD）死亡率的特征进行系统分析，覆盖四个相互衔接的层面：其一，识别死亡率的长期趋势与季节性波动；其二，按性别、地区、人群与亚类开展分组比较；其三，计算年度与性别的减寿年数（years of life lost，YLL）及YLL率；其四，运用Arriaga分解方法量化不同年龄组对预期寿命或死亡率差异的贡献。分析的基本逻辑是：先明确数据来源、研究对象、指标定义与统计口径，再在统一口径下开展时间序列、分组比较、减寿分析与分解分析，使各部分的分子、分母、时间边界与人群范围保持一致。

需要特别说明的是，本研究的地区范围、数据来源、参考寿命表与亚类定义尚未最终确定。以下正文在方法层面给出候选方案、适用条件与拟议主方案，在实证数值层面凡依赖未确认数据与口径者均标注为待确认，不填入示例值或惯例值。待相关条件明确后，可在本框架下直接补充计算并形成最终结论。

## 二、数据来源、研究对象、指标定义与统计口径

### （一）数据来源与研究对象

本研究的目标人群为某地区2015—2025年的常住人口或户籍人口，具体范围（省、市或区域）待确认。死亡数据拟来源于该地区全人群死因监测系统、居民死亡医学证明书数据库或同级卫生健康统计年报；人口分母拟来源于同级统计年鉴或公安人口登记数据。若涉及跨地区比较，需明确比较单元为地市级、区县级还是城乡二分，并保证各单元死亡登记完整性与人口分母口径一致。

数据可用年份、分性别／地区／人群／亚类的字段完整性、死因编码版本（如ICD-10）与编码质量待确认。若死亡数据存在漏报、迟报或编码不明，需在分析前进行完整性评估，并在结果解释中说明其对死亡率水平与趋势的影响方向。

### （二）指标定义与统计口径

本研究的核心指标包括：

1. **粗死亡率**：某年某人群CVD死亡数除以同年该人群平均人口数，通常以每10万人口表示。该指标受人口年龄结构影响，跨年或跨地区比较时需谨慎。
2. **年龄别死亡率**：按5岁或10岁年龄组计算的死亡率，是标准化率、寿命表与YLL计算的基础。
3. **年龄标准化死亡率**：以标准人口年龄结构对年龄别死亡率加权得到，用于消除年龄结构差异。标准人口可选择Segi世界标准人口、全国普查人口或研究地区基期人口，具体选择待确认。
4. **YLL**：某年某人群因CVD死亡导致的减寿年数，等于各年龄组死亡数乘以该年龄组死亡时的期望剩余寿命，再求和。
5. **YLL率**：YLL除以同期该人群人口数，通常以每10万人口或每千人口表示，用于跨人群比较。

统计口径需统一以下要素：死亡日期与人口分母的时间边界（如日历年）、年龄的计算方式（实足年龄或出生队列）、CVD的ICD编码范围（如I00—I99或特定亚类）、人群范围（常住或户籍）、以及是否纳入不明原因死亡。上述口径待确认后应在全文中保持一致。

### （三）已知事实、用户确认信息与必要假设

**已知事实**：心脑血管疾病是全球及我国主要死因之一，其死亡率通常随年龄上升，且在不同性别、城乡与地区间存在差异；季节性波动在部分CVD亚类中较为常见。上述为一般性专业共识，不构成本研究已观察到的结果。

**用户确认信息**：本任务要求覆盖2015—2025年、某地区、心脑血管疾病、分性别／地区／人群／亚类、YLL与YLL率、Arriaga分解。除此之外，地区范围、数据来源、参考寿命表与亚类定义均未确认。

**必要假设**：在数据与口径明确前，本文假设死亡登记与人口分母在年份与人群范围上可对齐，且CVD死因编码可稳定识别。该假设仅为方法讨论之前提，若实际数据不满足，需在对应部分调整。

## 三、时间序列长期趋势与季节性趋势分析

### （一）分析目的与指标选择

时间序列分析旨在回答两个问题：2015—2025年间该地区CVD死亡率是否存在长期上升、下降或平稳趋势；死亡率是否存在稳定的季节性波动，以及季节性波动的幅度与相位是否随年份或人群变化。分析对象可为粗死亡率、年龄标准化死亡率、年龄别死亡率或YLL率，具体选择取决于研究问题与数据质量。若关注死亡水平本身，宜以年龄标准化死亡率为主；若关注疾病负担，可同时报告YLL率。

### （二）长期趋势识别方法及适用条件

长期趋势的候选方法包括：

1. **年度变化百分比（annual percent change，APC）与平均年度变化百分比（average annual percent change，AAPC）**：基于对数线性回归，适用于死亡率随时间近似呈指数或线性变化的情形。需注意死亡数较小时率的稳定性，以及端点年份对AAPC的影响。
2. **Joinpoint回归**：允许趋势在不同时间段发生转折，适用于趋势并非单调、可能存在拐点的情形。需设定最小观测年数与最大拐点数，并报告拐点位置及其不确定性。
3. **年龄—时期—队列（APC）模型**：用于分离年龄、时期与队列效应，但存在识别问题，需附加约束或采用内生性检验，适用于有足够长年份与年龄组结构的数据。
4. **平滑样条或广义相加模型**：适用于探索非线性趋势，但需避免过拟合，并明确自由度选择依据。

拟议主方案为：以年龄标准化死亡率为主要指标，先计算年度率及其置信区间，再采用Joinpoint回归识别趋势转折，同时报告AAPC。若数据年份较短或死亡数较少，则退化为对数线性回归并报告APC。上述选择需在数据确认后最终确定。

### （三）季节性趋势识别方法及适用条件

季节性分析的候选方法包括：

1. **月度或季度死亡率描述**：计算各月／季度的死亡率或死亡数占比，观察高峰与低谷。适用于死亡数足够、月度分母可得的地区。
2. **季节指数或集中度指标**：如季节指数、圆分布分析，用于量化季节波动的强度与相位。
3. **时间序列分解**：如STL分解、X-13ARIMA-SEATS，将序列分解为趋势、季节与残差成分。适用于月度或季度数据，且序列长度通常需覆盖多个完整周期。
4. **广义线性模型或分布滞后非线性模型**：在控制长期趋势与气温等协变量后估计季节效应，适用于探讨季节性与气象因素的关联。

拟议主方案为：以月度年龄标准化死亡率为基础，先做描述性季节分析，再采用STL分解或X-13ARIMA-SEATS提取季节成分；若数据仅到年度，则季节性分析不可行，需明确标注。季节性分析需注意：冬季高峰可能与呼吸道感染、气温骤降及心血管事件增加有关，但本研究若未控制相关协变量，只能描述季节性模式，不能推断因果。

### （四）结果解释框架与不确定性

长期趋势的解释需结合人口老龄化、医疗可及性、危险因素流行变化与死因编码质量。若年龄标准化死亡率下降而粗死亡率上升，可能反映人口老龄化效应；若两者同向变化，则需进一步分析年龄别死亡率。季节性解释需区分“死亡季节变动”与“登记季节变动”，后者可能源于节假日、报告延迟或编码流程。所有趋势与季节结论均需报告不确定性区间，并说明缺失数据、死亡数少与口径变化对结果的影响。

## 四、分性别、地区、人群、亚类的比较分析

### （一）分组定义与分母口径

分组比较的前提是各组定义清晰、分母口径一致。拟议分组如下：

1. **性别**：男、女；若数据允许，可增加未知性别并单独说明。
2. **地区**：按研究地区内部的地理单元划分，如城乡、地市或区县。需明确是常住人口还是户籍人口，以及跨地区比较单元是否具有可比性。
3. **人群**：可按年龄组、职业、教育程度、收入水平或医保类型划分。具体维度取决于数据字段可用性，待确认。
4. **亚类**：CVD亚类可按ICD-10编码划分，如缺血性心脏病（I20—I25）、脑血管病（I60—I69）、高血压性心脏病（I10—I15）等。亚类定义需在分析前固定，避免同一疾病在不同年份归入不同类别。

分母口径需与分子一致：若死亡数按常住人口统计，人口分母也应为常住人口；若按户籍统计，则分母为户籍人口。跨地区比较时，若各地区人口年龄结构差异较大，应使用年龄标准化率或直接比较年龄别死亡率。

### （二）比较逻辑与统计方法

分组比较的逻辑是：先描述各组死亡率水平与时间趋势，再检验组间差异是否具有统计学意义，最后结合YLL率与Arriaga分解解释差异来源。候选统计方法包括：

1. **率比与率差**：计算各组与参照组的率比（RR）与率差（RD），并报告置信区间。
2. **泊松回归或负二项回归**：用于估计组间率比，可控制年龄、年份等协变量。若死亡数存在过度离散，宜采用负二项模型。
3. **年龄标准化与直接比较**：当组间年龄结构差异大时，标准化率是必要补充。
4. **趋势检验**：如Cochran-Armitage趋势检验或Joinpoint分组分析，用于判断各组趋势是否不同。

拟议主方案为：以年龄标准化死亡率为主要比较指标，辅以年龄别死亡率热图或表格；采用泊松回归估计组间率比，并报告95%置信区间。若数据存在地区分层，可考虑多层模型或随机效应模型。

### （三）结果解释框架与偏倚

分组比较的解释需注意以下偏倚来源：死亡登记完整性在不同地区或人群间可能存在差异；年龄结构、社会经济地位与医疗可及性可能同时影响死亡率与分组变量；亚类定义变化可能导致趋势断裂。因此，组间差异不能直接解释为因果效应，而应结合数据质量与背景信息谨慎推断。若某些组死亡数过少，率估计不稳定，需合并年份或年龄组，或采用平滑方法。

## 五、年度与性别的YLL及YLL率减寿分析

### （一）YLL与YLL率的定义与计算逻辑

YLL衡量因过早死亡导致的寿命损失，其计算逻辑为：对每个年龄组，将该年龄组死亡数乘以死亡时的期望剩余寿命，再对所有年龄组求和。公式可表示为：

YLL = Σ_a (D_a × e_a)

其中D_a为年龄组a的死亡数，e_a为该年龄组的期望剩余寿命。YLL率则为YLL除以同期人口数，通常以每10万人口或每千人口表示。

年度YLL按日历年计算，性别YLL按男、女分别计算。若需跨年或跨性别比较，应使用YLL率而非YLL绝对数，以避免人口规模差异的影响。

### （二）参考寿命表或标准寿命的选择依据

YLL计算的关键参数是期望剩余寿命e_a，其来源有以下候选方案：

1. **研究地区同期寿命表**：最能反映当地实际死亡水平与预期寿命，但需该地区完整生命表数据，且可能受死亡登记质量影响。
2. **全国寿命表**：适用于研究地区寿命表不可得或质量不足的情形，但可能低估或高估当地减寿水平。
3. **标准寿命表**：如Segi世界标准寿命表、WHO标准寿命表或全球疾病负担（GBD）标准寿命表。其优点是跨地区可比，但可能与当地实际寿命水平存在差异。
4. **固定参考寿命**：如以25岁或某一年龄为起点计算剩余寿命，适用于特定研究问题，但需明确说明选择依据。

拟议主方案为：优先采用研究地区同期寿命表；若不可得，则采用全国寿命表并说明其局限性；若需跨地区比较，可同时报告基于标准寿命表的YLL率作为敏感性分析。参考寿命表的选择会直接影响YLL水平与不同人群的比较，因此必须在结果中明确标注。

### （三）年度与性别YLL分析的解释框架

年度YLL分析可揭示CVD减寿负担的时间变化：若YLL率下降，可能反映死亡率下降或死亡年龄后移；若YLL率上升，可能反映早死增加或人口结构变化。性别YLL分析可比较男女减寿负担差异：通常男性CVD死亡年龄较早，YLL可能相对较高，但具体结果取决于当地数据。解释时需结合年龄别死亡率与死亡年龄分布，避免仅凭YLL总量下结论。

不确定性主要来源于：死亡数少时YLL估计不稳定；参考寿命表选择不同导致YLL水平差异；人口分母不准确导致YLL率偏倚。需在结果中报告敏感性分析或至少说明不确定性来源。

## 六、Arriaga分解方法的应用与结果解释

### （一）分解对象与比较单元

Arriaga分解用于量化不同年龄组对两个人群或两个时期之间预期寿命差异的贡献。本研究的分解对象可为：

1. **年度比较**：如2015年与2025年该地区CVD死亡率的差异，分解各年龄组的贡献。
2. **性别比较**：如男性与女性CVD死亡率的差异，分解各年龄组的贡献。
3. **地区比较**：如城乡或不同地市之间的差异，分解各年龄组的贡献。

比较单元需明确：是以预期寿命差异为分解对象，还是以死亡率差异为分解对象。Arriaga方法通常以预期寿命差异为分解对象，但也可扩展至其他综合指标。年龄组划分需统一，通常采用5岁组或10岁组，并确保两组人群的年龄组边界一致。

### （二）分解步骤

Arriaga分解的基本步骤包括：

1. **构建寿命表**：对两个比较人群分别构建简略寿命表，得到各年龄组的死亡概率、生存人年数与预期寿命。
2. **计算年龄组贡献**：将预期寿命差异分解为各年龄组的直接效应、间接效应与交互效应。直接效应指某年龄组死亡率差异对预期寿命差异的贡献；间接效应指该年龄组死亡率差异通过影响后续年龄组存活人数而产生的贡献。
3. **汇总与解释**：将各年龄组贡献汇总，得到总差异，并识别贡献最大的年龄组。

具体公式与计算流程可参考Arriaga（1984）及后续扩展方法。若数据不支持完整寿命表构建，可退化为死亡率差异的年龄组分解，但需明确方法差异。

### （三）结果解释框架

Arriaga分解的结果可回答：哪些年龄组的CVD死亡率差异对总体预期寿命差异贡献最大。例如，若中老年组贡献最大，说明CVD死亡主要集中在这些年龄组；若年轻组贡献较大，说明早死对减寿影响显著。解释时需注意：分解结果依赖于寿命表质量与年龄组划分；若死亡数少，年龄组贡献估计不稳定；若两组人群的年龄结构差异大，需谨慎解释直接效应与间接效应。

## 七、缺失数据、偏倚、不确定性与可复现性

本研究在数据与口径未确认前，存在以下主要缺口与不确定性：

1. **地区范围未确认**：研究覆盖的省、市或区域待明确，跨地区比较单元待确认。
2. **数据来源与字段未确认**：死亡数据来源、可用年份、分性别／地区／人群／亚类字段与人口分母口径待确认。
3. **参考寿命表未确认**：YLL计算所用寿命表或标准寿命待确认，其选择会影响减寿年数及不同人群的比较。
4. **亚类定义未确认**：CVD亚类的ICD编码范围与归类规则待确认。
5. **缺失数据与登记质量**：死亡漏报、迟报、编码不明与人口分母误差可能影响所有指标，需在分析前评估并报告。
6. **方法适用条件**：Joinpoint回归、STL分解、Arriaga分解等方法均需满足一定数据条件，若数据不满足，需调整方法或明确标注局限。

为保证可复现性，建议在最终分析中记录：数据提取日期与版本、死因编码版本、人口分母来源、年龄组划分、标准化人口选择、寿命表来源、软件与代码版本。若用户要求，可提供分析代码。

## 八、结论性说明

本文在方法层面完成了2015—2025年某地区心脑血管疾病死亡率特征分析的主体框架，覆盖时间序列长期趋势与季节性趋势、分性别／地区／人群／亚类比较、年度与性别YLL及YLL率、Arriaga分解四个部分。由于地区范围、数据来源、参考寿命表与亚类定义尚未确认，本文未填入任何实证数值或结论。待上述条件明确后，可在本框架下直接开展计算，形成最终分析结果。所有方法选择均给出候选方案与适用条件，拟议主方案仅为建议，可根据实际数据与口径调整。

案例Q05-direct
原始要求：
为研究生撰写中文研究设计的方法部分，研究Plan问答对提示词质量的影响。当前只是拟定研究，尚未实施。设计必须保留：4类任务，每类5个，共20个任务；每模式每任务独立重复3次；两名独立评审盲评。比较直接增强和Plan，并另设两组获得相同确认信息的公平对照。说明随机化、盲法、准确性、完整性、清晰度与可执行性评价以及分歧处理。具体统计检验依据未来数据分布和配对结构选择，候选方法可以说明适用条件，不预设必然显著。正文800至1200个汉字，附表另计。不提供代码，不得把拟定样本写成已纳入样本，不得编造效应量、P值、参考文献或显著性结论，不得使用假设数据。
实际确认信息：
[{"questionId":"plan_rounds","question":"Plan组的问答交互如何终止？","answer":"这是尚未实施的研究。请根据原始研究目标提出可开展的推荐设计并说明适用条件；我没有额外真实数据，不确认任何既有参数。"},{"questionId":"task_source","question":"20个任务从哪里来？","answer":"这是尚未实施的研究。请根据原始研究目标提出可开展的推荐设计并说明适用条件；我没有额外真实数据，不确认任何既有参数。"},{"questionId":"output_scope","question":"方法部分是否需要包含候选统计检验的适用条件说明？","answer":"这是尚未实施的研究。请根据原始研究目标提出可开展的推荐设计并说明适用条件；我没有额外真实数据，不确认任何既有参数。"}]
作品A：
## 研究设计（拟议）

本部分为拟议研究设计，尚未实施，不预设任何样本、效应量或显著性结论。研究拟考察Plan问答对提示词质量的影响。拟设4类任务：需求澄清类、约束满足类、步骤规划类与结果验证类，每类5个，共20个任务。任务来源拟从公开、可复现的任务库或研究者自建任务集中筛选，构造时统一记录任务目标、输入材料、约束条件与预期输出形式；具体来源与筛选标准须在实施前备案。每模式每任务独立重复3次，以观察同一任务下提示词质量的稳定性。评价对象为提示词本身，评审所见材料为经盲化处理的提示词及其对应任务；下游作品仅作验证依据，不替代对提示词质量的评价。

拟比较四组：直接增强组、Plan组、公平对照A组与公平对照B组。直接增强组不主动发起问答，但正常使用已提供的需求与确认信息；Plan组由增强器提出关键问题、用户回答，再将有效回答融入优化提示词，问答交互不得改为模型自行生成执行计划。公平对照两组获得与Plan组逐字相同的实际确认信息：先取得并封存同一案例的实际确认信息，再向两组提供相同信息；保留各组指定增强流程，不改写任一回答、不另换任务、不单组增加修订轮次。Plan组问答终止规则拟定为：关键问题覆盖任务目标、约束、输出形式与验证标准，或连续两轮无新增有效信息时终止；具体阈值须预注册。

评价指标为提示词质量的准确性、完整性、清晰度与可执行性。准确性指提示词与任务需求及确认信息一致；完整性指覆盖必要约束与输出要求；清晰度指表述无歧义、结构可读；可执行性指下游模型可据此稳定执行。两名独立评审盲评，评审不知分组与重复次数。评分拟采用有序等级，分歧处理为：先独立评分，差异超过预设等级时讨论；仍不一致则引入第三名评审仲裁。随机化拟用于任务呈现顺序、重复顺序与评审分配；盲法贯穿材料制备、评分与初步分析。缺失数据、偏倚与不确定性须记录；若出现缺失，拟报告缺失机制并做敏感性分析。

统计检验依据未来数据分布与配对结构选择，不预先选定。候选方法包括：若每任务每模式重复3次且评分近似连续、残差近似正态，可用线性混合模型，任务与重复为随机效应，组别、任务类型及交互为固定效应；若评分为有序等级，可用累积链接混合模型；若仅比较两相关组且差值近似对称，可用Wilcoxon符号秩检验；若比较四组独立或部分配对结构，可用Kruskal-Wallis检验后作事先规定的成对比较，或采用置换检验。所有检验须说明适用条件，并报告效应量与置信区间；不预设必然显著。分析口径以提示词评分为主，下游作品仅作辅助验证。
作品B：
## 方法

### 研究设计与总体框架

本研究拟采用被试内、多任务、多模式的实验设计，考察Plan问答对提示词质量的影响。研究尚未实施，以下为拟开展的设计方案。核心自变量为提示词生成模式，拟比较直接增强与Plan两种模式，并设置两组公平对照以控制确认信息差异。因变量为提示词质量，拟从准确性、完整性、清晰度与可执行性四个维度评价。研究保留4类任务，每类5个，共20个任务；每模式每任务独立重复3次；由两名独立评审进行盲评。

### 任务与材料

拟选取4类任务，每类5个，共20个任务。任务类型应覆盖不同认知需求与输出形式，例如信息提取、方案生成、约束满足与多步推理等。任务材料需在实验前统一编写并审核，确保表述一致、无歧义，且不包含可能泄露模式信息的线索。每类任务内的5个任务在难度与结构上尽量匹配，以减少任务间差异对结果的干扰。任务顺序在每名评审内随机化，以降低顺序效应与疲劳效应。

### 实验条件与对照设置

拟设置4组条件：直接增强组、Plan组、公平对照1组与公平对照2组。直接增强组采用直接增强方式生成提示词；Plan组采用Plan问答交互方式生成提示词。两组公平对照获得与Plan组相同的确认信息，但不进行Plan问答交互，以分离“确认信息”与“Plan交互过程”的效应。公平对照1组与公平对照2组在确认信息的呈现方式或交互形式上可有所区别，但确认信息内容保持一致。通过该设计，可比较直接增强与Plan的差异，并评估确认信息本身的作用。所有条件均在相同任务集上运行，每模式每任务独立重复3次，以估计随机波动。

### 随机化与盲法

任务呈现顺序、条件顺序及重复次序均拟随机化。每名评审独立评审所有提示词，评审顺序随机化。两名评审均不知晓提示词所属条件与重复次数，仅依据统一评价标准评分。为保障盲法，提示词中的模式标识、交互痕迹与元信息需在评审前移除或统一格式化。若评审过程中出现条件泄露，需记录并评估其影响。

### 评价维度与操作定义

提示词质量拟从四个维度评价：准确性、完整性、清晰度与可执行性。准确性指提示词是否准确反映任务目标与约束；完整性指是否涵盖任务所需的关键信息与步骤；清晰度指语言是否明确、无歧义；可执行性指提示词是否可直接用于生成或执行任务。每个维度拟采用有序评分量表，并附行为锚点说明。两名评审独立评分，评分前进行校准训练，但不接触实验条件信息。

### 数据收集与分歧处理

每名评审对每个提示词的四个维度分别评分。两名评审的评分将用于计算一致性。若评分分歧超过预设阈值，拟由第三名评审仲裁或通过共识讨论解决。分歧处理规则需在实验前确定并记录。所有评分数据在分析前进行匿名化与条件编码，确保分析者不知晓条件归属。

### 统计分析计划

统计分析将依据未来数据的分布特征与配对结构选择。由于每模式每任务重复3次，且同一任务在不同条件下重复测量，数据具有配对与嵌套结构。若数据近似正态且满足球形假设，可采用重复测量方差分析或线性混合模型；若数据为有序分类或偏离正态，可采用非参数检验或广义线性混合模型。候选方法包括：配对t检验或Wilcoxon符号秩检验用于两两比较；Friedman检验或Skillings-Mack检验用于多条件比较；线性混合模型用于处理任务与评审的随机效应。模型选择需考虑交互效应、随机截距与随机斜率。若存在缺失数据，拟采用多重插补或全信息最大似然估计，并报告缺失机制。所有检验将报告效应量及其置信区间，不预设必然显著。分析前将进行假设检验与敏感性分析，确保结果稳健。

### 伦理与质量控制

研究拟遵循伦理规范，任务材料不涉及敏感信息。实验前进行预注册，明确假设、设计与分析计划。数据收集过程中定期核查评分质量与盲法执行情况。最终报告将完整呈现任务、条件、评分标准与分析代码逻辑，但不提供代码本身。所有结论均基于未来实际数据，不编造效应量、P值或显著性结论。

### 附表

附表1：4类任务与20个任务示例（略）。附表2：评价维度与行为锚点（略）。附表3：随机化与盲法流程（略）。附表4：候选统计检验及适用条件（略）。

案例Q05-plan
原始要求：
为研究生撰写中文研究设计的方法部分，研究Plan问答对提示词质量的影响。当前只是拟定研究，尚未实施。设计必须保留：4类任务，每类5个，共20个任务；每模式每任务独立重复3次；两名独立评审盲评。比较直接增强和Plan，并另设两组获得相同确认信息的公平对照。说明随机化、盲法、准确性、完整性、清晰度与可执行性评价以及分歧处理。具体统计检验依据未来数据分布和配对结构选择，候选方法可以说明适用条件，不预设必然显著。正文800至1200个汉字，附表另计。不提供代码，不得把拟定样本写成已纳入样本，不得编造效应量、P值、参考文献或显著性结论，不得使用假设数据。
实际确认信息：
[{"questionId":"plan_rounds","question":"Plan组的问答交互如何终止？","answer":"这是尚未实施的研究。请根据原始研究目标提出可开展的推荐设计并说明适用条件；我没有额外真实数据，不确认任何既有参数。"},{"questionId":"task_source","question":"20个任务从哪里来？","answer":"这是尚未实施的研究。请根据原始研究目标提出可开展的推荐设计并说明适用条件；我没有额外真实数据，不确认任何既有参数。"},{"questionId":"output_scope","question":"方法部分是否需要包含候选统计检验的适用条件说明？","answer":"这是尚未实施的研究。请根据原始研究目标提出可开展的推荐设计并说明适用条件；我没有额外真实数据，不确认任何既有参数。"}]
作品A：
## 方法

### 研究设计与总体框架

本研究拟采用被试内、多任务、多模式的实验设计，考察Plan问答对提示词质量的影响。研究尚未实施，以下为拟开展的设计方案。核心自变量为提示词生成模式，拟比较直接增强与Plan两种模式，并设置两组公平对照以控制确认信息差异。因变量为提示词质量，拟从准确性、完整性、清晰度与可执行性四个维度评价。研究保留4类任务，每类5个，共20个任务；每模式每任务独立重复3次；由两名独立评审进行盲评。

### 任务与材料

拟选取4类任务，每类5个，共20个任务。任务类型应覆盖不同认知需求与输出形式，例如信息提取、方案生成、约束满足与多步推理等。任务材料需在实验前统一编写并审核，确保表述一致、无歧义，且不包含可能泄露模式信息的线索。每类任务内的5个任务在难度与结构上尽量匹配，以减少任务间差异对结果的干扰。任务顺序在每名评审内随机化，以降低顺序效应与疲劳效应。

### 实验条件与对照设置

拟设置4组条件：直接增强组、Plan组、公平对照1组与公平对照2组。直接增强组采用直接增强方式生成提示词；Plan组采用Plan问答交互方式生成提示词。两组公平对照获得与Plan组相同的确认信息，但不进行Plan问答交互，以分离“确认信息”与“Plan交互过程”的效应。公平对照1组与公平对照2组在确认信息的呈现方式或交互形式上可有所区别，但确认信息内容保持一致。通过该设计，可比较直接增强与Plan的差异，并评估确认信息本身的作用。所有条件均在相同任务集上运行，每模式每任务独立重复3次，以估计随机波动。

### 随机化与盲法

任务呈现顺序、条件顺序及重复次序均拟随机化。每名评审独立评审所有提示词，评审顺序随机化。两名评审均不知晓提示词所属条件与重复次数，仅依据统一评价标准评分。为保障盲法，提示词中的模式标识、交互痕迹与元信息需在评审前移除或统一格式化。若评审过程中出现条件泄露，需记录并评估其影响。

### 评价维度与操作定义

提示词质量拟从四个维度评价：准确性、完整性、清晰度与可执行性。准确性指提示词是否准确反映任务目标与约束；完整性指是否涵盖任务所需的关键信息与步骤；清晰度指语言是否明确、无歧义；可执行性指提示词是否可直接用于生成或执行任务。每个维度拟采用有序评分量表，并附行为锚点说明。两名评审独立评分，评分前进行校准训练，但不接触实验条件信息。

### 数据收集与分歧处理

每名评审对每个提示词的四个维度分别评分。两名评审的评分将用于计算一致性。若评分分歧超过预设阈值，拟由第三名评审仲裁或通过共识讨论解决。分歧处理规则需在实验前确定并记录。所有评分数据在分析前进行匿名化与条件编码，确保分析者不知晓条件归属。

### 统计分析计划

统计分析将依据未来数据的分布特征与配对结构选择。由于每模式每任务重复3次，且同一任务在不同条件下重复测量，数据具有配对与嵌套结构。若数据近似正态且满足球形假设，可采用重复测量方差分析或线性混合模型；若数据为有序分类或偏离正态，可采用非参数检验或广义线性混合模型。候选方法包括：配对t检验或Wilcoxon符号秩检验用于两两比较；Friedman检验或Skillings-Mack检验用于多条件比较；线性混合模型用于处理任务与评审的随机效应。模型选择需考虑交互效应、随机截距与随机斜率。若存在缺失数据，拟采用多重插补或全信息最大似然估计，并报告缺失机制。所有检验将报告效应量及其置信区间，不预设必然显著。分析前将进行假设检验与敏感性分析，确保结果稳健。

### 伦理与质量控制

研究拟遵循伦理规范，任务材料不涉及敏感信息。实验前进行预注册，明确假设、设计与分析计划。数据收集过程中定期核查评分质量与盲法执行情况。最终报告将完整呈现任务、条件、评分标准与分析代码逻辑，但不提供代码本身。所有结论均基于未来实际数据，不编造效应量、P值或显著性结论。

### 附表

附表1：4类任务与20个任务示例（略）。附表2：评价维度与行为锚点（略）。附表3：随机化与盲法流程（略）。附表4：候选统计检验及适用条件（略）。
作品B：
## 研究方法

### 一、研究对象与任务

本研究拟考察“Plan问答对提示词质量的影响”。研究对象为同一大语言模型在受控条件下生成的提示词，而非模型最终回答或下游作品本身。任务拟设4类，每类5个，共20个。任务来源尚未确认，拟议主方案为：从公开、可合法使用的任务库或既有研究中筛选，按“信息检索与综合、文本生成与改写、数据分析与推理、方案设计与决策”四类各选5个；入选标准为任务目标明确、输入材料可公开、完成标准可由人工评审判断、难度大致分层且不依赖特定机构数据。若无法获得合适公开任务，则退而采用研究者自编任务，但须经两名未参与主评审的专家独立审查其代表性与可理解性，并记录修订过程。任务来源、筛选标准和最终清单应在实施前固定并留档。

### 二、实验条件与分组

拟设四组。A组为直接增强：用户直接提交原始需求及已提供的确认信息，不进行增强器发起的多轮问答。B组为Plan：增强器先提出关键问题，用户回答后，将有效回答融入优化提示词；Plan组不得改为模型自行生成执行计划再作答。C组与D组为公平对照，均接收同一原始任务和同一份实际确认信息，并均可使用该信息；两组分别保留各自指定的增强流程，不改写回答、不更换任务，也不只增加某一组的修订轮次。每模式每任务独立重复3次，共形成4×20×3=240个提示词样本。重复指独立会话或独立运行，避免同一会话内记忆污染。所有组使用同一模型版本、解码参数、系统提示和时间窗口；若模型或参数中途变更，应记录并作为敏感性分析因素。

### 三、随机化与盲法

任务呈现顺序、组别运行顺序及重复次序拟采用区组随机化，以任务为区组，确保每任务四组各3次在时间上尽量均衡，减少模型服务波动。两名独立评审盲评：评审不知晓组别、研究假设及另一评审评分；提示词中的组别标识、增强器痕迹和可识别信息应统一脱敏。若因交互记录格式难以完全遮蔽，则至少保证评审仅见最终提示词和原始任务，不见对话过程。评审无法完全盲化时，须在局限中说明。

### 四、评价指标与评分流程

评价对象为提示词。拟从四个维度评分：准确性，指提示词是否忠实反映任务目标与约束，不含事实性误导；完整性，指是否覆盖任务所需关键信息、步骤与边界；清晰度，指结构、指代和表述是否易于理解；可执行性，指据此提示词能否直接开展下游工作。每维度拟采用5点量表并附文字锚点。下游作品可按原任务作为验证依据，但不替代提示词评分。评分前先进行校准训练，再独立评分；每样本由两名评审盲评。缺失数据、无法判断项和模型异常输出应记录原因，不纳入对应维度均分，并报告缺失比例。

### 五、分歧处理

两名评审各维度分差不超过1分时取均值；超过1分时先讨论，仍不一致则由第三名资深评审仲裁。仲裁仅针对分歧维度，不重评全样本。记录初始分、讨论后分和仲裁分，以评估评分可靠性。拟计算组内相关系数或Krippendorff's α，具体阈值依数据分布和量表使用情况确定。

### 六、统计检验候选及适用条件

分析单位为任务内重复样本，需考虑任务与重复的嵌套或配对结构。候选方法包括：若各维度得分近似连续且残差满足正态与方差齐性，可用线性混合模型，将组别设为固定效应，任务与重复设为随机效应；若分布偏态或为有序分类，可用累积链接混合模型或广义估计方程；若仅比较少数预设对比且配对结构明确，可用配对非参数检验并作多重比较校正。所有检验均须先检查分布、离群值、缺失机制和模型假设；不预设任何组必然显著，也不编造效应量或P值。公平对照的信息流应通过同一原始任务、同一确认信息、相同可用权限和相同评价流程保证，并在附录表中列明各组实际获得的信息与流程差异。
