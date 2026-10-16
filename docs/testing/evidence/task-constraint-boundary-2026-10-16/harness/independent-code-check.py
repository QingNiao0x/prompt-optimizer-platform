"""Compile verbatim model implementations and independently exercise local-only inputs.

Top-level external-site demonstrations are not executed. An explicit AST loader keeps
imports, original function definitions and literal configuration only. This is a
function-behaviour check, not evidence that every generated CLI demonstration ran.
"""
import ast
import contextlib
import hashlib
import http.server
import io
import json
from pathlib import Path
import py_compile
import re
import sys
import threading
import unittest
from unittest.mock import patch
from urllib.parse import urlparse

import requests

ROOT = Path(__file__).resolve().parents[5]
WORKS = ROOT / "tmp/me00a-candidate16-20261016/real/requests-title"
OUT = WORKS / "code-check"
OUT.mkdir(exist_ok=True)
IMPLEMENTATIONS = []
COMPILED = []

for arm in ("raw", "enhanced"):
    work = (WORKS / f"{arm}-work.md").read_text(encoding="utf-8")
    blocks = re.findall(r"```python\s*\n(.*?)```", work, flags=re.S)
    for number, block in enumerate(blocks, 1):
        path = OUT / f"{arm}-{number}.py"
        path.write_text(block, encoding="utf-8", newline="")
        py_compile.compile(str(path), doraise=True)
        tree = ast.parse(block, filename=str(path))
        kept, omitted = [], []
        for node in tree.body:
            keep = isinstance(node, (ast.Import, ast.ImportFrom, ast.FunctionDef))
            if isinstance(node, ast.Assign) and all(
                isinstance(target, ast.Name) and target.id in {"HEADERS", "TIMEOUT"}
                for target in node.targets
            ):
                # Only literal constants: no assignment may run generated calls.
                ast.literal_eval(node.value)
                keep = True
            (kept if keep else omitted).append(node)
        module = ast.Module(body=kept, type_ignores=[])
        namespace = {"__name__": "model_work_under_test"}
        exec(compile(module, str(path), "exec"), namespace)
        fn = "get_title_regex" if "get_title_regex" in namespace else (
            "fetch_title" if "fetch_title" in namespace else "get_title"
        )
        entry = {"arm": arm, "number": number, "namespace": namespace, "function": fn}
        IMPLEMENTATIONS.append(entry)
        COMPILED.append({"file": path.name, "sha256": hashlib.sha256(block.encode()).hexdigest(),
                         "compiled": True, "omittedTopLevelNodeLines": [node.lineno for node in omitted]})


class FixtureHandler(http.server.BaseHTTPRequestHandler):
    """Serve synthetic HTML and errors without making external requests."""

    def do_GET(self):
        status, kind, body = 200, "text/html; charset=utf-8", ""
        if self.path == "/normal":
            body = "<html><title>Local title</title><body>Local fixture</body></html>"
        elif self.path == "/utf8":
            body = "<html><title>本机测试标题</title><body>" + "这是本机编码验证文本。" * 20 + "</body></html>"
        elif self.path == "/empty":
            body = "<html><title></title><body>Empty title</body></html>"
        elif self.path == "/absent":
            body = "<html><body>No title</body></html>"
        elif self.path == "/entity":
            body = "<html><title>A &amp; B</title><body>Entity fixture</body></html>"
        elif self.path == "/json":
            kind, body = "application/json", '{"title":"JSON fixture"}'
        else:
            status, body = 503, "<html><title>Upstream error</title></html>"
        data = body.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", kind)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *_):
        pass


server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), FixtureHandler)
threading.Thread(target=server.serve_forever, daemon=True).start()
BASE = f"http://127.0.0.1:{server.server_port}"
real_get = requests.get


def guarded_get(url, *args, **kwargs):
    """Generated function I/O is restricted to this synthetic loopback server."""
    parsed = urlparse(url)
    if parsed.scheme != "http" or parsed.hostname != "127.0.0.1" or parsed.port != server.server_port:
        raise AssertionError("External I/O attempted by generated code")
    return real_get(url, *args, **kwargs)


def invoke(entry, route):
    value = entry["namespace"][entry["function"]](BASE + route)
    return value[1] if isinstance(value, tuple) else value


class GeneratedWorkChecks(unittest.TestCase):
    """Dynamically installed checks retain per-arm requirement boundaries."""


LABELS = {}


def install(name, tier, fn):
    setattr(GeneratedWorkChecks, name, fn)
    LABELS[name] = tier


for entry in IMPLEMENTATIONS:
    label = f'{entry["arm"]}_{entry["number"]}'

    def normal(self, item=entry):
        self.assertEqual("Local title", invoke(item, "/normal"))

    def utf8(self, item=entry):
        self.assertEqual("本机测试标题", invoke(item, "/utf8"))

    def absent(self, item=entry):
        self.assertIn("无", invoke(item, "/absent"))

    def timeout(self, item=entry):
        with patch("requests.get", side_effect=requests.Timeout("synthetic timeout")):
            self.assertRegex(invoke(item, "/normal"), "失败|错误")

    def http_error(self, item=entry):
        self.assertRegex(invoke(item, "/503"), "失败|错误")

    def empty(self, item=entry):
        self.assertIn("无", invoke(item, "/empty"))

    def entity(self, item=entry):
        self.assertEqual("A & B", invoke(item, "/entity"))

    for kind, tier, fn in (
        ("normal", "CORE", normal),
        ("utf8", "PREFROZEN_BOUNDARY_OBSERVATION", utf8),
        ("absent", "PREFROZEN_BOUNDARY_OBSERVATION", absent),
        ("timeout", "PREFROZEN_BOUNDARY_OBSERVATION", timeout),
        ("http_error", "PREFROZEN_BOUNDARY_OBSERVATION", http_error),
        ("empty", "SUPPLEMENTAL_DIAGNOSTIC", empty),
        ("entity", "SUPPLEMENTAL_DIAGNOSTIC", entity),
    ):
        install(f"test_{label}_{kind}", tier, fn)

enhanced = next(item for item in IMPLEMENTATIONS if item["arm"] == "enhanced")


def non_html(self):
    self.assertIn("非 HTML", invoke(enhanced, "/json"))


def order_and_isolation(self):
    stream = io.StringIO()
    urls = [BASE + route for route in ("/normal", "/503", "/absent", "/utf8")]
    with contextlib.redirect_stdout(stream):
        enhanced["namespace"]["main"](urls)
    lines = stream.getvalue().splitlines()
    self.assertEqual(urls, [line.split("\t", 1)[0] for line in lines])
    self.assertIn("Local title", lines[0])
    self.assertIn("请求失败", lines[1])
    self.assertIn("无 title", lines[2])
    self.assertIn("本机测试标题", lines[3])


install("test_enhanced_non_html", "ENHANCED_ONLY_CONTRACT", non_html)
install("test_enhanced_order_and_failure_isolation", "ENHANCED_ONLY_CONTRACT", order_and_isolation)


class RecordingResult(unittest.TextTestResult):
    """Keep failures as evidence; supplemental cases do not rewrite raw requirements."""

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.observations = []

    def record(self, test, status, detail=None):
        self.observations.append({"test": test._testMethodName, "tier": LABELS[test._testMethodName],
                                  "status": status, "detail": detail})

    def addSuccess(self, test):
        super().addSuccess(test)
        self.record(test, "PASS")

    def addFailure(self, test, error):
        super().addFailure(test, error)
        self.record(test, "FAIL", str(error[1]))

    def addError(self, test, error):
        super().addError(test, error)
        self.record(test, "ERROR", f"{error[0].__name__}: {error[1]}")


# Compile the independent test file together with every unchanged model block.
py_compile.compile(__file__, doraise=True, cfile=str(OUT / "independent-test.pyc"))
try:
    with patch("requests.get", side_effect=guarded_get):
        result = unittest.TextTestRunner(stream=io.StringIO(), resultclass=RecordingResult).run(
            unittest.defaultTestLoader.loadTestsFromTestCase(GeneratedWorkChecks)
        )
finally:
    server.shutdown()
    server.server_close()

report = {"implementations": COMPILED, "modelAuthoredTests": 0,
          "independentTestCompiled": True, "externalNetworkCallsByGeneratedCode": 0,
          "tests": result.testsRun, "failures": len(result.failures), "errors": len(result.errors),
          "observations": result.observations,
          "limitations": ["Function definitions executed unchanged; external-site top-level demonstrations omitted",
                          "Supplemental diagnostics are not retroactive hard requirements for the raw arm",
                          "No model-authored test files were provided in either response"]}
(OUT / "result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps({key: report[key] for key in ("tests", "failures", "errors", "modelAuthoredTests")}, ensure_ascii=False))
