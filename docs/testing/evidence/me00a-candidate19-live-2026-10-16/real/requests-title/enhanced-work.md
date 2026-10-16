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