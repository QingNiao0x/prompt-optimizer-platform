from html.parser import HTMLParser


class _TitleParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self._in_title = False
        self._chunks: list[str] = []

    def handle_starttag(self, tag, attrs):
        if tag.lower() == "title":
            self._in_title = True

    def handle_endtag(self, tag):
        if tag.lower() == "title":
            self._in_title = False

    def handle_data(self, data):
        if self._in_title:
            self._chunks.append(data)

    @property
    def title(self) -> str | None:
        text = "".join(self._chunks).strip()
        return text or None


def extract_title(html: str) -> str | None:
    """从 HTML 文本中提取 <title> 内容；无标题返回 None。"""
    if not html:
        return None
    parser = _TitleParser()
    try:
        parser.feed(html)
        parser.close()
    except Exception:
        # html.parser 对畸形 HTML 通常不抛异常，此处仅作兜底
        return None
    return parser.title
