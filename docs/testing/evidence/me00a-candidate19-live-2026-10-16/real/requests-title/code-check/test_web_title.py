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
