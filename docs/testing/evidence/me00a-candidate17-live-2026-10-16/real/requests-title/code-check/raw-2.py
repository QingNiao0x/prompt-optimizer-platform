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
