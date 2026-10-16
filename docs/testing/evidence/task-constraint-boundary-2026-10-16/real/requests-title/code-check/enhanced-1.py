import sys
import requests
from bs4 import BeautifulSoup

HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/122.0.0.0 Safari/537.36"
    )
}
TIMEOUT = 10


def fetch_title(url: str) -> str:
    """抓取单个 URL 的 title 文本；失败时返回带说明的字符串。"""
    try:
        resp = requests.get(url, headers=HEADERS, timeout=TIMEOUT)
        resp.raise_for_status()
    except requests.RequestException as e:
        return f"[请求失败] {e}"

    content_type = resp.headers.get("Content-Type", "")
    if "html" not in content_type.lower():
        return f"[非 HTML 响应] Content-Type: {content_type or '未知'}"

    # 常规做法：优先使用响应声明的编码，其次使用 apparent_encoding
    if not resp.encoding or resp.encoding.lower() == "iso-8859-1":
        resp.encoding = resp.apparent_encoding or "utf-8"

    soup = BeautifulSoup(resp.text, "html.parser")
    if soup.title and soup.title.string:
        return soup.title.string.strip()
    return "[无 title 标签]"


def main(urls):
    for url in urls:
        title = fetch_title(url)
        print(f"{url}\t{title}")


if __name__ == "__main__":
    # 默认处理方式：从命令行参数读取 URL；未提供时使用示例 URL
    urls = sys.argv[1:]
    if not urls:
        urls = ["https://www.example.com"]
    main(urls)
