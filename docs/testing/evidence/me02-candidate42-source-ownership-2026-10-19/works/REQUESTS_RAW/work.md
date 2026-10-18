# 使用 requests 库爬取网页标题

下面是一个完整的示例，演示如何使用 `requests` 库配合 `BeautifulSoup` 爬取网页标题。

## 安装依赖

```bash
pip install requests beautifulsoup4
```

## 基础示例

```python
import requests
from bs4 import BeautifulSoup

def get_page_title(url):
    """爬取网页标题"""
    try:
        # 设置请求头，模拟浏览器访问
        headers = {
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) '
                          'AppleWebKit/537.36 (KHTML, like Gecko) '
                          'Chrome/120.0.0.0 Safari/537.36'
        }
        
        # 发送 GET 请求
        response = requests.get(url, headers=headers, timeout=10)
        response.raise_for_status()  # 检查 HTTP 状态码
        
        # 自动识别编码（避免中文乱码）
        response.encoding = response.apparent_encoding
        
        # 解析 HTML
        soup = BeautifulSoup(response.text, 'html.parser')
        
        # 获取 <title> 标签内容
        title = soup.title.string.strip() if soup.title else "无标题"
        return title
        
    except requests.exceptions.RequestException as e:
        return f"请求失败: {e}"


if __name__ == '__main__':
    urls = [
        'https://www.python.org',
        'https://www.baidu.com',
        'https://www.github.com',
    ]
    
    for url in urls:
        title = get_page_title(url)
        print(f"{url} -> {title}")
```

## 输出示例

```
https://www.python.org -> Welcome to Python.org
https://www.baidu.com -> 百度一下，你就知道
https://www.github.com -> GitHub · Build and ship software on a single, collaborative platform
```

## 关键点说明

| 要点 | 说明 |
|------|------|
| **User-Agent** | 不加会被很多网站拒绝（返回 403） |
| **timeout** | 防止请求卡死，建议设置 5~10 秒 |
| **raise_for_status()** | 主动抛出 4xx/5xx 异常，便于排查 |
| **apparent_encoding** | 自动检测编码，避免中文乱码 |
| **soup.title.string** | 获取 `<title>` 标签内的文本 |

## 进阶：使用正则表达式（不依赖 BeautifulSoup）

如果不想安装额外库，也可以用正则提取：

```python
import re
import requests

def get_title_by_regex(url):
    headers = {'User-Agent': 'Mozilla/5.0'}
    try:
        r = requests.get(url, headers=headers, timeout=10)
        r.encoding = r.apparent_encoding
        match = re.search(r'<title[^>]*>(.*?)</title>', r.text, re.S | re.I)
        return match.group(1).strip() if match else "无标题"
    except Exception as e:
        return f"错误: {e}"

print(get_title_by_regex('https://www.python.org'))
```

## 注意事项

1. **遵守 robots.txt**：爬取前查看目标网站的爬虫协议。
2. **控制频率**：批量爬取时加 `time.sleep(1)` 避免给对方服务器造成压力。
3. **动态网页**：如果标题由 JavaScript 渲染（如 SPA 应用），`requests` 拿不到，需要改用 `Selenium` 或 `Playwright`。
4. **反爬机制**：部分网站会检测 UA、Cookie、IP 等，必要时需配合代理或会话（`requests.Session()`）。

如果需要爬取**动态渲染页面**或**批量并发爬取**的版本，可以告诉我，我再补充示例。