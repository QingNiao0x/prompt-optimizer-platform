import requests
from bs4 import BeautifulSoup
from urllib.parse import urlparse

def get_title(url, timeout=10):
    headers = {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) '
                      'AppleWebKit/537.36 (KHTML, like Gecko) '
                      'Chrome/120.0.0.0 Safari/537.36'
    }
    try:
        resp = requests.get(url, headers=headers, timeout=timeout)
        resp.raise_for_status()
        resp.encoding = resp.apparent_encoding
        soup = BeautifulSoup(resp.text, 'html.parser')
        return soup.title.string.strip() if soup.title else '无标题'
    except Exception as e:
        return f'错误: {e}'


urls = [
    'https://www.python.org',
    'https://www.baidu.com',
    'https://github.com',
]

for url in urls:
    print(f'{url} -> {get_title(url)}')
