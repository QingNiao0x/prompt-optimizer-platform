def fetch_page_title(
    url: str,
    *,
    timeout: tuple[float, float] = (5.0, 10.0),
    session: requests.Session | None = None,
    headers: dict[str, str] | None = None,
) -> str
