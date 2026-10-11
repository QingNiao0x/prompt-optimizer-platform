# 用 Python requests 库爬取网页标题

下面是一个完整的示例，演示如何使用 `requests` 库获取网页内容，并解析出网页标题。

## 基础版本

```python
import requests
from bs4 import BeautifulSoup


def get_page_title(url: str) -> str:
    """获取网页标题"""
    headers = {
        "User-Agent": (
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            "AppleWebKit/537.36 (KHTML, like Gecko) "
            "Chrome/120.0.0.0 Safari/537.36"
        )
    }

    response = requests.get(url, headers=headers, timeout=10)
    response.raise_for_status()  # 非 2xx 状态码时抛出异常

    # 让 requests 根据响应头/内容自动推断编码，避免中文乱码
    response.encoding = response.apparent_encoding

    soup = BeautifulSoup(response.text, "html.parser")
    title_tag = soup.find("title")
    return title_tag.get_text(strip=True) if title_tag else ""


if __name__ == "__main__":
    url = "https://www.example.com"
    print(get_page_title(url))
```

## 关键点说明

1. **设置 User-Agent**
   很多网站会拒绝没有 UA 的请求（返回 403）。加上浏览器 UA 可以显著提高成功率。

2. **设置 timeout**
   避免请求卡死。建议 5~15 秒。

3. **`raise_for_status()`**
   遇到 4xx / 5xx 时直接抛异常，方便定位问题。

4. **编码处理**
   `response.apparent_encoding` 会基于内容猜测编码，比默认的 `response.encoding` 更可靠，能避免中文标题乱码。

5. **解析标题**
   用 `BeautifulSoup` 找到 `<title>` 标签并取文本。如果页面没有 `<title>`，返回空字符串。

## 不依赖 BeautifulSoup 的版本

如果不想引入额外依赖，可以用正则：

```python
import re
import requests


def get_page_title(url: str) -> str:
    headers = {"User-Agent": "Mozilla/5.0"}
    response = requests.get(url, headers=headers, timeout=10)
    response.raise_for_status()
    response.encoding = response.apparent_encoding

    match = re.search(
        r"<title[^>]*>(.*?)</title>",
        response.text,
        re.IGNORECASE | re.DOTALL,
    )
    return match.group(1).strip() if match else ""
```

> 注意：正则解析 HTML 在遇到嵌套、注释、属性换行等复杂情况时容易出错，生产环境建议用 `BeautifulSoup` 或 `lxml`。

## 批量爬取示例

```python
import time
import requests
from bs4 import BeautifulSoup

HEADERS = {"User-Agent": "Mozilla/5.0"}


def get_page_title(url: str) -> str:
    try:
        resp = requests.get(url, headers=HEADERS, timeout=10)
        resp.raise_for_status()
        resp.encoding = resp.apparent_encoding
        soup = BeautifulSoup(resp.text, "html.parser")
        tag = soup.find("title")
        return tag.get_text(strip=True) if tag else ""
    except requests.RequestException as e:
        return f"[ERROR] {e}"


urls = [
    "https://www.example.com",
    "https://www.python.org",
]

for u in urls:
    print(u, "->", get_page_title(u))
    time.sleep(1)  # 礼貌性延时，避免给服务器造成压力
```

## 注意事项

- **遵守 robots.txt 与网站条款**：爬取前确认目标站点允许抓取。
- **控制频率**：加 `time.sleep`，避免高频请求。
- **异常处理**：网络请求可能超时、被拒、返回非 HTML 内容，务必用 `try/except` 包裹。
- **动态页面**：如果标题由 JavaScript 渲染（如 SPA），`requests` 拿到的 HTML 里可能没有 `<title>`，此时需要改用 `playwright` / `selenium` 等工具。

## 依赖安装

```bash
pip install requests beautifulsoup4
```

以上代码可直接运行，把 `url` 换成你想抓取的地址即可。