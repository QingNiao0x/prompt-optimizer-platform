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
