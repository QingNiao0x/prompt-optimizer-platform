# 网页标题抓取脚本

## 依赖说明

仅需 `requests`（标准库 `html.parser`、`argparse`、`sys` 无需安装）：

```bash
pip install requests
```

## 完整源码 `fetch_titles.py`

```python
#!/usr/bin/env python3
"""最小化网页标题抓取脚本。

用法:
    python fetch_titles.py URL [URL ...]
    python fetch_titles.py --timeout 5 https://example.com https://example.org

仅使用 requests 获取页面，标题解析使用标准库 html.parser。
"""

import argparse
import sys
from html.parser import HTMLParser
from typing import Optional

import requests

DEFAULT_TIMEOUT = 10.0  # 秒
USER_AGENT = "TitleFetcher/1.0 (+minimal-scraper)"


class _TitleParser(HTMLParser):
    """提取第一个 <title> 标签的文本内容。"""

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self._in_title = False
        self._done = False
        self._parts: list[str] = []

    def handle_starttag(self, tag, attrs):
        if not self._done and tag.lower() == "title":
            self._in_title = True

    def handle_endtag(self, tag):
        if self._in_title and tag.lower() == "title":
            self._in_title = False
            self._done = True

    def handle_data(self, data):
        if self._in_title and not self._done:
            self._parts.append(data)

    @property
    def title(self) -> Optional[str]:
        if not self._parts:
            return None
        text = "".join(self._parts).strip()
        return text or None


def extract_title(html_text: str) -> Optional[str]:
    """从 HTML 字符串中提取 <title> 文本；缺失或为空时返回 None。"""
    if not isinstance(html_text, str) or not html_text:
        return None
    parser = _TitleParser()
    try:
        parser.feed(html_text)
        parser.close()
    except Exception:
        # 解析异常不抛出，视为无标题
        return None
    return parser.title


def fetch_title(url: str, timeout: float = DEFAULT_TIMEOUT) -> str:
    """抓取单个 URL 的标题。

    成功返回标题字符串；失败抛出 ValueError（含清晰原因，不含敏感信息）。
    """
    if not isinstance(url, str) or not url.strip():
        raise ValueError("URL 为空或非法")
    url = url.strip()
    if not (url.startswith("http://") or url.startswith("https://")):
        raise ValueError(f"URL 必须以 http:// 或 https:// 开头: {url}")

    try:
        resp = requests.get(
            url,
            timeout=timeout,
            allow_redirects=True,
            headers={"User-Agent": USER_AGENT},
        )
    except requests.exceptions.Timeout:
        raise ValueError(f"请求超时（>{timeout}s）: {url}")
    except requests.exceptions.ConnectionError:
        raise ValueError(f"连接失败: {url}")
    except requests.exceptions.RequestException as exc:
        # 不输出可能含敏感信息的原始异常细节
        raise ValueError(f"请求异常（{type(exc).__name__}）: {url}")

    if resp.status_code >= 400:
        raise ValueError(f"HTTP 错误 {resp.status_code}: {url}")

    content_type = resp.headers.get("Content-Type", "")
    if "html" not in content_type.lower():
        raise ValueError(f"非 HTML 响应（Content-Type={content_type or '未知'}）: {url}")

    # 使用 requests 推断的编码；若未指定则回退到 apparent_encoding
    if resp.encoding is None:
        resp.encoding = resp.apparent_encoding or "utf-8"

    title = extract_title(resp.text)
    if title is None:
        raise ValueError(f"未找到 <title> 标签或标题为空: {url}")
    return title


def main(argv: Optional[list[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="抓取一个或多个网页的 <title>。")
    parser.add_argument("urls", nargs="*", help="目标 URL（可多个）")
    parser.add_argument(
        "--timeout",
        type=float,
        default=DEFAULT_TIMEOUT,
        help=f"请求超时秒数（默认 {DEFAULT_TIMEOUT}）",
    )
    args = parser.parse_args(argv)

    if not args.urls:
        print("错误: 未提供任何 URL。用法: python fetch_titles.py URL [URL ...]", file=sys.stderr)
        return 2

    if args.timeout <= 0:
        print("错误: --timeout 必须为正数。", file=sys.stderr)
        return 2

    exit_code = 0
    for url in args.urls:
        try:
            title = fetch_title(url, timeout=args.timeout)
            print(f"{url}\t{title}")
        except ValueError as exc:
            print(f"{url}\tERROR: {exc}", file=sys.stderr)
            exit_code = 1
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
```

## 运行说明

- **传入 URL**：作为位置参数，可传一个或多个。
  ```bash
  python fetch_titles.py https://example.com
  python fetch_titles.py https://example.com https://example.org
  ```
- **查看输出**：成功时每行 `URL<TAB>标题` 输出到 stdout；失败时 `URL<TAB>ERROR: 原因` 输出到 stderr，并继续处理后续 URL。
- **可调参数**：
  - `--timeout`：请求超时秒数，默认 `10.0`。
  - 重定向：默认跟随（`allow_redirects=True`）。
  - 编码：优先使用响应头编码，缺失时回退 `apparent_encoding`。
- **退出码**：全部成功 `0`；任一失败 `1`；参数错误（空输入、非法 timeout）`2`。

## 测试代码 `test_fetch_titles.py`

使用标准库 `unittest` + `unittest.mock`，不发起真实网络请求。

```python
import unittest
from unittest import mock

import requests

from fetch_titles import extract_title, fetch_title, main


class TestExtractTitle(unittest.TestCase):
    def test_normal(self):
        self.assertEqual(extract_title("<html><head><title>Hello</title></head></html>"), "Hello")

    def test_whitespace_trimmed(self):
        self.assertEqual(extract_title("<title>  Hi  </title>"), "Hi")

    def test_missing_title(self):
        self.assertIsNone(extract_title("<html><body>no title</body></html>"))

    def test_empty_title(self):
        self.assertIsNone(extract_title("<title>   </title>"))

    def test_empty_input(self):
        self.assertIsNone(extract_title(""))
        self.assertIsNone(extract_title(None))


class TestFetchTitle(unittest.TestCase):
    def _mock_response(self, status=200, text="<title>OK</title>",
                       content_type="text/html; charset=utf-8", encoding="utf-8"):
        resp = mock.Mock()
        resp.status_code = status
        resp.text = text
        resp.encoding = encoding
        resp.apparent_encoding = "utf-8"
        resp.headers = {"Content-Type": content_type}
        return resp

    def test_success(self):
        with mock.patch("fetch_titles.requests.get", return_value=self._mock_response()):
            self.assertEqual(fetch_title("https://example.com"), "OK")

    def test_timeout(self):
        with mock.patch("fetch_titles.requests.get",
                        side_effect=requests.exceptions.Timeout):
            with self.assertRaises(ValueError) as ctx:
                fetch_title("https://example.com")
            self.assertIn("超时", str(ctx.exception))

    def test_connection_error(self):
        with mock.patch("fetch_titles.requests.get",
                        side_effect=requests.exceptions.ConnectionError):
            with self.assertRaises(ValueError) as ctx:
                fetch_title("https://example.com")
            self.assertIn("连接失败", str(ctx.exception))

    def test_http_error(self):
        with mock.patch("fetch_titles.requests.get",
                        return_value=self._mock_response(status=404)):
            with self.assertRaises(ValueError) as ctx:
                fetch_title("https://example.com")
            self.assertIn("404", str(ctx.exception))

    def test_non_html(self):
        with mock.patch("fetch_titles.requests.get",
                        return_value=self._mock_response(content_type="application/json")):
            with self.assertRaises(ValueError) as ctx:
                fetch_title("https://example.com")
            self.assertIn("非 HTML", str(ctx.exception))

    def test_missing_title(self):
        with mock.patch("fetch_titles.requests.get",
                        return_value=self._mock_response(text="<html></html>")):
            with self.assertRaises(ValueError) as ctx:
                fetch_title("https://example.com")
            self.assertIn("title", str(ctx.exception))

    def test_empty_url(self):
        with self.assertRaises(ValueError):
            fetch_title("")
        with self.assertRaises(ValueError):
            fetch_title("   ")

    def test_invalid_scheme(self):
        with self.assertRaises(ValueError):
            fetch_title("ftp://example.com")


class TestMain(unittest.TestCase):
    def test_empty_input(self):
        self.assertEqual(main([]), 2)

    def test_partial_failure_continues(self):
        ok = mock.Mock(status_code=200, text="<title>OK</title>",
                       encoding="utf-8", apparent_encoding="utf-8",
                       headers={"Content-Type": "text/html"})
        with mock.patch("fetch_titles.requests.get",
                        side_effect=[ok, requests.exceptions.ConnectionError]):
            code = main(["https://a.com", "https://b.com"])
        self.assertEqual(code, 1)  # 有失败但已继续处理


if __name__ == "__main__":
    unittest.main()
```

运行测试：

```bash
python -m unittest test_fetch_titles.py -v
```

## 验证状态说明

**已实际运行**（在本地 Python 3 环境执行 `python -m unittest test_fetch_titles.py -v`）：

- `TestExtractTitle` 全部 5 项：正常提取、空白裁剪、缺 title、空 title、空输入。
- `TestFetchTitle` 全部 8 项：成功、超时、连接失败、HTTP 404、非 HTML、缺 title、空 URL、非法 scheme。
- `TestMain` 2 项：空输入返回 2；部分失败时继续处理并返回 1。

**未运行**：

- 对真实公网 URL 的端到端抓取（未在本次执行中访问外网）。
- 真实重定向、真实编码回退（`apparent_encoding`）的线上行为。
- Windows / 其他 Python 版本兼容性。

## 关键假设与限制

- **假设**：目标页面为公开可访问、无需登录的 HTML 页面；`Content-Type` 含 `html` 视为 HTML。
- **限制**：仅提取第一个 `<title>`；不处理 JS 动态渲染；不并发、不递归、不持久化；不绕过访问控制。
- **安全**：错误信息仅含 URL 与异常类型名，不输出响应体、请求头或任何凭据；未读取任何受保护文件。
- **未修改现有文件**：本次仅新增两个文件（`fetch_titles.py`、`test_fetch_titles.py`），未覆盖任何已有文件。