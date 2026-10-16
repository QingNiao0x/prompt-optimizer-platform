# 网页标题爬取功能模块实现方案

## 一、实现方案说明

### 整体思路
本模块使用 Python `requests` 库发起 HTTP GET 请求获取网页内容，通过标准库 `html.parser` 解析 HTML 并提取 `<title>` 标签内容。采用标准库解析器以减少第三方依赖，同时提供清晰的异常处理机制。

### 模块划分

| 模块/文件 | 职责 |
|-----------|------|
| `title_fetcher.py` | 核心功能模块：请求发送、标题提取、异常处理 |
| `test_title_fetcher.py` | 单元测试：覆盖正常、异常、边界场景 |

### 关键流程

```
输入 URL → 校验 URL 格式 → 发起 HTTP GET 请求（带超时）→ 检查状态码 → 解析响应编码 → 提取 <title> 内容 → 返回标题
                ↓ 失败                          ↓ 非 2xx              ↓ 无 title 标签
            抛出异常/返回错误                     抛出异常              返回 None 或抛异常
```

---

## 二、关键代码实现

### `title_fetcher.py`

```python
"""网页标题爬取模块

使用 requests 库获取网页内容，通过 html.parser 提取 <title> 标签文本。
"""

from __future__ import annotations

import re
from html.parser import HTMLParser
from typing import Optional
from urllib.parse import urlparse

import requests


# ---------- 自定义异常 ----------

class TitleFetchError(Exception):
    """标题获取失败的基类异常。"""


class InvalidURLError(TitleFetchError):
    """URL 格式非法。"""


class RequestTimeoutError(TitleFetchError):
    """请求超时。"""


class ConnectionFailedError(TitleFetchError):
    """连接失败。"""


class HTTPStatusError(TitleFetchError):
    """HTTP 状态码非 2xx。"""

    def __init__(self, status_code: int, message: str = ""):
        self.status_code = status_code
        super().__init__(message or f"HTTP 请求失败，状态码: {status_code}")


class EncodingError(TitleFetchError):
    """响应编码无法识别或解码失败。"""


class TitleNotFoundError(TitleFetchError):
    """响应中未找到 <title> 标签。"""


# ---------- HTML 标题解析器 ----------

class TitleParser(HTMLParser):
    """从 HTML 中提取 <title> 标签内容的解析器。"""

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self._in_title = False
        self._title_parts: list[str] = []
        self.title: Optional[str] = None

    def handle_starttag(self, tag: str, attrs) -> None:
        if tag.lower() == "title":
            self._in_title = True
            self._title_parts = []

    def handle_endtag(self, tag: str) -> None:
        if tag.lower() == "title" and self._in_title:
            self._in_title = False
            self.title = "".join(self._title_parts).strip()

    def handle_data(self, data: str) -> None:
        if self._in_title:
            self._title_parts.append(data)


# ---------- 核心函数 ----------

# 预编译 URL 格式校验正则
_URL_PATTERN = re.compile(
    r"^https?://"  # 仅允许 http/https
    r"[^\s/$.?#].[^\s]*$",
    re.IGNORECASE,
)

DEFAULT_TIMEOUT: float = 10.0
DEFAULT_HEADERS: dict[str, str] = {
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/120.0.0.0 Safari/537.36"
    ),
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
}


def _validate_url(url: str) -> None:
    """校验 URL 格式，非法时抛出 InvalidURLError。

    Args:
        url: 待校验的 URL 字符串。

    Raises:
        InvalidURLError: URL 为空、非字符串或格式非法。
    """
    if not isinstance(url, str) or not url.strip():
        raise InvalidURLError("URL 不能为空")

    url = url.strip()

    # 基本格式校验
    if not _URL_PATTERN.match(url):
        raise InvalidURLError(f"URL 格式非法: {url!r}")

    # 使用 urlparse 进一步校验
    parsed = urlparse(url)
    if not parsed.scheme or not parsed.netloc:
        raise InvalidURLError(f"URL 缺少 scheme 或 host: {url!r}")


def _extract_title(html_content: str) -> Optional[str]:
    """从 HTML 内容中提取 <title> 标签文本。

    Args:
        html_content: HTML 字符串。

    Returns:
        提取到的标题文本；若未找到 <title> 标签则返回 None。
    """
    parser = TitleParser()
    parser.feed(html_content)
    parser.close()
    return parser.title


def fetch_title(
    url: str,
    timeout: float = DEFAULT_TIMEOUT,
    headers: Optional[dict[str, str]] = None,
) -> str:
    """获取指定 URL 的网页标题。

    Args:
        url: 目标网页 URL，必须以 http:// 或 https:// 开头。
        timeout: 请求超时时间（秒），默认 10 秒。
        headers: 自定义请求头，默认使用内置的浏览器 User-Agent。

    Returns:
        网页标题字符串（已去除首尾空白）。

    Raises:
        InvalidURLError: URL 格式非法。
        RequestTimeoutError: 请求超时。
        ConnectionFailedError: 网络连接失败。
        HTTPStatusError: HTTP 状态码非 2xx。
        EncodingError: 响应编码无法识别或解码失败。
        TitleNotFoundError: 响应中未找到 <title> 标签。
    """
    # 1. 校验 URL
    _validate_url(url)
    url = url.strip()

    # 2. 校验 timeout
    if timeout <= 0:
        raise ValueError(f"timeout 必须为正数，当前值: {timeout}")

    # 3. 发起请求
    request_headers = DEFAULT_HEADERS.copy()
    if headers:
        request_headers.update(headers)

    try:
        response = requests.get(
            url,
            timeout=timeout,
            headers=request_headers,
            allow_redirects=True,
        )
    except requests.exceptions.Timeout:
        raise RequestTimeoutError(f"请求超时（{timeout} 秒）: {url}")
    except requests.exceptions.ConnectionError as exc:
        raise ConnectionFailedError(f"连接失败: {url} - {exc}")
    except requests.exceptions.RequestException as exc:
        raise ConnectionFailedError(f"请求异常: {url} - {exc}")

    # 4. 检查状态码
    if not 200 <= response.status_code < 300:
        raise HTTPStatusError(response.status_code)

    # 5. 处理响应编码
    try:
        # 优先使用响应头中的 charset；若缺失则使用 apparent_encoding 探测
        if response.encoding is None or response.encoding.lower() == "iso-8859-1":
            # requests 默认将无 charset 的响应编码设为 ISO-8859-1，
            # 此时使用 apparent_encoding 进行探测
            response.encoding = response.apparent_encoding
        html_content = response.text
    except (UnicodeDecodeError, LookupError) as exc:
        raise EncodingError(f"响应编码处理失败: {exc}")

    # 6. 提取标题
    title = _extract_title(html_content)
    if title is None or title == "":
        raise TitleNotFoundError(f"未在响应中找到 <title> 标签: {url}")

    return title
```

---

## 三、接口契约

### 函数签名

```python
def fetch_title(
    url: str,
    timeout: float = DEFAULT_TIMEOUT,
    headers: Optional[dict[str, str]] = None,
) -> str
```

### 参数说明

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| `url` | `str` | 是 | — | 目标网页 URL，必须以 `http://` 或 `https://` 开头 |
| `timeout` | `float` | 否 | `10.0` | 请求超时时间（秒），必须为正数 |
| `headers` | `Optional[dict[str, str]]` | 否 | `None` | 自定义请求头，会与默认请求头合并 |

### 返回值

| 类型 | 说明 |
|------|------|
| `str` | 网页 `<title>` 标签内容（已去除首尾空白） |

### 异常约定

| 异常类型 | 触发条件 |
|----------|----------|
| `InvalidURLError` | URL 为空、非字符串、格式非法（非 http/https、缺少 host 等） |
| `RequestTimeoutError` | 请求超过 `timeout` 秒未完成 |
| `ConnectionFailedError` | DNS 解析失败、连接被拒绝、TLS 错误等网络层异常 |
| `HTTPStatusError` | 响应状态码非 2xx（含 3xx 未重定向成功、4xx、5xx） |
| `EncodingError` | 响应编码无法识别或解码失败 |
| `TitleNotFoundError` | 响应中无 `<title>` 标签或标签内容为空 |
| `ValueError` | `timeout` 参数非正数 |

---

## 四、测试方案

### `test_title_fetcher.py`

```python
"""title_fetcher 模块的单元测试。"""

import unittest
from unittest.mock import Mock, patch

import requests

from title_fetcher import (
    ConnectionFailedError,
    EncodingError,
    HTTPStatusError,
    InvalidURLError,
    RequestTimeoutError,
    TitleNotFoundError,
    fetch_title,
)


class TestFetchTitleNormal(unittest.TestCase):
    """正常流程测试。"""

    @patch("title_fetcher.requests.get")
    def test_fetch_title_success(self, mock_get: Mock) -> None:
        """正常获取标题。"""
        mock_response = Mock()
        mock_response.status_code = 200
        mock_response.encoding = "utf-8"
        mock_response.text = "<html><head><title>测试页面</title></head><body></body></html>"
        mock_get.return_value = mock_response

        result = fetch_title("https://example.com")
        self.assertEqual(result, "测试页面")

    @patch("title_fetcher.requests.get")
    def test_fetch_title_with_whitespace(self, mock_get: Mock) -> None:
        """标题包含首尾空白，应被去除。"""
        mock_response = Mock()
        mock_response.status_code = 200
        mock_response.encoding = "utf-8"
        mock_response.text = "<html><head><title>  带空白的标题  </title></head></html>"
        mock_get.return_value = mock_response

        result = fetch_title("https://example.com")
        self.assertEqual(result, "带空白的标题")

    @patch("title_fetcher.requests.get")
    def test_fetch_title_with_html_entities(self, mock_get: Mock) -> None:
        """标题包含 HTML 实体，应被正确解码。"""
        mock_response = Mock()
        mock_response.status_code = 200
        mock_response.encoding = "utf-8"
        mock_response.text = "<html><head><title>A &amp; B &lt; C</title></head></html>"
        mock_get.return_value = mock_response

        result = fetch_title("https://example.com")
        self.assertEqual(result, "A & B < C")

    @patch("title_fetcher.requests.get")
    def test_fetch_title_redirect(self, mock_get: Mock) -> None:
        """重定向后获取标题。"""
        mock_response = Mock()
        mock_response.status_code = 200
        mock_response.encoding = "utf-8"
        mock_response.text = "<html><head><title>重定向后页面</title></head></html>"
        mock_get.return_value = mock_response

        result = fetch_title("http://example.com/redirect")
        self.assertEqual(result, "重定向后页面")


class TestFetchTitleExceptions(unittest.TestCase):
    """异常流程测试。"""

    def test_invalid_url_empty(self) -> None:
        """空 URL 应抛出 InvalidURLError。"""
        with self.assertRaises(InvalidURLError):
            fetch_title("")

    def test_invalid_url_none(self) -> None:
        """None URL 应抛出 InvalidURLError。"""
        with self.assertRaises(InvalidURLError):
            fetch_title(None)  # type: ignore[arg-type]

    def test_invalid_url_no_scheme(self) -> None:
        """缺少 scheme 的 URL 应抛出 InvalidURLError。"""
        with self.assertRaises(InvalidURLError):
            fetch_title("example.com")

    def test_invalid_url_ftp(self) -> None:
        """非 http/https scheme 应抛出 InvalidURLError。"""
        with self.assertRaises(InvalidURLError):
            fetch_title("ftp://example.com")

    def test_invalid_timeout(self) -> None:
        """非正数 timeout 应抛出 ValueError。"""
        with self.assertRaises(ValueError):
            fetch_title("https://example.com", timeout=0)
        with self.assertRaises(ValueError):
            fetch_title("https://example.com", timeout=-5)

    @patch("title_fetcher.requests.get")
    def test_timeout_error(self, mock_get: Mock) -> None:
        """请求超时应抛出 RequestTimeoutError。"""
        mock_get.side_effect = requests.exceptions.Timeout()
        with self.assertRaises(RequestTimeoutError):
            fetch_title("https://example.com", timeout=1)

    @patch("title_fetcher.requests.get")
    def test_connection_error(self, mock_get: Mock) -> None:
        """连接失败应抛出 ConnectionFailedError。"""
        mock_get.side_effect = requests.exceptions.ConnectionError()
        with self.assertRaises(ConnectionFailedError):
            fetch_title("https://example.com")

    @patch("title_fetcher.requests.get")
    def test_http_404(self, mock_get: Mock) -> None:
        """404 状态码应抛出 HTTPStatusError。"""
        mock_response = Mock()
        mock_response.status_code = 404
        mock_get.return_value = mock_response

        with self.assertRaises(HTTPStatusError) as ctx:
            fetch_title("https://example.com/not-found")
        self.assertEqual(ctx.exception.status_code, 404)

    @patch("title_fetcher.requests.get")
    def test_http_500(self, mock_get: Mock) -> None:
        """500 状态码应抛出 HTTPStatusError。"""
        mock_response = Mock()
        mock_response.status_code = 500
        mock_get.return_value = mock_response

        with self.assertRaises(HTTPStatusError):
            fetch_title("https://example.com")

    @patch("title_fetcher.requests.get")
    def test_no_title_tag(self, mock_get: Mock) -> None:
        """无 <title> 标签应抛出 TitleNotFoundError。"""
        mock_response = Mock()
        mock_response.status_code = 200
        mock_response.encoding = "utf-8"
        mock_response.text = "<html><head></head><body>无标题页面</body></html>"
        mock_get.return_value = mock_response

        with self.assertRaises(TitleNotFoundError):
            fetch_title("https://example.com")

    @patch("title_fetcher.requests.get")
    def test_empty_title_tag(self, mock_get: Mock) -> None:
        """空 <title> 标签应抛出 TitleNotFoundError。"""
        mock_response = Mock()
        mock_response.status_code = 200
        mock_response.encoding = "utf-8"
        mock_response.text = "<html><head><title></title></head></html>"
        mock_get.return_value = mock_response

        with self.assertRaises(TitleNotFoundError):
            fetch_title("https://example.com")


class TestFetchTitleBoundary(unittest.TestCase):
    """边界场景测试。"""

    @patch("title_fetcher.requests.get")
    def test_empty_response_body(self, mock_get: Mock) -> None:
        """空响应体应抛出 TitleNotFoundError。"""
        mock_response = Mock()
        mock_response.status_code = 200
        mock_response.encoding = "utf-8"
        mock_response.text = ""
        mock_get.return_value = mock_response

        with self.assertRaises(TitleNotFoundError):
            fetch_title("https://example.com")

    @patch("title_fetcher.requests.get")
    def test_uppercase_title_tag(self, mock_get: Mock) -> None:
        """大写 <TITLE> 标签应被正确识别。"""
        mock_response = Mock()
        mock_response.status_code = 200
        mock_response.encoding = "utf-8"
        mock_response.text = "<HTML><HEAD><TITLE>大写标签</TITLE></HEAD></HTML>"
        mock_get.return_value = mock_response

        result = fetch_title("https://example.com")
        self.assertEqual(result, "大写标签")

    @patch("title_fetcher.requests.get")
    def test_title_with_nested_tags(self, mock_get: Mock) -> None:
        """<title> 内嵌套标签（如 <b>）应提取纯文本。"""
        mock_response = Mock()
        mock_response.status_code = 200
        mock_response.encoding = "utf-8"
        mock_response.text = "<html><head><title>加粗<b>标题</b></title></head></html>"
        mock_get.return_value = mock_response

        result = fetch_title("https://example.com")
        self.assertEqual(result, "加粗标题")

    @patch("title_fetcher.requests.get")
    def test_iso_8859_1_default_encoding(self, mock_get: Mock) -> None:
        """响应编码为 ISO-8859-1 时应使用 apparent_encoding 探测。"""
        mock_response = Mock()
        mock_response.status_code = 200
        mock_response.encoding = "ISO-8859-1"
        mock_response.apparent_encoding = "utf-8"
        mock_response.text = "<html><head><title>编码测试</title></head></html>"
        mock_get.return_value = mock_response

        result = fetch_title("https://example.com")
        self.assertEqual(result, "编码测试")

    @patch("title_fetcher.requests.get")
    def test_unicode_decode_error(self, mock_get: Mock) -> None:
        """解码失败应抛出 EncodingError。"""
        mock_response = Mock()
        mock_response.status_code = 200
        mock_response.encoding = "utf-8"
        # 模拟 text 属性抛出 UnicodeDecodeError
        mock_response.text = Mock(side_effect=UnicodeDecodeError("utf-8", b"\xff", 0, 1, "invalid"))
        mock_get.return_value = mock_response

        with self.assertRaises(EncodingError):
            fetch_title("https://example.com")

    @patch("title_fetcher.requests.get")
    def test_custom_headers_merged(self, mock_get: Mock) -> None:
        """自定义 headers 应与默认 headers 合并。"""
        mock_response = Mock()
        mock_response.status_code = 200
        mock_response.encoding = "utf-8"
        mock_response.text = "<html><head><title>自定义头</title></head></html>"
        mock_get.return_value = mock_response

        custom_headers = {"X-Custom-Header": "test-value"}
        fetch_title("https://example.com", headers=custom_headers)

        # 验证调用时 headers 包含默认和自定义内容
        call_kwargs = mock_get.call_args.kwargs
        self.assertIn("User-Agent", call_kwargs["headers"])
        self.assertEqual(call_kwargs["headers"]["X-Custom-Header"], "test-value")


if __name__ == "__main__":
    unittest.main()
```

### 测试用例汇总

| 场景 | 测试用例 | 预期结果 |
|------|----------|----------|
| **正常** | 标准 HTML 含 `<title>` | 返回标题文本 |
| **正常** | 标题含首尾空白 | 返回去除空白后的标题 |
| **正常** | 标题含 HTML 实体 | 返回解码后的标题 |
| **正常** | 重定向后获取 | 返回最终页面标题 |
| **异常** | 空 URL / None URL | 抛出 `InvalidURLError` |
| **异常** | 无 scheme / 非 http(s) scheme | 抛出 `InvalidURLError` |
| **异常** | timeout ≤ 0 | 抛出 `ValueError` |
| **异常** | 请求超时 | 抛出 `RequestTimeoutError` |
| **异常** | 连接失败 | 抛出 `ConnectionFailedError` |
| **异常** | 404 / 500 状态码 | 抛出 `HTTPStatusError` |
| **异常** | 无 `<title>` 标签 | 抛出 `TitleNotFoundError` |
| **异常** | 空 `<title>` 标签 | 抛出 `TitleNotFoundError` |
| **边界** | 空响应体 | 抛出 `TitleNotFoundError` |
| **边界** | 大写 `<TITLE>` 标签 | 正常返回标题 |
| **边界** | `<title>` 内嵌套标签 | 提取纯文本 |
| **边界** | 默认 ISO-8859-1 编码 | 使用 apparent_encoding 探测后正常返回 |
| **边界** | 解码失败 | 抛出 `EncodingError` |
| **边界** | 自定义 headers | 与默认 headers 合并后发送 |

---

## 五、最小输入示例

### 示例（标注为示例，不作为正式事实）

```python
from title_fetcher import fetch_title

# 输入
url = "https://www.example.com"

# 调用
title = fetch_title(url)

# 预期输出（示例，实际取决于目标网站内容）
print(title)
# 输出: "Example Domain"
```

### 命令行使用示例

如需命令行入口，可添加以下代码至 `title_fetcher.py` 末尾：

```python
if __name__ == "__main__":
    import sys

    if len(sys.argv) < 2:
        print("用法: python title_fetcher.py <URL> [timeout]", file=sys.stderr)
        sys.exit(1)

    target_url = sys.argv[1]
    request_timeout = float(sys.argv[2]) if len(sys.argv) > 2 else DEFAULT_TIMEOUT

    try:
        result = fetch_title(target_url, timeout=request_timeout)
        print(result)
    except TitleFetchError as exc:
        print(f"错误: {exc}", file=sys.stderr)
        sys.exit(1)
```

```bash
# 命令行调用
python title_fetcher.py https://www.example.com
# 预期输出（示例）: Example Domain
```

---

## 六、设计说明与限制

### 关键依据

1. **使用 `html.parser` 而非 BeautifulSoup**：标准库 `html.parser` 足以处理 `<title>` 标签提取，避免引入第三方依赖。若后续需要解析复杂 HTML 结构（如处理畸形 HTML、提取多个元素），可考虑引入 BeautifulSoup 作为增强方案。

2. **编码处理策略**：`requests` 在响应头无 `charset` 时默认使用 `ISO-8859-1`，这会导致中文乱码。本实现检测到该默认值时使用 `apparent_encoding`（基于内容探测）进行修正。

3. **异常分层**：自定义异常继承自 `TitleFetchError` 基类，调用方可统一捕获 `TitleFetchError` 处理所有业务异常，也可精确捕获特定异常类型。

### 适用范围与限制

- 仅支持 `http://` 和 `https://` 协议。
- 提取的是 HTML 中第一个 `<title>` 标签的内容（符合 HTML 规范，`<title>` 应唯一）。
- 不执行 JavaScript，对于 SPA（单页应用）页面可能无法获取动态渲染的标题。
- 默认超时 10 秒，可根据网络环境调整。

### 必要假设

- 目标 URL 返回的是 HTML 内容（非 JSON、XML 等）。
- 目标服务器允许程序化访问（未设置反爬机制）。
- 网络环境可正常访问目标 URL。