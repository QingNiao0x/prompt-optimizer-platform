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
