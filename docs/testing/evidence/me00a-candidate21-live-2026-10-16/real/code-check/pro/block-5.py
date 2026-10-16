if __name__ == "__main__":
    import sys

    if len(sys.argv) < 2:
        print("用法: python title_fetcher.py <URL> [timeout]", file=sys.stderr)
        sys.exit(1)

    target_url = sys.argv[1]
    request_timeout = float(sys.argv[2]) if len(sys.argv) > 2 else DEFAULT_TIMEOUT

    try:
        result = fetch_title(target_url, timeout=request_timeout)
        print(result)
    except TitleFetchError as exc:
        print(f"错误: {exc}", file=sys.stderr)
        sys.exit(1)
