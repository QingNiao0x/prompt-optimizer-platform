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
