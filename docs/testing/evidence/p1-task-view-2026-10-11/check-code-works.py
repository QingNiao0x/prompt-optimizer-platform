"""Check inspected generated code on synthetic inputs; never execute HTTP or __main__."""
import ast
import hashlib
import json
import random
import re
from pathlib import Path
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parent


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def definitions(path, extra=None):
    code = re.findall(r"```python\s*\n(.*?)```", path.read_text(encoding="utf-8"), re.S)[0]
    tree = ast.parse(code)
    declarations = []
    for node in tree.body:
        if isinstance(node, (ast.FunctionDef, ast.ClassDef)):
            declarations.append(node)
        elif isinstance(node, ast.ImportFrom) and node.module in ("html.parser", "bs4", "typing"):
            declarations.append(node)
        elif isinstance(node, ast.Import) and all(n.name in ("re", "argparse", "sys") for n in node.names):
            declarations.append(node)
        elif isinstance(node, ast.Assign):
            # Only literal configuration constants enter the test, never calls/loops/main/network.
            try:
                ast.literal_eval(node.value)
                declarations.append(node)
            except (ValueError, TypeError):
                pass
    forbidden = {"open", "eval", "exec", "__import__", "system", "popen"}
    for node in ast.walk(ast.Module(body=declarations, type_ignores=[])):
        if isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id in forbidden:
            raise RuntimeError("Unreviewed unsafe call")
    namespace = {"__name__": "generated_work_check", **(extra or {})}
    exec(compile(ast.Module(body=declarations, type_ignores=[]), str(path), "exec"), namespace)
    return namespace


def sorting_check(relative):
    path = ROOT / relative
    namespace = definitions(path)
    names = [n for n in namespace if n in ("quick_sort", "quicksort", "quickSort")]
    if len(names) != 1:
        raise RuntimeError("Expected one inspected quick-sort function")
    fn = namespace[names[0]]
    rng = random.Random(20261011)
    cases = [[], [1], [3, 1, 2], [2, 2, 1], [-3, 0, -1], list(range(100)), list(range(99, -1, -1)), [7] * 100]
    cases += [[rng.randint(-25, 25) for _ in range(rng.randint(0, 120))] for _ in range(100)]
    failures = []
    for i, values in enumerate(cases):
        before = values[:]
        output = fn(values)
        if output != sorted(before) or values != before:
            failures.append(i)
    return {"artifact": relative, "sha256": sha(path), "cases": len(cases), "failures": failures,
            "scope": "actual generated function, sorted oracle plus unchanged input; no performance proof"}


def title_check(relative):
    path = ROOT / relative
    state = {"html": "", "failure": False}
    class RequestError(Exception):
        pass
    class Response:
        status_code = 200
        encoding = "utf-8"
        apparent_encoding = "utf-8"
        headers = {"Content-Type": "text/html; charset=utf-8"}
        @property
        def text(self):
            return state["html"]
        def raise_for_status(self):
            if state["failure"]:
                raise RequestError("synthetic HTTP failure")
    def fake_get(*args, **kwargs):
        return Response()
    exceptions = SimpleNamespace(RequestException=RequestError, Timeout=RequestError, ConnectionError=RequestError)
    request = SimpleNamespace(get=fake_get, RequestException=RequestError, exceptions=exceptions)
    namespace = definitions(path, {"requests": request})
    fn = namespace.get("fetch_title") or namespace.get("get_page_title")
    kwargs = {} if fn.__name__ == "get_page_title" or "candidate" in relative else {"timeout": 10, "headers": {}, "encoding": None}
    samples = [("<html><title>中文标题</title></html>", "中文标题"),
               ("<TITLE>A &amp; B</TITLE>", "A & B"),
               ("<title>first</title><title>second</title>", "first"),
               ("<html><body>none</body></html>", None), ("<title></title>", None)]
    checks = []
    for html, expected in samples:
        state["html"] = html
        actual = fn("https://synthetic.invalid/title", **kwargs)
        checks.append({"expected": expected, "actual": actual, "passed": actual == expected or expected is None and actual == ""})
    return {"artifact": relative, "sha256": sha(path), "checks": checks,
            "scope": "inspected primary generated parser/function with a fake requests transport; no live website, CLI, timeout or encoding integration proof"}


def main():
    result = {"seed": 20261011, "networkCalls": 0, "sort": [], "title": []}
    for name in ["baseline-flash/QB-05-raw-work.md", "baseline-examples-corrected/QB-05-direct-work.md", "candidate-v2-flash/QB-05-direct-work.md"]:
        result["sort"].append(sorting_check(name))
    for name in ["baseline-flash/QB-04-raw-work.md", "baseline-flash/QB-04-direct-work.md", "candidate-v2-flash/QB-04-direct-work.md"]:
        result["title"].append(title_check(name))
    (ROOT / "code-work-checks.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"sortCases": sum(x["cases"] for x in result["sort"]), "sortFailures": sum(len(x["failures"]) for x in result["sort"]),
                      "titleChecks": sum(len(x["checks"]) for x in result["title"]), "titleFailures": sum(not c["passed"] for x in result["title"] for c in x["checks"])}, ensure_ascii=False))


if __name__ == "__main__":
    main()
