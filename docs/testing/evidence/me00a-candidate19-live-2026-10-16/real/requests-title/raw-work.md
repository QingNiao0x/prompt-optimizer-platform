# 使用 requests 库爬取网页标题

下面是一个完整的示例，演示如何使用 `requests` 库配合 `BeautifulSoup` 爬取网页标题。

## 基础版本

```python
import requests
from bs4 import BeautifulSoup

def get_page_title(url):
    """爬取指定网页的标题"""
    try:
        # 发送请求，设置超时和请求头
        headers = {
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) '
                          'AppleWebKit/537.36 (KHTML, like Gecko) '
                          'Chrome/120.0.0.0 Safari/537.36'
        }
        response = requests.get(url, headers=headers, timeout=10)
        response.raise_for_status()  # 检查 HTTP 错误
        
        # 自动处理编码（避免中文乱码）
        response.encoding = response.apparent_encoding
        
        # 解析 HTML
        soup = BeautifulSoup(response.text, 'html.parser')
        title = soup.title.string.strip() if soup.title else '无标题'
        return title
    
    except requests.exceptions.RequestException as e:
        return f'请求失败: {e}'


if __name__ == '__main__':
    urls = [
        'https://www.baidu.com',
        'https://www.python.org',
        'https://www.github.com',
    ]
    
    for url in urls:
        title = get_page_title(url)
        print(f'{url} -> {title}')
```

## 只用 requests + 正则（不依赖 BeautifulSoup）

如果你不想安装 `bs4`，可以用正则表达式提取标题：

```python
import re
import requests

def get_title_by_regex(url):
    headers = {'User-Agent': 'Mozilla/5.0'}
    try:
        resp = requests.get(url, headers=headers, timeout=10)
        resp.encoding = resp.apparent_encoding
        # 匹配 <title>...</title>，忽略大小写和换行
        match = re.search(r'<title[^>]*>(.*?)</title>', resp.text, re.S | re.I)
        return match.group(1).strip() if match else '无标题'
    except requests.RequestException as e:
        return f'请求失败: {e}'


print(get_title_by_regex('https://www.python.org'))
```

## 关键点说明

| 要点 | 说明 |
|------|------|
| **User-Agent** | 很多网站会拒绝没有 UA 的请求，务必加上 |
| **timeout** | 防止请求卡死，建议设置 5~10 秒 |
| **raise_for_status()** | 遇到 4xx/5xx 状态码时抛出异常，便于排查 |
| **apparent_encoding** | 自动识别编码，避免中文乱码 |
| **异常处理** | 网络请求容易失败，一定要用 try/except 包裹 |

## 批量爬取（带并发）

如果 URL 很多，可以用线程池加速：

```python
import requests
from bs4 import BeautifulSoup
from concurrent.futures import ThreadPoolExecutor

HEADERS = {'User-Agent': 'Mozilla/5.0'}

def fetch_title(url):
    try:
        r = requests.get(url, headers=HEADERS, timeout=10)
        r.encoding = r.apparent_encoding
        soup = BeautifulSoup(r.text, 'html.parser')
        return url, soup.title.string.strip() if soup.title else '无标题'
    except Exception as e:
        return url, f'失败: {e}'

urls = ['https://www.baidu.com', 'https://www.python.org', 'https://www.github.com']

with ThreadPoolExecutor(max_workers=5) as pool:
    for url, title in pool.map(fetch_title, urls):
        print(f'{url} -> {title}')
```

## 安装依赖

```bash
pip install requests beautifulsoup4
```

## 注意事项

1. **遵守 robots.txt**：爬取前查看目标网站的爬虫协议
2. **控制频率**：不要高频请求，避免给服务器造成压力
3. **合法合规**：仅用于学习研究，不要爬取敏感或受版权保护的内容
4. **动态网页**：如果标题由 JavaScript 渲染（如 SPA 应用），`requests` 拿不到，需要用 `selenium` 或 `playwright`

需要我进一步演示某个具体网站，或者讲解如何处理动态渲染页面吗？