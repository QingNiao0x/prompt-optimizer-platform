我来提供一个用 `requests` 库爬取网页标题的完整示例代码。

```python
import requests
from bs4 import BeautifulSoup

def get_page_title(url, timeout=10):
    """
    爬取指定网页的标题
    
    Args:
        url: 目标网页地址
        timeout: 请求超时时间（秒）
    
    Returns:
        网页标题字符串，失败时返回 None
    """
    headers = {
        "User-Agent": (
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            "AppleWebKit/537.36 (KHTML, like Gecko) "
            "Chrome/120.0.0.0 Safari/537.36"
        )
    }
    
    try:
        response = requests.get(url, headers=headers, timeout=timeout)
        response.raise_for_status()
        
        # 自动处理编码，避免中文乱码
        response.encoding = response.apparent_encoding
        
        soup = BeautifulSoup(response.text, "html.parser")
        title_tag = soup.find("title")
        
        if title_tag and title_tag.string:
            return title_tag.string.strip()
        return None
        
    except requests.exceptions.RequestException as e:
        print(f"请求失败: {e}")
        return None


if __name__ == "__main__":
    urls = [
        "https://www.python.org",
        "https://www.example.com",
    ]
    
    for url in urls:
        title = get_page_title(url)
        print(f"{url} -> {title}")
```

**关键点说明：**

1. **`requests.get()`**：发送 HTTP GET 请求，`headers` 中设置 `User-Agent` 模拟浏览器，避免被部分网站拒绝。

2. **`response.raise_for_status()`**：当返回状态码为 4xx/5xx 时抛出异常，便于统一处理错误。

3. **编码处理**：`response.apparent_encoding` 会根据内容自动推断编码，能有效避免中文网页乱码问题。

4. **解析标题**：使用 `BeautifulSoup` 定位 `<title>` 标签并提取文本。如果不想引入额外依赖，也可以用正则表达式替代：

```python
import re

def get_title_by_regex(html):
    match = re.search(r"<title[^>]*>(.*?)</title>", html, re.IGNORECASE | re.DOTALL)
    return match.group(1).strip() if match else None
```

**安装依赖：**

```bash
pip install requests beautifulsoup4
```

**注意事项：**

- 请遵守目标网站的 `robots.txt` 和使用条款，控制请求频率。
- 部分网站有反爬机制，可能需要额外处理（如 Cookie、验证码等）。
- 对于动态渲染（JavaScript 生成标题）的页面，`requests` 无法获取，需要改用 Selenium、Playwright 等工具。

如果你有具体的目标网站或需要批量爬取、并发加速等功能，可以告诉我，我再补充相应代码。