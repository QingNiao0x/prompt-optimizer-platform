以下代码按“网址未提供”的前提编写：`TARGET_URL` 是占位变量，需要你替换为实际目标网址后再运行。代码不假设任何具体站点、页面结构或反爬规则。

```python
import requests
from html.parser import HTMLParser


class TitleParser(HTMLParser):
    """用标准库 html.parser 提取 <title> 文本，不依赖第三方解析库。"""

    def __init__(self):
        super().__init__()
        self._in_title = False
        self.title_parts = []

    def handle_starttag(self, tag, attrs):
        if tag.lower() == "title":
            self._in_title = True

    def handle_endtag(self, tag):
        if tag.lower() == "title":
            self._in_title = False

    def handle_data(self, data):
        if self._in_title:
            self.title_parts.append(data)

    def get_title(self):
        title = "".join(self.title_parts).strip()
        return title or None


def fetch_title(url, timeout=10):
    """
    使用 requests 获取网页 HTML，并提取 <title>。
    返回 (title, error_message)：
      - 成功且有标题：返回 (标题, None)
      - 成功但无标题：返回 (None, "未找到 <title> 标签或标题为空")
      - 失败：返回 (None, 错误说明)
    """
    headers = {
        # 使用常见浏览器 UA，仅用于提高一般站点的可访问性；
        # 不假设目标站点一定有反爬策略。
        "User-Agent": (
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            "AppleWebKit/537.36 (KHTML, like Gecko) "
            "Chrome/120.0 Safari/537.36"
        )
    }

    try:
        response = requests.get(url, headers=headers, timeout=timeout)
    except requests.exceptions.Timeout:
        return None, f"请求超时（超过 {timeout} 秒）"
    except requests.exceptions.ConnectionError:
        return None, "连接错误：无法连接到目标主机"
    except requests.exceptions.RequestException as exc:
        return None, f"请求异常：{exc}"

    if response.status_code != 200:
        return None, f"非 200 响应，状态码：{response.status_code}"

    # 让 requests 根据响应头或内容推断编码；若推断不准，可手动指定。
    response.encoding = response.apparent_encoding or response.encoding

    parser = TitleParser()
    try:
        parser.feed(response.text)
    except Exception as exc:
        return None, f"解析 HTML 失败：{exc}"

    title = parser.get_title()
    if title is None:
        return None, "未找到 <title> 标签或标题为空"

    return title, None


if __name__ == "__main__":
    # 占位网址：请替换为你要爬取的实际网址。
    # 这里不使用任何真实站点，避免把未确认信息当作事实。
    TARGET_URL = "https://example.com/"

    title, error = fetch_title(TARGET_URL)

    if error:
        print(f"获取标题失败：{error}")
    else:
        print(f"网页标题：{title}")
```

关键步骤说明：

1. **发起请求**：用 `requests.get(url, headers=headers, timeout=timeout)` 获取 HTML。`timeout` 避免请求无限等待。
2. **异常处理**：分别捕获超时、连接错误和其他 `RequestException`，并检查 `status_code` 是否为 200。
3. **编码处理**：用 `response.apparent_encoding` 推断编码，减少中文等非 ASCII 页面乱码的可能。
4. **标题提取**：使用标准库 `html.parser.HTMLParser` 解析 `<title>`，无需额外安装解析库。
5. **标题缺失处理**：若没有 `<title>` 或标题为空，返回明确的错误说明。

依赖说明：

- 必须安装 `requests`：`pip install requests`
- HTML 解析使用 Python 标准库 `html.parser`，无需额外安装。

需要确认的信息：

- 实际目标网址：代码中的 `TARGET_URL` 是占位符，需你替换。
- 目标页面结构、编码、是否有反爬限制：这些信息未提供，代码未作任何假设。若目标站点有特殊反爬机制，可能需要你根据实际情况调整请求头、Cookie、代理或请求频率等。