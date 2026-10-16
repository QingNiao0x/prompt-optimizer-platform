import argparse
import sys

from .exceptions import FetchError
from .fetcher import fetch_page_title


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="抓取网页 <title>")
    parser.add_argument("url", help="目标 URL，例如 https://example.com")
    parser.add_argument("--connect-timeout", type=float, default=5.0)
    parser.add_argument("--read-timeout", type=float, default=10.0)
    args = parser.parse_args(argv)

    try:
        title = fetch_page_title(
            args.url,
            timeout=(args.connect_timeout, args.read_timeout),
        )
    except FetchError as exc:
        print(f"[ERROR] {exc}", file=sys.stderr)
        return 1

    print(title)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
