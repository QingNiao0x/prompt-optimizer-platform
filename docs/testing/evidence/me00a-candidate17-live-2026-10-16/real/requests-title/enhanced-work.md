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