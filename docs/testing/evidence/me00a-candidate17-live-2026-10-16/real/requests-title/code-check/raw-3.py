import re
import requests

def get_title(url):
    headers = {'User-Agent': 'Mozilla/5.0'}
    resp = requests.get(url, headers=headers, timeout=10)
    resp.encoding = resp.apparent_encoding
    match = re.search(r'<title[^>]*>(.*?)</title>', resp.text, re.S | re.I)
    return match.group(1).strip() if match else '无标题'

print(get_title('https://www.python.org'))
