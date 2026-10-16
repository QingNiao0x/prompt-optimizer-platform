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
