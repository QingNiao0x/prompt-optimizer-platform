# 网页标题抓取工具

## 依赖安装

```bash
pip install requests
```

HTML 解析使用 Python 标准库 `html.parser`，无需额外依赖。

## 完整代码

```python
"""
fetch_titles.py
使用 requests 抓取网页并提取 <title> 文本。
"""

import re
from html.parser import HTMLParser
from typing import Union, List, Dict, Any

import requests


# ---------- 常量 ----------
DEFAULT_TIMEOUT = 10  # 秒
USER_AGENT = "Mozilla/5.0 (compatible; TitleFetcher/1.0)"


# ---------- HTML 标题解析器 ----------
class _TitleParser(HTMLParser):
    """提取第一个 <title> 标签内的文本。"""

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self._in_title = False
        self._done = False
        self._parts: List[str] = []

    def handle_starttag(self, tag: str, attrs) -> None:
        if tag.lower() == "title" and not self._done:
            self._in_title = True

    def handle_endtag(self, tag: str) -> None:
        if tag.lower() == "title" and self._in_title:
            self._in_title = False
            self._done = True

    def handle_data(self, data: str) -> None:
        if self._in_title:
            self._parts.append(data)

    @property
    def title(self) -> str:
        return "".join(self._parts).strip()


def _extract_title(html_text: str) -> str:
    """从 HTML 文本中提取标题；无 <title> 时返回空字符串。"""
    parser = _TitleParser()
    try:
        parser.feed(html_text)
        parser.close()
    except Exception:
        # 解析异常时退化为正则兜底
        m = re.search(r"<title[^>]*>(.*?)</title>", html_text, re.I | re.S)
        return m.group(1).strip() if m else ""
    return parser.title


# ---------- 单 URL 抓取 ----------
def _fetch_one(url: str, timeout: float) -> Dict[str, Any]:
    """
    抓取单个 URL 的标题。
    返回 dict: {"url": ..., "ok": bool, "title": str|None, "error": str|None}
    """
    result: Dict[str, Any] = {"url": url, "ok": False, "title": None, "error": None}

    # 输入校验
    if not isinstance(url, str) or not url.strip():
        result["error"] = "URL 为空或不是字符串"
        return result
    url = url.strip()
    if not re.match(r"^https?://", url, re.I):
        result["error"] = "URL 必须以 http:// 或 https:// 开头"
        return result

    try:
        resp = requests.get(
            url,
            timeout=timeout,
            headers={"User-Agent": USER_AGENT},
            allow_redirects=True,
        )
    except requests.exceptions.Timeout:
        result["error"] = f"请求超时（>{timeout}s）"
        return result
    except requests.exceptions.SSLError:
        result["error"] = "SSL 证书校验失败"
        return result
    except requests.exceptions.ConnectionError:
        result["error"] = "连接失败（DNS/网络不可达）"
        return result
    except requests.exceptions.RequestException as exc:
        # 只暴露异常类型，避免泄露敏感信息
        result["error"] = f"请求异常：{type(exc).__name__}"
        return result

    if resp.status_code != 200:
        result["error"] = f"HTTP 状态码非 200：{resp.status_code}"
        return result

    # 编码处理：优先使用响应头声明，其次 apparent_encoding，最后 utf-8
    if not resp.encoding or resp.encoding.lower() == "iso-8859-1":
        resp.encoding = resp.apparent_encoding or "utf-8"

    try:
        html_text = resp.text
    except Exception:
        result["error"] = "响应内容解码失败"
        return result

    if not html_text or not html_text.strip():
        result["error"] = "响应内容为空"
        return result

    title = _extract_title(html_text)
    if not title:
        result["error"] = "页面中未找到 <title> 或标题为空"
        return result

    result["ok"] = True
    result["title"] = title
    return result


# ---------- 对外主函数 ----------
def fetch_titles(
    urls: Union[str, List[str]],
    timeout: float = DEFAULT_TIMEOUT,
) -> List[Dict[str, Any]]:
    """
    抓取一个或多个 URL 的页面标题。

    参数:
        urls: 单个 URL 字符串，或 URL 列表。
        timeout: 单次请求超时秒数（默认 10）。

    返回:
        与输入顺序对应的结果列表，每项为:
        {
            "url":   原始 URL,
            "ok":    bool，是否成功,
            "title": str|None，成功时为标题文本,
            "error": str|None，失败时为错误说明
        }
        传入空列表时返回空列表。
    """
    # 输入归一化
    if isinstance(urls, str):
        url_list = [urls]
    elif isinstance(urls, (list, tuple)):
        url_list = list(urls)
    else:
        raise TypeError("urls 必须是字符串或字符串列表")

    if not url_list:
        return []

    if not isinstance(timeout, (int, float)) or timeout <= 0:
        raise ValueError("timeout 必须为正数")

    return [_fetch_one(u, timeout) for u in url_list]


# ---------- 命令行入口 ----------
if __name__ == "__main__":
    import sys
    import json

    if len(sys.argv) < 2:
        print("用法: python fetch_titles.py <URL> [URL ...]")
        sys.exit(1)

    results = fetch_titles(sys.argv[1:])
    print(json.dumps(results, ensure_ascii=False, indent=2))
```

## 运行说明

### 依赖
- Python 3.8+
- `requests`（`pip install requests`）
- HTML 解析使用标准库 `html.parser`，无需额外安装

### 调用方式

**方式一：作为模块导入**

```python
from fetch_titles import fetch_titles

# 单个 URL
res = fetch_titles("https://example.com")
print(res[0]["title"])

# 多个 URL
res = fetch_titles(["https://example.com", "https://www.python.org"])
for item in res:
    print(item["url"], "->", item["title"] if item["ok"] else item["error"])
```

**方式二：命令行**

```bash
python fetch_titles.py https://example.com https://www.python.org
```

### 返回结果含义

返回一个列表，与输入顺序一一对应，每项为字典：

| 字段 | 类型 | 含义 |
|------|------|------|
| `url` | str | 原始传入的 URL |
| `ok` | bool | 是否成功获取标题 |
| `title` | str \| None | 成功时为标题文本（已 strip） |
| `error` | str \| None | 失败时为错误说明（不含敏感信息） |

### 行为约定

- **空列表**：返回 `[]`。
- **空字符串 / 非字符串 URL**：该项 `ok=False`，`error` 说明原因。
- **非 http(s) 协议**：拒绝并返回错误。
- **超时 / 连接失败 / SSL 失败 / 非 200**：返回对应错误，不抛异常。
- **无 `<title>` 或标题为空**：`ok=False`，`error="页面中未找到 <title> 或标题为空"`。
- **编码**：优先响应头声明；若为默认 `iso-8859-1` 则用 `apparent_encoding` 兜底，避免中文乱码。
- **异常信息**：只暴露异常类型名，不包含 URL 中的凭据或响应体内容。

## 测试用例

以下测试使用 `unittest` + `unittest.mock`，不依赖真实网络。

```python
# test_fetch_titles.py
import unittest
from unittest.mock import patch, Mock
import requests

from fetch_titles import fetch_titles, _extract_title


def _mock_response(status=200, text="", encoding="utf-8"):
    r = Mock()
    r.status_code = status
    r.text = text
    r.encoding = encoding
    r.apparent_encoding = "utf-8"
    return r


class TestExtractTitle(unittest.TestCase):
    def test_normal(self):
        self.assertEqual(_extract_title("<html><title>Hello</title></html>"), "Hello")

    def test_no_title(self):
        self.assertEqual(_extract_title("<html><body>x</body></html>"), "")

    def test_empty_title(self):
        self.assertEqual(_extract_title("<title>   </title>"), "")

    def test_entities(self):
        self.assertEqual(_extract_title("<title>A &amp; B</title>"), "A & B")


class TestFetchTitles(unittest.TestCase):
    @patch("fetch_titles.requests.get")
    def test_single_url_ok(self, mock_get):
        mock_get.return_value = _mock_response(text="<title>Example</title>")
        res = fetch_titles("https://example.com")
        self.assertEqual(len(res), 1)
        self.assertTrue(res[0]["ok"])
        self.assertEqual(res[0]["title"], "Example")

    @patch("fetch_titles.requests.get")
    def test_list_order_preserved(self, mock_get):
        mock_get.side_effect = [
            _mock_response(text="<title>A</title>"),
            _mock_response(text="<title>B</title>"),
        ]
        res = fetch_titles(["https://a.com", "https://b.com"])
        self.assertEqual([r["title"] for r in res], ["A", "B"])

    def test_empty_list(self):
        self.assertEqual(fetch_titles([]), [])

    def test_invalid_type(self):
        with self.assertRaises(TypeError):
            fetch_titles(123)

    def test_invalid_timeout(self):
        with self.assertRaises(ValueError):
            fetch_titles("https://example.com", timeout=0)

    def test_empty_url_string(self):
        res = fetch_titles("")
        self.assertFalse(res[0]["ok"])
        self.assertIn("空", res[0]["error"])

    def test_non_http_scheme(self):
        res = fetch_titles("ftp://example.com")
        self.assertFalse(res[0]["ok"])
        self.assertIn("http", res[0]["error"])

    @patch("fetch_titles.requests.get")
    def test_timeout(self, mock_get):
        mock_get.side_effect = requests.exceptions.Timeout()
        res = fetch_titles("https://example.com")
        self.assertFalse(res[0]["ok"])
        self.assertIn("超时", res[0]["error"])

    @patch("fetch_titles.requests.get")
    def test_connection_error(self, mock_get):
        mock_get.side_effect = requests.exceptions.ConnectionError()
        res = fetch_titles("https://example.com")
        self.assertFalse(res[0]["ok"])
        self.assertIn("连接失败", res[0]["error"])

    @patch("fetch_titles.requests.get")
    def test_non_200(self, mock_get):
        mock_get.return_value = _mock_response(status=404, text="not found")
        res = fetch_titles("https://example.com")
        self.assertFalse(res[0]["ok"])
        self.assertIn("404", res[0]["error"])

    @patch("fetch_titles.requests.get")
    def test_empty_body(self, mock_get):
        mock_get.return_value = _mock_response(text="")
        res = fetch_titles("https://example.com")
        self.assertFalse(res[0]["ok"])
        self.assertIn("为空", res[0]["error"])

    @patch("fetch_titles.requests.get")
    def test_no_title_tag(self, mock_get):
        mock_get.return_value = _mock_response(text="<html><body>hi</body></html>")
        res = fetch_titles("https://example.com")
        self.assertFalse(res[0]["ok"])
        self.assertIn("title", res[0]["error"])


if __name__ == "__main__":
    unittest.main()
```

运行测试：

```bash
python -m unittest test_fetch_titles.py -v
```

> **说明**：以上测试代码已编写，但**本次未实际执行**，因此不声称"测试通过"。请在本地运行确认。

## 关键依据与限制

- **已知事实**：`requests` 在响应头未声明编码时默认 `ISO-8859-1`，中文页面易乱码，故用 `apparent_encoding` 兜底。
- **假设**：调用方传入的是公开可访问的 http(s) URL；不处理需要登录/鉴权的页面。
- **限制**：仅提取第一个 `<title>`；不执行 JavaScript，SPA 页面可能取不到动态标题；不跟随 `meta refresh`。
- **安全**：代码不读取任何本地文件（含 `.env`、`*.pem`、`*.key`），错误信息仅含异常类型名，不输出响应体或请求头中的凭据。