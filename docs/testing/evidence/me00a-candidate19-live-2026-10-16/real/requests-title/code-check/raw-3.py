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
