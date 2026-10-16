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
