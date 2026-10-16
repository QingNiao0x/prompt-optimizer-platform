# 使用 requests 库爬取网页标题

下面是一个完整的示例，演示如何使用 `requests` 库配合 `BeautifulSoup` 爬取网页标题。

## 基础版本

```python
import requests
from bs4 import BeautifulSoup

def get_page_title(url):
    """获取网页标题"""
    try:
        # 发送 GET 请求
        headers = {
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) '
                          'AppleWebKit/537.36 (KHTML, like Gecko) '
                          'Chrome/120.0.0.0 Safari/537.36'
        }
        response = requests.get(url, headers=headers, timeout=10)
        response.raise_for_status()  # 检查 HTTP 错误
        
        # 自动处理编码
        response.encoding = response.apparent_encoding
        
        # 解析 HTML
        soup = BeautifulSoup(response.text, 'html.parser')
        title = soup.title.string.strip() if soup.title else '无标题'
        return title
        
    except requests.exceptions.RequestException as e:
        return f'请求失败: {e}'


if __name__ == '__main__':
    url = 'https://www.python.org'
    print(f'网页标题: {get_page_title(url)}')
```

## 批量爬取多个网页

```python
import requests
from bs4 import BeautifulSoup
from concurrent.futures import ThreadPoolExecutor

HEADERS = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) '
                  'AppleWebKit/537.36 (KHTML, like Gecko) '
                  'Chrome/120.0.0.0 Safari/537.36'
}

def fetch_title(url):
    try:
        resp = requests.get(url, headers=HEADERS, timeout=10)
        resp.raise_for_status()
        resp.encoding = resp.apparent_encoding
        soup = BeautifulSoup(resp.text, 'html.parser')
        return url, soup.title.string.strip() if soup.title else '无标题'
    except Exception as e:
        return url, f'错误: {e}'


if __name__ == '__main__':
    urls = [
        'https://www.python.org',
        'https://www.baidu.com',
        'https://github.com',
        'https://www.zhihu.com',
    ]
    
    # 使用线程池并发爬取
    with ThreadPoolExecutor(max_workers=5) as executor:
        for url, title in executor.map(fetch_title, urls):
            print(f'{url}\n  → {title}\n')
```

## 关键点说明

| 要点 | 说明 |
|------|------|
| **User-Agent** | 伪装成浏览器，避免被网站拒绝 |
| **timeout** | 设置超时，防止程序卡死 |
| **raise_for_status()** | 遇到 4xx/5xx 状态码时抛出异常 |
| **apparent_encoding** | 自动识别网页编码，避免中文乱码 |
| **BeautifulSoup** | 解析 HTML，提取 `<title>` 标签内容 |

## 仅用 requests + 正则（不依赖 bs4）

如果不想安装 `BeautifulSoup`，可以用正则：

```python
import re
import requests

def get_title(url):
    headers = {'User-Agent': 'Mozilla/5.0'}
    resp = requests.get(url, headers=headers, timeout=10)
    resp.encoding = resp.apparent_encoding
    match = re.search(r'<title[^>]*>(.*?)</title>', resp.text, re.S | re.I)
    return match.group(1).strip() if match else '无标题'

print(get_title('https://www.python.org'))
```

## 安装依赖

```bash
pip install requests beautifulsoup4
```

## 注意事项

1. **遵守 robots.txt**：爬取前查看目标网站的爬虫协议
2. **控制频率**：加 `time.sleep()` 避免给服务器造成压力
3. **合法合规**：仅用于学习研究，不要爬取敏感或受版权保护的内容
4. **异常处理**：网络请求容易失败，务必做好异常捕获

需要我扩展某个方向吗？比如**异步爬取（aiohttp）**、**保存到 CSV**、或**处理 JavaScript 渲染的页面（Selenium/Playwright）**。