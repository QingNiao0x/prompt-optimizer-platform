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
