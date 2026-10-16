# 使用 requests 爬取网页标题：实现方案

## 一、实现方案说明

### 1.1 整体思路

采用"薄封装 + 明确契约"的方式实现：核心函数 `fetch_page_title` 负责单次抓取与解析，命令行入口 `main` 负责参数解析与结果输出。请求层使用 `requests`，解析层默认使用标准库 `html.parser`（零额外依赖），并在文档中说明何时切换到 BeautifulSoup。

### 1.2 关键流程

1. 校验 URL（非空、scheme 为 http/https、host 非空）。
2. 使用 `requests.get` 发起请求，设置 `timeout=(connect, read)` 与 `User-Agent`。
3. 检查状态码，非 2xx 抛出明确异常。
4. 依据 `response.encoding` / `apparent_encoding` 解码，失败则回退 `utf-8` 并记录。
5. 用 `html.parser` 提取 `<title>` 文本，去空白。
6. 返回标题字符串；无标题则抛出 `TitleNotFoundError`。

### 1.3 模块划分

| 模块 | 职责 |
| --- | --- |
| `title_fetcher/__init__.py` | 包入口，导出公共 API |
| `title_fetcher/exceptions.py` | 自定义异常类型 |
| `title_fetcher/parser.py` | HTML 标题解析（html.parser 实现） |
| `title_fetcher/fetcher.py` | 请求发送、编码处理、异常映射 |
| `title_fetcher/cli.py` | 命令行入口 |
| `tests/test_fetcher.py` | 单元测试（mock requests） |
| `tests/test_parser.py` | 解析器单元测试 |

> 说明：若项目已有 `utils/http.py` 等模块，可将 `fetcher.py` 合并进去，保持现有结构不变。

---

## 二、模块清单及职责

- **新增** `title_fetcher/exceptions.py`：定义 `FetchError` 基类及子类。
- **新增** `title_fetcher/parser.py`：`extract_title(html: str) -> str | None`。
- **新增** `title_fetcher/fetcher.py`：`fetch_page_title(url, *, timeout, session, headers) -> str`。
- **新增** `title_fetcher/cli.py`：`main(argv=None) -> int`。
- **新增** `tests/`：单元测试。
- **不修改** 现有模块，避免破坏兼容性。

---

## 三、关键代码

### 3.1 `exceptions.py`

```python
class FetchError(Exception):
    """抓取相关错误的基类。"""


class InvalidURLError(FetchError):
    """URL 为空、格式非法或 scheme 不受支持。"""


class RequestTimeoutError(FetchError):
    """连接或读取超时。"""


class ConnectionFailedError(FetchError):
    """DNS 失败、连接被拒绝等网络错误。"""


class HTTPStatusError(FetchError):
    """响应状态码非 2xx。"""

    def __init__(self, status_code: int, url: str) -> None:
        super().__init__(f"HTTP {status_code} for {url}")
        self.status_code = status_code
        self.url = url


class TitleNotFoundError(FetchError):
    """响应中不存在 <title> 标签或内容为空。"""


class DecodingError(FetchError):
    """响应体无法按任何候选编码解码。"""
```

### 3.2 `parser.py`

```python
from html.parser import HTMLParser


class _TitleParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self._in_title = False
        self._chunks: list[str] = []

    def handle_starttag(self, tag, attrs):
        if tag.lower() == "title":
            self._in_title = True

    def handle_endtag(self, tag):
        if tag.lower() == "title":
            self._in_title = False

    def handle_data(self, data):
        if self._in_title:
            self._chunks.append(data)

    @property
    def title(self) -> str | None:
        text = "".join(self._chunks).strip()
        return text or None


def extract_title(html: str) -> str | None:
    """从 HTML 文本中提取 <title> 内容；无标题返回 None。"""
    if not html:
        return None
    parser = _TitleParser()
    try:
        parser.feed(html)
        parser.close()
    except Exception:
        # html.parser 对畸形 HTML 通常不抛异常，此处仅作兜底
        return None
    return parser.title
```

> **何时改用 BeautifulSoup**：当页面存在嵌套 `<title>`、需要 CSS 选择器、或需要处理严重畸形 HTML 时。此时将 `extract_title` 替换为 `BeautifulSoup(html, "html.parser").title.string` 即可，接口不变。

### 3.3 `fetcher.py`

```python
from urllib.parse import urlparse

import requests

from .exceptions import (
    ConnectionFailedError,
    DecodingError,
    HTTPStatusError,
    InvalidURLError,
    RequestTimeoutError,
    TitleNotFoundError,
)
from .parser import extract_title

DEFAULT_TIMEOUT = (5.0, 10.0)  # (connect, read)
DEFAULT_HEADERS = {
    "User-Agent": "title-fetcher/1.0 (+https://example.invalid)",
    "Accept": "text/html,application/xhtml+xml",
}


def _validate_url(url: str) -> None:
    if not isinstance(url, str) or not url.strip():
        raise InvalidURLError("URL 不能为空")
    parsed = urlparse(url)
    if parsed.scheme not in ("http", "https"):
        raise InvalidURLError(f"不支持的 URL scheme: {parsed.scheme!r}")
    if not parsed.netloc:
        raise InvalidURLError(f"URL 缺少主机名: {url!r}")


def _decode(response: requests.Response) -> str:
    # 优先使用 requests 推断的编码；失败则回退 utf-8
    candidates = [response.encoding, response.apparent_encoding, "utf-8"]
    for enc in candidates:
        if not enc:
            continue
        try:
            return response.content.decode(enc, errors="strict")
        except (LookupError, UnicodeDecodeError):
            continue
    raise DecodingError("无法使用任何候选编码解码响应体")


def fetch_page_title(
    url: str,
    *,
    timeout: tuple[float, float] = DEFAULT_TIMEOUT,
    session: requests.Session | None = None,
    headers: dict[str, str] | None = None,
) -> str:
    """抓取 URL 并返回 <title> 文本。

    参数:
        url: 目标 URL，必须为 http/https。
        timeout: (connect, read) 秒数。
        session: 可选的 requests.Session，便于复用连接。
        headers: 覆盖默认请求头。

    返回:
        去除首尾空白后的标题字符串。

    异常:
        InvalidURLError, RequestTimeoutError, ConnectionFailedError,
        HTTPStatusError, DecodingError, TitleNotFoundError。
    """
    _validate_url(url)

    requester = session or requests
    merged_headers = {**DEFAULT_HEADERS, **(headers or {})}

    try:
        response = requester.get(url, timeout=timeout, headers=merged_headers)
    except requests.exceptions.Timeout as exc:
        raise RequestTimeoutError(f"请求超时: {url}") from exc
    except requests.exceptions.ConnectionError as exc:
        raise ConnectionFailedError(f"连接失败: {url}") from exc
    except requests.exceptions.RequestException as exc:
        # 其他 requests 异常统一映射，避免泄漏底层细节
        raise ConnectionFailedError(f"请求异常: {url}: {exc}") from exc

    if not (200 <= response.status_code < 300):
        raise HTTPStatusError(response.status_code, url)

    html = _decode(response)
    title = extract_title(html)
    if title is None:
        raise TitleNotFoundError(f"未找到 <title>: {url}")
    return title
```

### 3.4 `cli.py`

```python
import argparse
import sys

from .exceptions import FetchError
from .fetcher import fetch_page_title


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="抓取网页 <title>")
    parser.add_argument("url", help="目标 URL，例如 https://example.com")
    parser.add_argument("--connect-timeout", type=float, default=5.0)
    parser.add_argument("--read-timeout", type=float, default=10.0)
    args = parser.parse_args(argv)

    try:
        title = fetch_page_title(
            args.url,
            timeout=(args.connect_timeout, args.read_timeout),
        )
    except FetchError as exc:
        print(f"[ERROR] {exc}", file=sys.stderr)
        return 1

    print(title)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
```

---

## 四、接口契约

### 4.1 `fetch_page_title`

```python
def fetch_page_title(
    url: str,
    *,
    timeout: tuple[float, float] = (5.0, 10.0),
    session: requests.Session | None = None,
    headers: dict[str, str] | None = None,
) -> str
```

| 项 | 说明 |
| --- | --- |
| `url` | 必填，`str`，必须为 `http`/`https` 且含 host |
| `timeout` | `(connect, read)` 秒数，均为正数 |
| `session` | 可选，复用连接 |
| `headers` | 可选，覆盖默认头 |
| 返回 | `str`，非空标题 |
| 异常 | 见下表 |

| 异常 | 触发条件 |
| --- | --- |
| `InvalidURLError` | 空值、非字符串、scheme 非法、无 host |
| `RequestTimeoutError` | 连接或读取超时 |
| `ConnectionFailedError` | DNS/连接/其他 requests 异常 |
| `HTTPStatusError` | 状态码非 2xx（含 `status_code`、`url` 属性） |
| `DecodingError` | 所有候选编码均失败 |
| `TitleNotFoundError` | 无 `<title>` 或内容为空 |

### 4.2 `extract_title`

```python
def extract_title(html: str) -> str | None
```

- 输入：HTML 字符串（空字符串返回 `None`）。
- 输出：去空白后的标题，或 `None`。
- 不抛异常（内部兜底）。

### 4.3 `main`

```python
def main(argv: list[str] | None = None) -> int
```

- 返回 `0` 成功，`1` 失败（错误信息打印到 stderr）。

---

## 五、测试方案

### 5.1 正常流程

| 用例 | 输入 | 预期 |
| --- | --- | --- |
| 标准页面 | mock 200 + `<html><head><title>Hello</title></head></html>` | 返回 `"Hello"` |
| 标题含空白 | `<title>  Hi  </title>` | 返回 `"Hi"` |
| 标题含实体 | `<title>A &amp; B</title>` | 返回 `"A & B"` |
| 大小写标签 | `<TITLE>X</TITLE>` | 返回 `"X"` |

### 5.2 异常流程

| 用例 | 触发方式 | 预期 |
| --- | --- | --- |
| 超时 | mock `requests.exceptions.Timeout` | `RequestTimeoutError` |
| 连接失败 | mock `requests.exceptions.ConnectionError` | `ConnectionFailedError` |
| 404 | mock 状态码 404 | `HTTPStatusError`，`status_code == 404` |
| 500 | mock 状态码 500 | `HTTPStatusError` |
| 空 URL | `fetch_page_title("")` | `InvalidURLError` |
| 非法 scheme | `fetch_page_title("ftp://x")` | `InvalidURLError` |
| 无 host | `fetch_page_title("http://")` | `InvalidURLError` |

### 5.3 边界场景

| 用例 | 输入 | 预期 |
| --- | --- | --- |
| 空响应体 | mock 200 + `b""` | `TitleNotFoundError` |
| 无 title 标签 | `<html><body>hi</body></html>` | `TitleNotFoundError` |
| 空 title | `<title></title>` | `TitleNotFoundError` |
| 编码异常 | 声明 `charset=unknown` 且字节非法 | `DecodingError` 或回退 utf-8 成功 |
| 嵌套 title | `<title>a<title>b</title></title>` | 返回 `"ab"`（html.parser 行为，需在测试中固化） |
| 非字符串 URL | `fetch_page_title(None)` | `InvalidURLError` |

### 5.4 测试实现要点

- 使用 `unittest.mock.patch("requests.get")` 或注入 `session` 假对象，避免真实网络。
- 断言异常类型与消息关键字，不依赖具体文案。
- 对 `extract_title` 单独做纯函数测试，覆盖畸形 HTML。

---

## 六、最小输入示例

> 以下为示例，不作为正式事实。

**输入（命令行）**

```bash
python -m title_fetcher.cli https://example.com
```

**预期输出（示例）**

```
Example Domain
```

**失败示例（示例）**

```bash
python -m title_fetcher.cli https://example.com/not-found
# stderr: [ERROR] HTTP 404 for https://example.com/not-found
# 退出码: 1
```

---

## 七、约束与假设说明

- **已知事实**：任务要求使用 `requests`；标题提取主方案为 `html.parser`。
- **用户确认信息**：无（未提供目标 URL 来源、是否允许第三方依赖、是否已有项目结构）。
- **必要假设**：
  1. 项目为 Python 3.9+（使用 `list[str]`、`str | None` 语法）。
  2. 允许新增独立包 `title_fetcher`，不修改现有模块。
  3. 默认超时 `(5, 10)` 秒为合理值，可由调用方覆盖。
  4. 不涉及认证、Cookie、代理等场景；如需，可通过 `session` 注入。
- **未覆盖**：JavaScript 渲染页面、反爬策略、重定向策略定制（`requests` 默认跟随重定向）。
- **安全**：代码与日志不输出任何凭据；未读取 `.env`、密钥或生产配置。