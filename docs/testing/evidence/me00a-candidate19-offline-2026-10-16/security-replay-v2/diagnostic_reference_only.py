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
        raise ValueError("invalid url: expected http(s) URL")
    return url


def fetch_title(
    url: str,
    *,
    timeout: tuple = DEFAULT_TIMEOUT,
    user_agent: str = DEFAULT_UA,
    session: Optional[requests.Session] = None,
) -> dict:
    url = _validate_url(url)
    result = {"url": _display_url(url), "status": None, "title": None, "error": None}
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
            results.append({"url": _display_url(u), "status": None,
                            "title": None, "error": f"invalid_url: {exc}"})
        if delay and i < len(url_list) - 1:
            time.sleep(delay)

    if as_json:
        return json.dumps(results, ensure_ascii=False, indent=2)
    return results


def _display_url(value):
    """Reference locator redaction; does not claim to detect arbitrary secrets or redact HTML titles."""
    import re
    from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit
    if not isinstance(value, str):
        return "<invalid-url>"
    try:
        parsed = urlsplit(value)
        keys = {"token", "accesstoken", "refreshtoken", "apikey", "password", "secret", "authorization"}
        pairs = parse_qsl(parsed.query, keep_blank_values=True)
        sensitive = lambda key: re.sub(r"[-_]", "", key).casefold() in keys
        auth = "@" in parsed.netloc
        if not auth and not any(sensitive(key) for key, _ in pairs):
            return value
        query = urlencode([(key, "[REDACTED]" if sensitive(key) else item) for key, item in pairs])
        return urlunsplit((parsed.scheme, parsed.netloc.rsplit("@", 1)[-1], parsed.path, query, parsed.fragment))
    except (ValueError, TypeError):
        return "<invalid-url>"
