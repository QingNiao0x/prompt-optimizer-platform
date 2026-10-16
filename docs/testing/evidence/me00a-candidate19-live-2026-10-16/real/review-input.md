你是独立AI辅助评审，不是人工领域专家。比较同一要求和信息条件下的匿名作品。准确性、完整性、清晰度、可执行性分别按1—5评分：1无法使用，2需大改，3基本可用但有缺口，4满足要求仅小改，5充分且精确。不要奖励长度或标题。原交付、事实、明确规则、篇幅、代码行为和格式严重失败独立记录，不能被平均分抵消。资料不足不允许编造，也不应把整篇退回问卷或空表。仅按用户原要求及实际信息评价，不新增代码、示例、量表或资料要求；作品自报合规不是证据。给每对比较A胜/B胜/平/双方失败，引用所属作品的完整原句，说明不确定性和无法判定之处。仅输出JSON：cases数组，每项含id、scoresA、scoresB、verdict、severeFailures、evidence（含work与quote）及uncertainties。不要补写作品或答案。

案例requests-title
原始要求：
使用requests库爬取网页标题。
实际确认信息：
无
作品A：
# 网页标题爬取功能实现方案

## 一、实现方案说明

### 模块划分

单文件模块 `web_title.py`，包含以下部分：

| 部分 | 职责 |
|------|------|
| `sanitize_url` | URL 脱敏，用于日志/错误信息输出 |
| `_extract_title` | 从 HTML 文本中提取 `<title>` 内容（标准库 `html.parser`） |
| `fetch_title` | 单个 URL 抓取与解析，返回结构化结果 |
| `fetch_titles` | 批量处理，返回 `{url: result}` 映射 |
| `main` | 命令行入口 |

### 函数签名与返回结构

```python
def fetch_title(url: str, timeout: float = 10.0) -> dict
def fetch_titles(urls: Iterable[str], timeout: float = 10.0) -> dict[str, dict]
```

`fetch_title` 返回统一结构（**永不抛异常**，异常转为 `status`）：

```python
{
    "url": str,          # 原始 URL（保留凭据，供调用方定位）
    "status": str,       # 见下表
    "title": str | None, # 成功时为标题文本，否则 None
    "error": str | None, # 失败时为脱敏后的错误描述，否则 None
}
```

### status 取值与含义（接口契约）

| status | 含义 | title | error |
|--------|------|-------|-------|
| `ok` | 请求成功、200、HTML、有非空 title | 标题 | None |
| `empty_title` | 200 且 HTML，但 `<title>` 为空或仅空白 | `""` | None |
| `no_title` | 200 且 HTML，但无 `<title>` 标签 | None | None |
| `not_html` | 200，但 Content-Type 非 HTML | None | 说明 |
| `http_error` | 响应状态码非 200 | None | `HTTP <code>` |
| `timeout` | 请求超时 | None | 说明 |
| `request_error` | 连接/SSL/其他 requests 异常 | None | 说明 |
| `invalid_url` | 入参非法（空、非字符串、无 scheme/host） | None | 说明 |

### 命令行用法

```bash
python web_title.py https://example.com https://example.org
```

输出每行：`<脱敏URL>\t<status>\t<title 或 error>`。

### 解析方式选择

- **默认**：标准库 `html.parser`，零额外依赖，满足常规页面。
- **可选**：若需处理畸形 HTML 或复杂结构，可替换为 `BeautifulSoup(html, "lxml")`，会新增 `beautifulsoup4`、`lxml` 依赖。本实现默认走标准库路径，并在 `_extract_title` 中集中封装，便于替换。

### 安全与脱敏

- `sanitize_url` 使用 `urllib.parse` 剥离 `user:pass@` 中的凭据，仅保留 `scheme://host[:port]/path`。
- 日志与错误信息只输出脱敏 URL；`result["url"]` 保留原始 URL 供调用方定位，但**不写入日志**。
- 不读取 `.env`、`*.pem`、`*.key` 等受保护路径。

---

## 二、完整可运行代码

```python
# web_title.py
"""使用 requests 抓取网页标题的最小实现。

接口契约见模块 docstring 与 README 说明。
"""
from __future__ import annotations

import sys
from html.parser import HTMLParser
from typing import Iterable
from urllib.parse import urlsplit, urlunsplit

import requests

DEFAULT_TIMEOUT = 10.0
USER_AGENT = "web-title-fetcher/1.0"


# ---------- URL 脱敏 ----------

def sanitize_url(url: str) -> str:
    """移除 URL 中的 userinfo（凭据），仅保留 scheme://host[:port]/path?query。"""
    try:
        parts = urlsplit(url)
    except ValueError:
        return "<invalid-url>"
    if not parts.scheme or not parts.netloc:
        return "<invalid-url>"
    host = parts.hostname or ""
    if parts.port:
        host = f"{host}:{parts.port}"
    return urlunsplit((parts.scheme, host, parts.path, parts.query, ""))


# ---------- HTML 标题提取 ----------

class _TitleParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self._in_title = False
        self._chunks: list[str] = []
        self.found = False

    def handle_starttag(self, tag, attrs):
        if tag.lower() == "title":
            self._in_title = True
            self.found = True

    def handle_endtag(self, tag):
        if tag.lower() == "title":
            self._in_title = False

    def handle_data(self, data):
        if self._in_title:
            self._chunks.append(data)

    @property
    def title(self) -> str:
        return "".join(self._chunks).strip()


def _extract_title(html_text: str) -> tuple[bool, str]:
    """返回 (是否找到 <title> 标签, 标题文本)。"""
    parser = _TitleParser()
    try:
        parser.feed(html_text)
        parser.close()
    except Exception:
        # 解析器对畸形输入可能抛错，视为未找到
        return False, ""
    return parser.found, parser.title


# ---------- 单 URL 抓取 ----------

def _is_valid_url(url) -> bool:
    if not isinstance(url, str) or not url.strip():
        return False
    try:
        parts = urlsplit(url)
    except ValueError:
        return False
    return parts.scheme in ("http", "https") and bool(parts.netloc)


def fetch_title(url: str, timeout: float = DEFAULT_TIMEOUT) -> dict:
    """抓取单个 URL 的标题，永不抛异常。

    返回 dict：{"url", "status", "title", "error"}
    """
    result = {"url": url, "status": None, "title": None, "error": None}

    if not _is_valid_url(url):
        result["status"] = "invalid_url"
        result["error"] = "URL 为空、类型错误或缺少 http(s) scheme/host"
        return result

    safe = sanitize_url(url)

    try:
        resp = requests.get(
            url,
            timeout=timeout,
            headers={"User-Agent": USER_AGENT},
            allow_redirects=True,
        )
    except requests.exceptions.Timeout:
        result["status"] = "timeout"
        result["error"] = f"请求超时（{timeout}s）: {safe}"
        return result
    except requests.exceptions.RequestException as exc:
        result["status"] = "request_error"
        result["error"] = f"请求失败: {safe} ({type(exc).__name__})"
        return result

    if resp.status_code != 200:
        result["status"] = "http_error"
        result["error"] = f"HTTP {resp.status_code}: {safe}"
        return result

    ctype = (resp.headers.get("Content-Type") or "").lower()
    if ctype and "html" not in ctype:
        result["status"] = "not_html"
        result["error"] = f"非 HTML 内容（Content-Type={ctype}）: {safe}"
        return result

    # 解码：优先响应声明编码，其次 apparent_encoding，最后 utf-8
    if resp.encoding:
        text = resp.text
    elif resp.apparent_encoding:
        text = resp.content.decode(resp.apparent_encoding, errors="replace")
    else:
        text = resp.content.decode("utf-8", errors="replace")

    found, title = _extract_title(text)
    if not found:
        result["status"] = "no_title"
        return result
    if not title:
        result["status"] = "empty_title"
        result["title"] = ""
        return result

    result["status"] = "ok"
    result["title"] = title
    return result


# ---------- 批量抓取 ----------

def fetch_titles(urls: Iterable[str], timeout: float = DEFAULT_TIMEOUT) -> dict:
    """对每个 URL 独立处理，返回 {原始URL: result}。"""
    results: dict = {}
    for url in urls:
        results[url] = fetch_title(url, timeout=timeout)
    return results


# ---------- 命令行入口 ----------

def main(argv: list[str] | None = None) -> int:
    args = list(sys.argv[1:] if argv is None else argv)
    if not args:
        print("用法: python web_title.py <URL> [URL ...]", file=sys.stderr)
        return 2

    results = fetch_titles(args)
    for url, r in results.items():
        safe = sanitize_url(url) if isinstance(url, str) else "<invalid-url>"
        if r["status"] == "ok":
            print(f"{safe}\t{r['status']}\t{r['title']}")
        elif r["status"] == "empty_title":
            print(f"{safe}\t{r['status']}\t<empty>")
        else:
            print(f"{safe}\t{r['status']}\t{r['error']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
```

---

## 三、可执行测试代码

```python
# test_web_title.py
"""核心行为测试：不依赖真实外网，使用打桩响应对象与本地 HTML。"""
import unittest
from unittest import mock

import requests

import web_title
from web_title import fetch_title, fetch_titles, sanitize_url, _extract_title


class FakeResponse:
    def __init__(self, status_code=200, text="", headers=None,
                 encoding="utf-8", apparent_encoding=None, content=None):
        self.status_code = status_code
        self.text = text
        self.headers = headers or {"Content-Type": "text/html; charset=utf-8"}
        self.encoding = encoding
        self.apparent_encoding = apparent_encoding
        self.content = content if content is not None else text.encode("utf-8")


class TestSanitizeUrl(unittest.TestCase):
    def test_strips_credentials(self):
        self.assertEqual(
            sanitize_url("https://user:secret@example.com/path?q=1"),
            "https://example.com/path?q=1",
        )

    def test_invalid(self):
        self.assertEqual(sanitize_url("not a url"), "<invalid-url>")
        self.assertEqual(sanitize_url(""), "<invalid-url>")


class TestExtractTitle(unittest.TestCase):
    def test_normal(self):
        found, title = _extract_title("<html><head><title>  Hello  </title></head></html>")
        self.assertTrue(found)
        self.assertEqual(title, "Hello")

    def test_no_title(self):
        found, title = _extract_title("<html><body>hi</body></html>")
        self.assertFalse(found)
        self.assertEqual(title, "")

    def test_empty_title(self):
        found, title = _extract_title("<title>   </title>")
        self.assertTrue(found)
        self.assertEqual(title, "")

    def test_entities(self):
        found, title = _extract_title("<title>A &amp; B</title>")
        self.assertTrue(found)
        self.assertEqual(title, "A & B")


class TestFetchTitle(unittest.TestCase):
    def test_invalid_url_empty(self):
        r = fetch_title("")
        self.assertEqual(r["status"], "invalid_url")
        self.assertIsNone(r["title"])

    def test_invalid_url_type(self):
        r = fetch_title(None)  # type: ignore[arg-type]
        self.assertEqual(r["status"], "invalid_url")

    def test_invalid_url_no_scheme(self):
        r = fetch_title("example.com")
        self.assertEqual(r["status"], "invalid_url")

    @mock.patch("web_title.requests.get")
    def test_ok(self, mock_get):
        mock_get.return_value = FakeResponse(text="<title>Example</title>")
        r = fetch_title("https://example.com")
        self.assertEqual(r["status"], "ok")
        self.assertEqual(r["title"], "Example")

    @mock.patch("web_title.requests.get")
    def test_empty_title(self, mock_get):
        mock_get.return_value = FakeResponse(text="<title>  </title>")
        r = fetch_title("https://example.com")
        self.assertEqual(r["status"], "empty_title")
        self.assertEqual(r["title"], "")

    @mock.patch("web_title.requests.get")
    def test_no_title(self, mock_get):
        mock_get.return_value = FakeResponse(text="<html><body>x</body></html>")
        r = fetch_title("https://example.com")
        self.assertEqual(r["status"], "no_title")
        self.assertIsNone(r["title"])

    @mock.patch("web_title.requests.get")
    def test_not_html(self, mock_get):
        mock_get.return_value = FakeResponse(
            text="{}", headers={"Content-Type": "application/json"}
        )
        r = fetch_title("https://example.com")
        self.assertEqual(r["status"], "not_html")

    @mock.patch("web_title.requests.get")
    def test_http_error(self, mock_get):
        mock_get.return_value = FakeResponse(status_code=404, text="")
        r = fetch_title("https://example.com")
        self.assertEqual(r["status"], "http_error")
        self.assertIn("404", r["error"])

    @mock.patch("web_title.requests.get")
    def test_timeout(self, mock_get):
        mock_get.side_effect = requests.exceptions.Timeout()
        r = fetch_title("https://example.com")
        self.assertEqual(r["status"], "timeout")

    @mock.patch("web_title.requests.get")
    def test_request_error(self, mock_get):
        mock_get.side_effect = requests.exceptions.ConnectionError("boom")
        r = fetch_title("https://example.com")
        self.assertEqual(r["status"], "request_error")

    @mock.patch("web_title.requests.get")
    def test_error_does_not_leak_credentials(self, mock_get):
        mock_get.side_effect = requests.exceptions.ConnectionError("boom")
        r = fetch_title("https://user:secret@example.com/x")
        self.assertNotIn("secret", r["error"])
        self.assertNotIn("user", r["error"])
        # 原始 URL 仍保留，供调用方定位
        self.assertIn("secret", r["url"])


class TestFetchTitles(unittest.TestCase):
    @mock.patch("web_title.requests.get")
    def test_batch_isolates_failures(self, mock_get):
        def side_effect(url, **kwargs):
            if "bad" in url:
                raise requests.exceptions.ConnectionError("boom")
            return FakeResponse(text=f"<title>{url}</title>")

        mock_get.side_effect = side_effect
        urls = ["https://good.example", "https://bad.example", "https://ok.example"]
        results = fetch_titles(urls)
        self.assertEqual(results["https://good.example"]["status"], "ok")
        self.assertEqual(results["https://bad.example"]["status"], "request_error")
        self.assertEqual(results["https://ok.example"]["status"], "ok")


if __name__ == "__main__":
    unittest.main()
```

运行：

```bash
python -m unittest test_web_title.py -v
```

---

## 四、接口契约汇总

### `fetch_title(url, timeout=10.0) -> dict`

- **入参**
  - `url`：`str`。必须为 `http`/`https` 且含 host；否则返回 `invalid_url`。
  - `timeout`：`float`，秒。传给 `requests.get`。
- **返回**：`{"url", "status", "title", "error"}`，字段含义见上表。
- **异常**：不抛出。所有可预期异常转为 `status`。
- **安全**：`error` 中 URL 已脱敏；`url` 字段保留原始值供调用方定位，调用方不应直接写入日志。

### `fetch_titles(urls, timeout=10.0) -> dict`

- **入参**：`urls` 为可迭代的 URL 字符串。
- **返回**：`{原始URL: fetch_title 结果}`。单个 URL 失败不影响其他。

### `sanitize_url(url) -> str`

- 返回去除 userinfo 的 URL；非法输入返回 `"<invalid-url>"`。

### 命令行

- `python web_title.py <URL> [URL ...]`
- 无参数返回退出码 2 并打印用法。
- 每行输出 `脱敏URL\tstatus\t标题或错误`。

---

## 五、关键依据、适用范围与限制

- **已知事实**：`requests` 的 `resp.encoding` 可能为 `None`（如无 charset 声明），此时回退 `apparent_encoding`，再回退 `utf-8`。
- **必要假设**：默认 `timeout=10s`、`User-Agent` 为自定义标识；如需调整由调用方传入。
- **限制**：
  - 标准库 `html.parser` 对严重畸形 HTML 可能漏解析 `<title>`；如需更强健解析，替换 `_extract_title` 为 BeautifulSoup+lxml（新增依赖）。
  - 未做 robots.txt 检查、限速、重试；生产使用需补充。
  - 未处理 `Content-Type` 缺失的情况（视为 HTML 尝试解析）。
- **未确认项**：原始需求未指定超时值、User-Agent、是否跟随重定向；本实现采用上述默认值，可按需调整。
作品B：
# 使用 requests 库爬取网页标题

下面是一个完整的示例，演示如何使用 `requests` 库配合 `BeautifulSoup` 爬取网页标题。

## 基础版本

```python
import requests
from bs4 import BeautifulSoup

def get_page_title(url):
    """爬取指定网页的标题"""
    try:
        # 发送请求，设置超时和请求头
        headers = {
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) '
                          'AppleWebKit/537.36 (KHTML, like Gecko) '
                          'Chrome/120.0.0.0 Safari/537.36'
        }
        response = requests.get(url, headers=headers, timeout=10)
        response.raise_for_status()  # 检查 HTTP 错误
        
        # 自动处理编码（避免中文乱码）
        response.encoding = response.apparent_encoding
        
        # 解析 HTML
        soup = BeautifulSoup(response.text, 'html.parser')
        title = soup.title.string.strip() if soup.title else '无标题'
        return title
    
    except requests.exceptions.RequestException as e:
        return f'请求失败: {e}'


if __name__ == '__main__':
    urls = [
        'https://www.baidu.com',
        'https://www.python.org',
        'https://www.github.com',
    ]
    
    for url in urls:
        title = get_page_title(url)
        print(f'{url} -> {title}')
```

## 只用 requests + 正则（不依赖 BeautifulSoup）

如果你不想安装 `bs4`，可以用正则表达式提取标题：

```python
import re
import requests

def get_title_by_regex(url):
    headers = {'User-Agent': 'Mozilla/5.0'}
    try:
        resp = requests.get(url, headers=headers, timeout=10)
        resp.encoding = resp.apparent_encoding
        # 匹配 <title>...</title>，忽略大小写和换行
        match = re.search(r'<title[^>]*>(.*?)</title>', resp.text, re.S | re.I)
        return match.group(1).strip() if match else '无标题'
    except requests.RequestException as e:
        return f'请求失败: {e}'


print(get_title_by_regex('https://www.python.org'))
```

## 关键点说明

| 要点 | 说明 |
|------|------|
| **User-Agent** | 很多网站会拒绝没有 UA 的请求，务必加上 |
| **timeout** | 防止请求卡死，建议设置 5~10 秒 |
| **raise_for_status()** | 遇到 4xx/5xx 状态码时抛出异常，便于排查 |
| **apparent_encoding** | 自动识别编码，避免中文乱码 |
| **异常处理** | 网络请求容易失败，一定要用 try/except 包裹 |

## 批量爬取（带并发）

如果 URL 很多，可以用线程池加速：

```python
import requests
from bs4 import BeautifulSoup
from concurrent.futures import ThreadPoolExecutor

HEADERS = {'User-Agent': 'Mozilla/5.0'}

def fetch_title(url):
    try:
        r = requests.get(url, headers=HEADERS, timeout=10)
        r.encoding = r.apparent_encoding
        soup = BeautifulSoup(r.text, 'html.parser')
        return url, soup.title.string.strip() if soup.title else '无标题'
    except Exception as e:
        return url, f'失败: {e}'

urls = ['https://www.baidu.com', 'https://www.python.org', 'https://www.github.com']

with ThreadPoolExecutor(max_workers=5) as pool:
    for url, title in pool.map(fetch_title, urls):
        print(f'{url} -> {title}')
```

## 安装依赖

```bash
pip install requests beautifulsoup4
```

## 注意事项

1. **遵守 robots.txt**：爬取前查看目标网站的爬虫协议
2. **控制频率**：不要高频请求，避免给服务器造成压力
3. **合法合规**：仅用于学习研究，不要爬取敏感或受版权保护的内容
4. **动态网页**：如果标题由 JavaScript 渲染（如 SPA 应用），`requests` 拿不到，需要用 `selenium` 或 `playwright`

需要我进一步演示某个具体网站，或者讲解如何处理动态渲染页面吗？

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

作为科研工作者，您提出的分析框架非常系统。下面我为您梳理一套完整的研究设计思路、方法学要点和写作框架，供您参考和落地实施。

---

## 一、研究总体设计

### 1.1 数据来源与变量定义
- **死亡数据**：来自地区死因监测系统/疾控中心，ICD-10编码 I00-I99（心脑血管疾病）
  - 亚类：缺血性心脏病（I20-I25）、脑血管病（I60-I69）、高血压性心脏病（I10-I15）、风湿性心脏病（I05-I09）、其他
- **人口数据**：分性别、年龄（5岁组）、年份、地区的常住人口数
- **标准人口**：采用2010年全国人口普查或WHO世界标准人口进行标化
- **地区划分**：城乡、经济带（东/中/西）、行政区域等
- **人群划分**：年龄组（0-14、15-44、45-59、60-74、75+）、城乡、职业/教育（若有）

### 1.2 核心指标
| 指标 | 公式/说明 |
|------|-----------|
| 粗死亡率（CDR） | 死亡数/平均人口×100000 |
| 标化死亡率（ASMR） | 直接法标化 |
| 年龄别死亡率（ASDR） | 分年龄组 |
| 潜在减寿年数（PYLL） | Σ(期望寿命−死亡年龄)×死亡数 |
| 减寿率（PYLL率） | PYLL/人口数×1000‰ |
| 平均减寿年数（AYLL） | PYLL/死亡数 |

---

## 二、时间序列长期趋势与季节性趋势

### 2.1 长期趋势分析
**方法选择：**
1. **Joinpoint回归**（推荐）：识别死亡率变化的转折点，计算APC（年度变化百分比）和AAPC
   - 软件：Joinpoint Regression Program（NCI）
   - 模型：对数线性模型 ln(y) = α + βx
2. **Joinpoint + 年龄-时期-队列（APC）模型**：分离年龄、时期、队列效应
3. **灰色预测GM(1,1) / ARIMA**：若需外推预测

**报告内容：**
- 2015-2025年ASMR总体趋势（上升/下降/平稳）
- 转折点年份及APC值（如"2015-2019年APC=-2.3%，2019-2025年APC=+1.8%"）
- 分性别、分亚类的趋势差异

### 2.2 季节性趋势分析
**方法选择：**
1. **圆形分布法**：计算发病/死亡的集中趋势（平均角$\bar{\alpha}$）和集中度（$r$值）
   - 检验：Rayleigh检验
2. **季节指数法**：月度/季度死亡数与全年均值之比
3. **时间序列分解**：STL分解（Seasonal-Trend decomposition using Loess）
   - 分解为趋势项、季节项、残差项
4. **广义相加模型（GAM）**：控制长期趋势后分析月份效应
5. **分布滞后非线性模型（DLNM）**：若结合气象数据，分析温度-死亡滞后效应

**报告内容：**
- 冬季高峰/夏季低谷特征（心脑血管疾病典型冬峰）
- 季节性强度（r值、季节指数峰值月份）
- 不同亚类、地区、性别的季节性差异

---

## 三、分组比较分析

### 3.1 分析维度
| 维度 | 分组 | 统计方法 |
|------|------|----------|
| 性别 | 男/女 | 率比（RR）、95%CI、χ²检验 |
| 地区 | 城乡/东中西 | 标化率比较、空间自相关（Moran's I） |
| 人群 | 年龄组 | 年龄别死亡率曲线、Cochran-Armitage趋势检验 |
| 亚类 | IHD/CVD/HD等 | 构成比、亚类间率比 |

### 3.2 统计方法
- **率比较**：Z检验、Poisson回归、负二项回归（过离散时）
- **趋势检验**：Cochran-Armitage、Mann-Kendall
- **空间分析**：Moran's I、Getis-Ord Gi*、空间扫描统计（SaTScan）
- **不平等分析**：集中指数（CI）、Slope Index of Inequality（SII）

### 3.3 报告建议
- 表格：分性别、地区、年龄组的ASMR及95%CI
- 图形：年龄别死亡率半对数线图、地区热力图、亚类构成堆积图

---

## 四、减寿分析（PYLL / YLL）

### 4.1 指标定义
- **YLL（Years of Life Lost，早死损失寿命年）**：
  $$YLL = \sum_{i} d_i \times (L - a_i)$$
  其中 $d_i$ 为某年龄组死亡数，$L$ 为期望寿命（如75岁或标准期望寿命），$a_i$ 为死亡年龄组中值
- **YLL率**：YLL/人口数×1000‰
- **AYLL**：YLL/死亡数

### 4.2 分析层次
1. **年度YLL和YLL率**：2015-2025年逐年计算，观察趋势
2. **分性别YLL和YLL率**：男女对比，计算率比
3. **分亚类YLL**：IHD、CVD等各自贡献
4. **分地区YLL**：城乡/区域差异
5. **年龄组分解**：各年龄组对总YLL的贡献率

### 4.3 期望寿命标准选择
- 推荐使用**WHO标准期望寿命表**或**2010年全国人口期望寿命**
- 敏感性分析：分别用75岁、80岁、当地期望寿命计算

### 4.4 软件实现
- **R**：`DALY`包、`epiR`包
- **Excel**：模板计算
- **SPSS**：语法编程

---

## 五、Arriaga分解方法

### 5.1 方法原理
Arriaga分解用于**分解期望寿命差异**，将两组（如城乡、男女、两个年份）之间的期望寿命差分解为：
1. **直接效应**：某年龄组死亡率变化对期望寿命的贡献
2. **间接效应**：存活人数变化导致的后续年龄组贡献
3. **交互效应**：上述两者的交互

### 5.2 分解公式（简版）
对于年龄组 $i$，两组间期望寿命差：
$$e_0^B - e_0^A = \sum_i \left[ \frac{l_i^A}{l_0^A} \cdot (q_i^A - q_i^B) \cdot \frac{e_{i+1}^A + e_i^A}{2} \right] + \text{间接项} + \text{交互项}$$

### 5.3 应用场景
1. **时间分解**：2015年 vs 2025年期望寿命差异，各年龄组/疾病的贡献
2. **性别分解**：男女期望寿命差异中，心脑血管疾病的贡献
3. **地区分解**：城乡期望寿命差异中，心脑血管疾病的贡献
4. **亚类分解**：IHD、CVD等对期望寿命变化的贡献

### 5.4 软件实现
- **R**：`DemoDecomp`包（Arriaga方法）、`ungroup`包
- **Excel**：Arriaga原始公式模板
- **Stata**：`arriaga`命令

### 5.5 报告要点
- 分解表：各年龄组/疾病对期望寿命差异的贡献（年数和百分比）
- 图形：贡献瀑布图、年龄组贡献堆积图
- 结论：如"城乡期望寿命差异中，心脑血管疾病贡献了X%，其中60-74岁组贡献最大"

---

## 六、论文写作框架建议

```
1. 引言
   1.1 心脑血管疾病全球及地区疾病负担
   1.2 研究地区背景与既往研究缺口
   1.3 研究目的

2. 资料与方法
   2.1 数据来源
   2.2 疾病分类与变量定义
   2.3 统计分析
       2.3.1 趋势分析（Joinpoint、STL）
       2.3.2 季节性分析（圆形分布、GAM）
       2.3.3 分组比较（Poisson回归等）
       2.3.4 减寿分析（YLL、YLL率）
       2.3.5 Arriaga分解
   2.4 质量控制
   2.5 伦理审查

3. 结果
   3.1 总体死亡趋势（2015-2025）
   3.2 季节性特征
   3.3 分性别、地区、人群、亚类比较
   3.4 YLL和YLL率分析
   3.5 Arriaga分解结果

4. 讨论
   4.1 主要发现
   4.2 与既往研究比较
   4.3 机制解释（人口老龄化、医疗可及性、环境因素）
   4.4 公共卫生意义
   4.5 局限性

5. 结论
6. 参考文献
```

---

## 七、关键注意事项

1. **数据质量**：死因漏报、编码错误、人口基数准确性——建议做漏报调整（如捕获-再捕获法）
2. **标化率**：必须报告95%CI，避免小样本地区率不稳定
3. **Joinpoint参数**：最大转折点数、最小间隔年数需合理设定
4. **季节性**：需排除长期趋势和随机波动影响，STL分解前先做趋势调整
5. **YLL期望寿命**：敏感性分析不同标准
6. **Arriaga**：注意年龄组划分一致性，末组开口问题
7. **多重比较**：分组较多时需校正（Bonferroni或FDR）
8. **伦理**：死因监测数据需伦理审批和隐私保护

---

## 八、推荐工具汇总

| 分析内容 | 推荐软件/包 |
|----------|-------------|
| Joinpoint趋势 | Joinpoint Regression Program |
| 季节性STL | R: `stats::stl`、`forecast` |
| 圆形分布 | R: `circular` |
| GAM/DLNM | R: `mgcv`、`dlnm` |
| 空间分析 | ArcGIS、GeoDa、R: `spdep` |
| YLL计算 | R: `DALY`、Excel模板 |
| Arriaga分解 | R: `DemoDecomp`、Stata |
| 可视化 | R: `ggplot2`、`forestplot` |

---

如果您需要，我可以进一步为您：
1. 提供**R语言代码模板**（Joinpoint、STL、YLL、Arriaga）
2. 设计**具体表格和图形模板**
3. 撰写**方法学部分的详细文字**
4. 针对某地区实际数据做**模拟分析示例**

请告诉我您目前的数据情况（年份跨度、地区范围、是否有气象数据等），我可以给出更针对性的建议。
作品B：
# 2015—2025年某地区心脑血管疾病死亡率特征分析

## 说明性前言

本分析拟围绕2015—2025年某地区心脑血管疾病死亡数据，依次开展时间序列分析、分组比较、减寿分析（YLL与YLL率）和Arriaga分解。截至本稿撰写时，以下关键参数尚未由用户确认，仅影响对应内容，其余部分先按可修订初稿完成：研究地区具体指哪一省、市或区域，是否涉及跨地区比较及其比较单元；死亡数据与人口数据的来源、年份范围、时间粒度（年度或月度）及率的分母口径；YLL分析采用的参考寿命表或标准寿命。上述缺口不在各节逐条重复标注，仅在对应位置说明其影响。本文不填入任何未经核实的数值、引用或结论；凡无法从现有资料推断者，均标注为待确认或无法从现有资料推断。

## 一、时间序列分析

### 1.1 分析目的与指标含义

本部分旨在描述2015—2025年某地区心脑血管疾病死亡率的长期趋势与季节性趋势。长期趋势反映死亡率在十年间的总体方向、变化幅度及统计显著性；季节性趋势反映死亡率在一年内不同月份或季度的周期性波动特征。若数据时间粒度为年度，则仅能分析长期趋势，无法识别季节性；若为月度或季度数据，则可同时分析长期趋势与季节性。时间粒度待确认。

死亡率指标拟采用粗死亡率与年龄标化死亡率并行报告。粗死亡率反映实际死亡负担，受人口年龄结构影响；年龄标化死亡率用于消除年龄结构差异，便于跨时间与跨人群比较。标化方法拟采用直接标准化，标准人口待确认。分母口径应与死亡数据来源一致，具体待确认。

### 1.2 长期趋势分析方法

拟议方案：采用Joinpoint回归模型分析长期趋势。该模型适用于死亡率随时间变化的趋势识别，可估计年度变化百分比（APC）和平均年度变化百分比（AAPC），并识别趋势转折点。适用条件包括：死亡率数据在时间上独立、服从泊松分布或正态分布（视模型设定）、样本量足够支撑分段拟合。若数据存在过度离散，可采用负二项模型或准泊松模型。Joinpoint模型对时间点数量和间隔有一定要求，通常建议至少10个时间点；本分析时间跨度为2015—2025年，若为年度数据则约11个点，满足基本要求。

备选方案：若Joinpoint模型不适用或数据不满足其假设，可采用线性回归或泊松回归分析长期趋势，以年份为自变量，死亡率为因变量，检验斜率是否显著不为零。也可采用Mann-Kendall趋势检验，该法为非参数方法，不要求数据服从特定分布，适用于单调趋势检验，但对非线性趋势识别能力有限。

趋势方向、变化幅度与统计检验结果的表述方式拟统一为：报告APC或AAPC及其95%置信区间，若置信区间不包含0，则判断趋势有统计学意义；同时报告趋势方向（上升、下降或平稳）及变化幅度（如“年均下降X%”）。若采用Joinpoint模型，还需报告转折点年份及各段APC。具体数值无法从现有资料推断，待数据确认后计算。

### 1.3 季节性趋势分析方法

拟议方案：若数据为月度或季度粒度，采用季节性分解方法分析季节性趋势。常用方法包括：季节性指数法、X-13ARIMA-SEATS或STL分解。季节性指数法计算各月（或季度）死亡率相对于全年平均水平的比值，适用于季节性模式稳定的数据；X-13ARIMA-SEATS适用于存在趋势、季节性和不规则成分的时间序列，可同时估计季节性成分并调整日历效应；STL分解基于局部加权回归，适用于非线性趋势和变化的季节性模式。

适用条件：季节性分析要求数据至少覆盖多个完整年度，通常建议至少3—5年；月度数据优于季度数据；若死亡率存在长期趋势，需先分离趋势再估计季节性，或采用能同时估计趋势与季节性的方法。心脑血管疾病死亡率通常存在冬季高、夏季低的季节性模式，但具体模式需由数据验证，不可预设。

若数据为年度粒度，则无法进行季节性分析，本部分仅报告长期趋势，并说明季节性分析因数据粒度限制无法开展。时间粒度待确认。

### 1.4 缺失数据与不确定性

若某些年份或月份数据缺失，需说明缺失模式（随机缺失或非随机缺失）及处理方法。若缺失比例较低且为随机缺失，可采用插补法；若缺失比例较高或为非随机缺失，需在结果中说明其对趋势估计的影响。具体缺失情况待数据确认。趋势分析的不确定性主要来源于模型选择、时间点数量、人口分母误差及死亡登记完整性，拟在结果中报告置信区间并讨论敏感性分析结果。

## 二、分组比较

### 2.1 分析目的与比较逻辑

本部分旨在按性别、地区、人群、亚类分别比较心脑血管疾病死亡率水平与变化趋势。比较逻辑为：先描述各分组死亡率水平（粗率与标化率），再比较各组间差异及随时间的变化，最后讨论差异的可能来源与可比性限制。所有比较需在分母口径一致、分类标准一致的前提下进行。

### 2.2 分组定义与分母口径

性别分组：拟按男性、女性二分。若数据包含其他性别类别，需说明其编码与纳入方式。分母分别为男性人口数与女性人口数，口径待确认。

地区分组：研究地区具体指哪一省、市或区域待确认。若涉及跨地区比较，需明确比较单元（如地级市、区县）及各地区分母口径。若仅分析单一地区，则地区分组不适用，或可按城乡、行政区划内部分区分析。地区编码需核对来源与版本，确保边界一致。

人群分组：拟按年龄组划分，如0—14岁、15—44岁、45—59岁、60—74岁、75岁及以上，或按更细年龄组划分。年龄组划分需与数据编码一致，并考虑心脑血管疾病死亡主要集中在老年人群的特点。分母为各年龄组人口数。若数据包含其他人群维度（如民族、职业、教育水平），需说明其定义与分母口径。

亚类分组：心脑血管疾病亚类拟按ICD编码划分，如缺血性心脏病、脑血管病、高血压性心脏病、心力衰竭等。ICD版本（ICD-10或ICD-11）及具体编码范围待确认。分母为全人群或相应亚类风险人群，视分析目的而定。亚类之间可能存在编码重叠或死因链多重编码问题，需说明处理方式。

### 2.3 可比性处理

年龄标化：各组比较时拟采用直接标准化，标准人口待确认。若各组年龄结构差异较大，标化率与粗率可能呈现不同模式，需同时报告并解释。

分母一致性：死亡数据与人口数据来源需一致，年份范围需匹配。若人口数据为年中人口，死亡数据为全年死亡，需说明其对应关系。若分母口径不一致，需调整或说明限制。

分类一致性：ICD编码版本、年龄组划分、地区编码需在研究期间保持一致。若期间发生编码版本变更或行政区划调整，需说明处理方式，如重新编码或分段分析。

统计检验：各组间死亡率比较拟采用率差、率比及其置信区间，或采用泊松回归、负二项回归调整混杂因素。时间趋势比较拟采用Joinpoint模型分别拟合各组，或采用交互项检验组间趋势差异。具体方法待数据确认后选择。

### 2.4 缺失数据与不确定性

若某些分组数据缺失或样本量过小，需说明其影响。小样本可能导致率估计不稳定，拟采用置信区间宽度或变异系数评估稳定性。若地区分组涉及行政区划变更，需说明变更年份及处理方式。具体缺失情况待数据确认。

## 三、减寿分析

### 3.1 分析目的与指标含义

本部分旨在计算并报告2015—2025年某地区心脑血管疾病死亡的年度、分性别YLL与YLL率。YLL（Years of Life Lost，减寿年数）反映因早死导致的寿命损失，综合考虑死亡数量与死亡年龄；YLL率（YLL率）为YLL除以相应人口数或每千人口，反映人群减寿负担。YLL与YLL率可用于比较不同年份、不同性别人群的早死负担。

### 3.2 参考寿命表的选择及其影响

参考寿命表的选择直接影响YLL计算结果。常用参考寿命表包括：全球疾病负担研究（GBD）标准寿命表、世界卫生组织（WHO）标准寿命表、某国家或地区期望寿命表、以及研究地区本地寿命表。不同参考寿命表对YLL的影响主要体现在：若采用较高的期望寿命（如GBD标准寿命表），则同一死亡年龄对应的YLL较大；若采用较低的期望寿命（如本地实际寿命表），则YLL较小。此外，参考寿命表的选择还影响不同人群间的可比性：若采用同一参考寿命表，则不同人群YLL差异反映早死负担差异；若采用各自寿命表，则差异还包含期望寿命差异。

本分析拟采用的参考寿命表待确认。在未确认前，无法计算具体YLL数值。拟议方案：若研究目的为比较不同人群早死负担，建议采用统一的参考寿命表（如GBD标准寿命表或WHO标准寿命表），以确保可比性；若研究目的为反映本地实际减寿情况，可采用本地寿命表。参考寿命表的选择需在结果中明确说明，并讨论其对YLL的影响。

### 3.3 YLL与YLL率计算方法

YLL计算拟采用标准方法：对每一死亡案例，计算其死亡年龄与参考寿命表中对应期望寿命之差，若死亡年龄小于期望寿命，则差值为YLL；若死亡年龄大于或等于期望寿命，则YLL为0。总YLL为所有死亡案例YLL之和。YLL率拟按每千人口或每10万人口计算，分母为相应年份、性别人口数。年龄组划分需与参考寿命表一致，通常按5岁组或单岁组计算。

若数据包含死亡年龄与性别，可按年度、性别分别汇总YLL。若数据仅包含年龄组死亡数，需假设各年龄组内死亡均匀分布或采用其他假设，具体待数据确认。

### 3.4 缺失数据与不确定性

YLL计算的不确定性主要来源于：参考寿命表选择、死亡年龄准确性、死亡登记完整性、人口分母误差。若死亡年龄存在错报或漏报，YLL估计可能偏倚。若参考寿命表未确认，YLL数值无法计算，本部分仅能提供方法框架。具体数值待数据与参考寿命表确认后补充。

## 四、Arriaga分解

### 4.1 分析目的与分解逻辑

本部分旨在采用Arriaga分解方法，分解死亡率变化对期望寿命的贡献。Arriaga分解将期望寿命变化分解为各年龄组死亡率变化的贡献，并可进一步分解为不同死因的贡献。分析目的为识别哪些年龄组或死因对期望寿命变化影响最大，为公共卫生干预提供依据。

### 4.2 年龄组划分与分解顺序

年龄组划分拟采用5岁组，如0—4岁、5—9岁、…、80—84岁、85岁及以上。若数据允许，可采用更细年龄组。年龄组划分需与死亡数据和人口数据一致，并考虑心脑血管疾病死亡主要集中在老年人群的特点。

分解顺序：Arriaga分解通常按年龄组从低到高依次分解，也可按死因分解。分解顺序可能影响各因素贡献的归因，但Arriaga方法在设计上旨在使各年龄组贡献之和等于期望寿命总变化。若同时分解年龄与死因，需说明分解顺序及交互项处理方式。拟议方案：先按年龄组分解，再在各年龄组内按死因分解，或采用多因素分解方法。具体顺序待确认。

### 4.3 各因素贡献的归因逻辑

Arriaga分解将期望寿命变化归因于各年龄组死亡率变化。其基本逻辑为：某年龄组死亡率变化会改变该年龄组的生存概率，进而影响期望寿命。贡献分为直接贡献、间接贡献和交互贡献。直接贡献为该年龄组死亡率变化对期望寿命的直接影响；间接贡献为该年龄组死亡率变化通过影响其他年龄组生存而对期望寿命的间接影响；交互贡献为各年龄组死亡率变化共同作用产生的交互效应。Arriaga方法通过特定公式将总变化分解为各年龄组贡献之和。

若进一步分解死因，需在各年龄组内按死因分解死亡率变化，并计算各死因对期望寿命变化的贡献。死因分类需与ICD编码一致，并注意死因链多重编码问题。

### 4.4 缺失数据与不确定性

Arriaga分解的不确定性主要来源于：年龄组划分、分解顺序、死因编码准确性、死亡登记完整性、人口分母误差。若某些年龄组或死因数据缺失，需说明处理方式。若期望寿命变化较小，分解结果可能不稳定，需报告置信区间或敏感性分析。具体数值待数据确认后计算。

## 五、重要未决条件集中说明

以下条件尚未确认，影响对应内容，但不影响本稿方法框架的完整性：

1. 研究地区具体指哪一省、市或区域，是否涉及跨地区比较及其比较单元。影响地区分组、分母口径及结果解释。
2. 死亡数据与人口数据的来源、年份范围、时间粒度（年度或月度）及率的分母口径。影响时间序列分析、分组比较及YLL计算。
3. YLL分析采用的参考寿命表或标准寿命。影响YLL与YLL率数值及不同人群比较。
4. ICD编码版本及具体编码范围。影响亚类分组与死因分解。
5. 年龄组划分、标准人口及标化方法。影响标化率与分组比较。
6. 缺失数据模式及处理方法。影响各部分的估计稳定性与不确定性。

上述条件确认后，可完成具体数值计算与结果报告。本稿不填入任何未经核实的数值、引用或结论。

案例Q05-direct
原始要求：
为研究生撰写中文研究设计的方法部分，研究Plan问答对提示词质量的影响。当前只是拟定研究，尚未实施。设计必须保留：4类任务，每类5个，共20个任务；每模式每任务独立重复3次；两名独立评审盲评。比较直接增强和Plan，并另设两组获得相同确认信息的公平对照。说明随机化、盲法、准确性、完整性、清晰度与可执行性评价以及分歧处理。具体统计检验依据未来数据分布和配对结构选择，候选方法可以说明适用条件，不预设必然显著。正文800至1200个汉字，附表另计。不提供代码，不得把拟定样本写成已纳入样本，不得编造效应量、P值、参考文献或显著性结论，不得使用假设数据。
实际确认信息：
[{"questionId":"q1","question":"Plan组的问答交互在什么条件下结束？","answer":"这是尚未实施的研究。请根据原始研究目标提出可开展的推荐设计并说明适用条件；我没有额外真实数据，不确认任何既有参数。"},{"questionId":"q2","question":"同信息公平对照的两组，确认信息以什么形式提供给模型？","answer":"这是尚未实施的研究。请根据原始研究目标提出可开展的推荐设计并说明适用条件；我没有额外真实数据，不确认任何既有参数。"},{"questionId":"q4","question":"20个任务在4类中的分配与顺序如何安排？","answer":"这是尚未实施的研究。请根据原始研究目标提出可开展的推荐设计并说明适用条件；我没有额外真实数据，不确认任何既有参数。"},{"questionId":"q5","question":"准确性、完整性、清晰度与可执行性四个维度，评分采用什么量尺？","answer":"这是尚未实施的研究。请根据原始研究目标提出可开展的推荐设计并说明适用条件；我没有额外真实数据，不确认任何既有参数。"},{"questionId":"q6","question":"正文800至1200个汉字是否包含标题、摘要与关键词？","answer":"这是尚未实施的研究。请根据原始研究目标提出可开展的推荐设计并说明适用条件；我没有额外真实数据，不确认任何既有参数。"}]
作品A：
## 研究方法

本研究拟考察Plan问答对提示词质量的影响，并设置直接增强与Plan两组核心比较，另设两组获得相同确认信息的公平对照。任务材料拟覆盖4类任务，每类5个，共20个任务；每类任务对应不同问题情境与产出要求，具体类别划分、任务内容及难度控制标准需在预研究中确定并固定。每名参与者在每一模式下对每个任务独立重复3次，以观察同一任务内提示词质量的稳定性；重复轮次之间拟设置无关填充任务或时间间隔，以降低记忆与顺序效应，具体间隔时长与填充形式需经预试验确定。研究尚未实施，以下样本、流程与统计方案均为拟议设计，适用条件随文说明。

拟议采用被试内或混合设计，具体取决于参与者招募规模与任务分配可行性。若采用被试内设计，每名参与者需完成直接增强、Plan及两组同信息对照条件下的任务，任务呈现顺序随机化，并对模式顺序进行拉丁方平衡；若采用被试间设计，则需确保各组参与者在先验提示词写作经验上可比。随机化拟在任务层面实施：20个任务在4类中的分配与顺序由研究者预先设定并随机排列，每名参与者的任务顺序独立随机，以避免类别与顺序混淆。盲法方面，评审所见材料为盲化后的提示词文本，隐藏模式标签、参与者身份、任务类别及重复轮次信息；下游作品可作为验证提示词可执行性的辅助依据，但不替换提示词质量这一评分对象。

评价拟围绕提示词质量展开，包括准确性、完整性、清晰度与可执行性四个维度。准确性指提示词是否准确反映任务目标与约束；完整性指是否涵盖完成任务所需的关键信息；清晰度指表述是否明确、无歧义；可执行性指下游模型或执行者能否据此稳定产出目标结果。四个维度的评分量尺尚未确认，拟议采用7点李克特量尺，1表示“非常差”，7表示“非常好”，并附维度定义与评分示例；若预试验显示评审者难以区分相邻等级，可调整为5点量尺或增加行为锚定描述。每份提示词由两名独立评审盲评，评审前接受统一培训并完成校准练习。分歧处理拟议流程为：先计算两名评审在各维度上的评分差异，若差异不超过1分，取均值；若超过1分，进入协商讨论；协商后仍不一致的，由第三名资深评审仲裁。仲裁结果作为最终评分，并记录分歧原因。

同信息公平对照须落实到信息流：先针对同一案例取得并封存实际确认信息，再让直接增强组与Plan组获得逐字相同的信息，且两组均可使用该信息。直接增强组不主动发起问答，但不等于不能使用已提供的确认信息；不得通过修改回答、更换任务或仅对某一组增加修订轮次来制造公平。Plan组的问答交互终止条件尚未确认，拟议主方案为：当参与者确认已获得完成任务所需的全部关键信息，或连续两轮未产生新的有效信息时终止；适用条件是该终止规则需在预试验中检验其可操作性与一致性。同信息对照中确认信息的提供形式亦未确认，拟议采用书面摘要加原始问答记录附件的形式，两组材料逐字相同；若书面摘要可能引入额外解释，则改用原始记录直接提供。

统计分析将依据未来数据分布与配对结构选择候选方法，不预设显著性。若同一参与者对同一任务重复3次，且各模式均重复测量，可采用线性混合模型，将参与者与任务作为随机效应，模式、任务类别及交互项作为固定效应；适用条件为残差近似正态、方差齐性。若数据不满足参数假设，可采用广义线性混合模型或基于秩的重复测量方差分析。若仅比较直接增强与Plan两组，且数据为配对连续评分，可采用配对t检验或Wilcoxon符号秩检验；若为独立组，则采用独立样本t检验或Mann-Whitney U检验。四维度评分可分别分析，也可在验证结构效度后合成总分；合成前需报告信度指标。缺失数据拟采用多重插补或全信息最大似然，并报告缺失比例与机制假设。所有分析将报告效应量及其置信区间，不将统计显著性作为唯一结论依据。

正文约1000个汉字，未包含标题、摘要与关键词；附表与代码另计。上述未确认参数均以拟议设计形式提出，实际实施前需经预试验与伦理审查确认。
作品B：
## 研究方法

### 研究设计与总体框架

本研究拟采用实验研究设计，考察Plan问答对提示词质量的影响。研究尚未实施，以下为推荐设计方案。核心比较为直接增强与Plan两种提示策略，并设置两组获得相同确认信息的公平对照，以分离Plan结构本身与额外确认信息的作用。研究保留4类任务，每类5个，共20个任务；每种模式对每个任务独立重复3次；由两名独立评审盲评。

### 任务与材料

任务拟按4类划分，每类5个，共20个。类别可依据任务目标与认知需求界定，例如信息提取、方案生成、约束满足与开放论证等，但具体类别需在实施前经内容效度检验确定。任务顺序采用区组随机化：将20个任务按类别分层，在每类内随机排列，再以随机顺序呈现给模型。每模式每任务独立重复3次，重复间不共享上下文，以降低顺序与记忆效应。所有提示词模板、任务描述与评分材料在实施前定稿并归档。

### 实验条件与公平对照

实验拟设四组。第一组为直接增强组，仅提供任务与基本指令。第二组为Plan组，在直接增强基础上加入Plan问答交互。第三组与第四组为公平对照：两组获得相同的确认信息，但一组以直接增强形式呈现，另一组以Plan形式呈现。确认信息的内容、措辞与呈现时机需在实施前统一，并由独立研究者审核，确保除Plan结构外无系统差异。若确认信息无法完全等同，则应在分析中将其作为协变量或分层因素处理。

### 随机化与盲法

任务顺序、条件分配与重复次序均采用随机化。每名评审独立评分，评审不知晓条件标签、研究假设与另一评审的评分。模型输出在评分前去除条件标识与可识别信息，统一格式。若无法完全盲化，则需记录盲法破坏情况并在敏感性分析中讨论。

### 评价维度与测量

评价维度包括准确性、完整性、清晰度与可执行性。准确性指内容与任务要求及事实依据的一致程度；完整性指是否覆盖任务所需关键要素；清晰度指表达是否易于理解、结构是否明确；可执行性指建议或方案是否具备可操作步骤与条件说明。评分拟采用有序量尺，例如1至5分，并附行为锚点。若未来数据近似连续且满足分布假设，可采用参数方法；若为有序分类或分布偏态，则采用非参数方法。评分前需进行评审培训与预试，以检验评分者间一致性。

### 统计分析与分歧处理

统计分析将依据未来数据分布与配对结构选择。若每任务每模式重复3次，且同一任务下不同模式配对，可采用配对检验或重复测量方差分析；若数据为有序评分或正态性不满足，可采用Wilcoxon符号秩检验、Friedman检验或累积链接混合模型。公平对照的比较需考虑确认信息相同这一分层结构，可纳入混合效应模型，以任务与评审为随机效应，条件为固定效应。若存在缺失或退出，采用意向性分析或多重插补，并报告敏感性分析。所有检验不预设必然显著，效应量与置信区间将按实际数据计算。

两名评审的分歧处理拟采用两步法：先计算评分者间一致性，如加权Kappa或组内相关系数；对分歧较大的条目，由第三名资深评审仲裁，或由两名评审讨论后重新评分。若无法达成一致，则保留原始评分并在分析中纳入评审随机效应。最终报告将说明随机化、盲法、评分量尺、分歧处理及统计选择的具体依据，并明确区分拟定方案与实施后结果。

案例Q05-plan
原始要求：
为研究生撰写中文研究设计的方法部分，研究Plan问答对提示词质量的影响。当前只是拟定研究，尚未实施。设计必须保留：4类任务，每类5个，共20个任务；每模式每任务独立重复3次；两名独立评审盲评。比较直接增强和Plan，并另设两组获得相同确认信息的公平对照。说明随机化、盲法、准确性、完整性、清晰度与可执行性评价以及分歧处理。具体统计检验依据未来数据分布和配对结构选择，候选方法可以说明适用条件，不预设必然显著。正文800至1200个汉字，附表另计。不提供代码，不得把拟定样本写成已纳入样本，不得编造效应量、P值、参考文献或显著性结论，不得使用假设数据。
实际确认信息：
[{"questionId":"q1","question":"Plan组的问答交互在什么条件下结束？","answer":"这是尚未实施的研究。请根据原始研究目标提出可开展的推荐设计并说明适用条件；我没有额外真实数据，不确认任何既有参数。"},{"questionId":"q2","question":"同信息公平对照的两组，确认信息以什么形式提供给模型？","answer":"这是尚未实施的研究。请根据原始研究目标提出可开展的推荐设计并说明适用条件；我没有额外真实数据，不确认任何既有参数。"},{"questionId":"q4","question":"20个任务在4类中的分配与顺序如何安排？","answer":"这是尚未实施的研究。请根据原始研究目标提出可开展的推荐设计并说明适用条件；我没有额外真实数据，不确认任何既有参数。"},{"questionId":"q5","question":"准确性、完整性、清晰度与可执行性四个维度，评分采用什么量尺？","answer":"这是尚未实施的研究。请根据原始研究目标提出可开展的推荐设计并说明适用条件；我没有额外真实数据，不确认任何既有参数。"},{"questionId":"q6","question":"正文800至1200个汉字是否包含标题、摘要与关键词？","answer":"这是尚未实施的研究。请根据原始研究目标提出可开展的推荐设计并说明适用条件；我没有额外真实数据，不确认任何既有参数。"}]
作品A：
## 研究方法

### 研究设计与总体框架

本研究拟采用实验研究设计，考察Plan问答对提示词质量的影响。研究尚未实施，以下为推荐设计方案。核心比较为直接增强与Plan两种提示策略，并设置两组获得相同确认信息的公平对照，以分离Plan结构本身与额外确认信息的作用。研究保留4类任务，每类5个，共20个任务；每种模式对每个任务独立重复3次；由两名独立评审盲评。

### 任务与材料

任务拟按4类划分，每类5个，共20个。类别可依据任务目标与认知需求界定，例如信息提取、方案生成、约束满足与开放论证等，但具体类别需在实施前经内容效度检验确定。任务顺序采用区组随机化：将20个任务按类别分层，在每类内随机排列，再以随机顺序呈现给模型。每模式每任务独立重复3次，重复间不共享上下文，以降低顺序与记忆效应。所有提示词模板、任务描述与评分材料在实施前定稿并归档。

### 实验条件与公平对照

实验拟设四组。第一组为直接增强组，仅提供任务与基本指令。第二组为Plan组，在直接增强基础上加入Plan问答交互。第三组与第四组为公平对照：两组获得相同的确认信息，但一组以直接增强形式呈现，另一组以Plan形式呈现。确认信息的内容、措辞与呈现时机需在实施前统一，并由独立研究者审核，确保除Plan结构外无系统差异。若确认信息无法完全等同，则应在分析中将其作为协变量或分层因素处理。

### 随机化与盲法

任务顺序、条件分配与重复次序均采用随机化。每名评审独立评分，评审不知晓条件标签、研究假设与另一评审的评分。模型输出在评分前去除条件标识与可识别信息，统一格式。若无法完全盲化，则需记录盲法破坏情况并在敏感性分析中讨论。

### 评价维度与测量

评价维度包括准确性、完整性、清晰度与可执行性。准确性指内容与任务要求及事实依据的一致程度；完整性指是否覆盖任务所需关键要素；清晰度指表达是否易于理解、结构是否明确；可执行性指建议或方案是否具备可操作步骤与条件说明。评分拟采用有序量尺，例如1至5分，并附行为锚点。若未来数据近似连续且满足分布假设，可采用参数方法；若为有序分类或分布偏态，则采用非参数方法。评分前需进行评审培训与预试，以检验评分者间一致性。

### 统计分析与分歧处理

统计分析将依据未来数据分布与配对结构选择。若每任务每模式重复3次，且同一任务下不同模式配对，可采用配对检验或重复测量方差分析；若数据为有序评分或正态性不满足，可采用Wilcoxon符号秩检验、Friedman检验或累积链接混合模型。公平对照的比较需考虑确认信息相同这一分层结构，可纳入混合效应模型，以任务与评审为随机效应，条件为固定效应。若存在缺失或退出，采用意向性分析或多重插补，并报告敏感性分析。所有检验不预设必然显著，效应量与置信区间将按实际数据计算。

两名评审的分歧处理拟采用两步法：先计算评分者间一致性，如加权Kappa或组内相关系数；对分歧较大的条目，由第三名资深评审仲裁，或由两名评审讨论后重新评分。若无法达成一致，则保留原始评分并在分析中纳入评审随机效应。最终报告将说明随机化、盲法、评分量尺、分歧处理及统计选择的具体依据，并明确区分拟定方案与实施后结果。
作品B：
## 研究方法

### 研究设计与对象

本研究采用实验研究设计，考察Plan问答对提示词质量的影响。研究对象为提示词本身，评审所见材料亦为提示词；下游作品仅作为原任务验证依据，不替代评分对象。实验保留4类任务，每类5个，共20个任务。任务类别依据研究目标划分，具体分类名称、来源与版本需在实施前核定并记录。每类任务在难度、领域与预期输出形式上尽量均衡，任务顺序安排待定，但须在实施前以可复现方式固定并记录。

实验设置四个条件。条件A为直接增强：不主动发起问答，但正常使用已提供的需求与确认信息。条件B为Plan：由增强器提出关键问题，用户回答后，将有效回答融入优化提示词。Plan不得改为模型自行生成执行计划再作答。条件C与条件D为同信息公平对照：两组接收并可使用同一原始任务和同一份实际确认信息，保留各组指定的增强流程，不改写任一组的回答、不另换任务，也不只增加某组修订轮次。两组确认信息以何种形式提供给模型尚待确认，须在实施前统一规定并记录。Plan组问答交互在什么条件下结束亦待确认，须在实施前明确终止规则，避免不同任务间交互深度不可比。

每名模式每任务独立重复3次。重复指在同一条件、同一任务下独立生成3份提示词，不得复用中间产物。随机化须在任务层面或重复层面实施，确保条件顺序、评审分配与呈现顺序不引入系统性偏差。建议对每份提示词分配唯一编号，随机化条件呈现顺序与评审顺序，并保留随机化记录以供复现。

### 评审与盲法

采用两名独立评审盲评。评审仅见提示词，不见条件标签、研究假设与生成过程信息。若提示词本身可能泄露条件，须在实施前评估并采取可行遮蔽措施，同时记录遮蔽方式及其局限。评审前须统一培训，明确四个维度的定义与评分口径。准确性指提示词是否准确反映原始任务需求与已确认信息；完整性指是否覆盖任务所需关键要素；清晰度指表述是否明确、无歧义、结构可读；可执行性指提示词是否足以指导后续生成或执行。四个维度均针对提示词评价，不针对下游作品。评分量尺尚待确认，须在实施前固定，并说明是否采用等级评分、锚点定义及是否允许中间值。下游作品可按原任务作为验证依据，用于辅助理解提示词是否可执行，但不得替换评分对象。

### 数据收集与统计口径

数据来源为实验生成的提示词及评审评分记录。缺失数据须记录原因，如评审未完成、提示词生成失败或编号丢失。缺失机制若为随机缺失，可采用删除或插补并说明；若非随机缺失，须在敏感性分析中讨论。偏倚来源包括条件泄露、评审期望、任务难度不平衡、重复间非独立性及顺序效应。须通过盲法、随机化、统一培训与过程记录降低偏倚，并在报告中说明残余不确定性。

统计检验依据未来数据分布与配对结构选择，不预设必然显著。若每任务每条件重复3次且同一任务下条件可比，可采用配对结构分析。若数据近似正态且配对差值独立，可用配对t检验或重复测量方差分析；若不满足正态或为有序评分，可用Wilcoxon符号秩检验或Friedman检验；若关注评审间一致性，可用ICC或Kendall协调系数，具体适用条件须结合评分量尺与缺失情况判断。若任务类别间异质性较大，可纳入任务作为随机效应，采用混合效应模型，但须说明模型假设与收敛情况。所有检验须报告效应量及置信区间，不编造效应量、P值或显著性结论。

### 分歧处理与可复现性

两名评审对同一提示词同一维度评分不一致时，先核对评分记录与维度定义。若差异在预设阈值内，可取均值或保留双方评分用于一致性分析；若超出阈值，由第三方仲裁或双方复核后重新评分。阈值与仲裁规则须在实施前确定并记录。全流程须保留任务清单、条件分配、随机化种子、提示词编号、评审表、评分记录、缺失说明与分析脚本说明，确保可复现。已定参数与未决参数须分开记录；未决参数包括Plan终止条件、同信息对照确认信息形式、任务分配与顺序、评分量尺及字数统计口径。正文800至1200个汉字是否包含标题、摘要与关键词尚未确认，未确认前保持未决。
