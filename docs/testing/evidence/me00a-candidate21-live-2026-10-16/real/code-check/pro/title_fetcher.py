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
