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
