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
