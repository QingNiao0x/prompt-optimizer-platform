"""Run unchanged saved implementations and authored tests with fake transport only."""
import ast
import contextlib
import hashlib
import importlib.util
import io
import json
import logging
from pathlib import Path
import py_compile
import re
import socket
import sys
import types
import unittest
from unittest.mock import patch
import requests

ROOT = Path(__file__).resolve().parents[4]
WORKS = ROOT / "docs/testing/evidence/me00a-candidate21-live-2026-10-16/real"
OUT = ROOT / "tmp/me00a-candidate31-20261017/saved-code-replay"
OUT.mkdir(exist_ok=False)
compiled, checks = [], []
CANARY = "SyntheticCanary_21_Z9q"
fence = chr(96) * 3


def blocks(folder, arm):
    text = (WORKS / folder / (arm + "-work.md")).read_text(encoding="utf-8")
    return re.findall(fence + r"(?:python|py)\s*\n(.*?)" + fence, text, re.S)


def save_source(folder, name, text, role):
    target = OUT / folder / name
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text, encoding="utf-8", newline="")
    row = dict(file=str(target.relative_to(OUT)), role=role, sha256=hashlib.sha256(text.encode()).hexdigest())
    try:
        py_compile.compile(str(target), doraise=True, cfile=str(target.with_suffix(".pyc")))
        row["compiled"] = True
    except py_compile.PyCompileError:
        row["compiled"] = False
        if role != "DOCUMENTED_SIGNATURE":
            raise
    compiled.append(row)
    return target


def load_module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


class FakeResponse:
    def __init__(self, html="<title>Alpha &amp; Beta</title>", status=200):
        self.content = html.encode("utf-8")
        self.encoding = "utf-8"
        self.apparent_encoding = "utf-8"
        self.status_code = status
        self.headers = {"Content-Type": "text/html; charset=utf-8"}

    @property
    def text(self):
        return self.content.decode(self.encoding or self.apparent_encoding)

    def raise_for_status(self):
        if self.status_code >= 400:
            raise requests.HTTPError("synthetic HTTP failure", response=self)


def blocked(*args, **kwargs):
    raise RuntimeError("Real network disabled in code verification")


def run_entry(label, function, enhanced):
    cases = [
        ("NORMAL", "https://example.org/page", "normal"),
        ("UTF8", "https://example.org/page", "utf8"),
        ("TOKEN_RETURN", "https://example.org/page?token=" + CANARY, "normal"),
        ("API_KEY_RETURN", "https://example.org/page?api_key=" + CANARY, "normal"),
        ("USERINFO_RETURN", "https://user:" + CANARY + "@example.org/page", "normal"),
        ("INVALID_URL", "https:///page?token=" + CANARY, "invalid"),
        ("TIMEOUT", "https://example.org/page?token=" + CANARY, "timeout"),
        ("CONNECTION_ERROR", "https://example.org/page?token=" + CANARY, "connection"),
        ("HTTP_ERROR", "https://example.org/page?token=" + CANARY, "http"),
        ("MISSING_TITLE", "https://example.org/page?token=" + CANARY, "missing"),
    ]
    for case, url, mode in cases:
        sent = []
        capture = io.StringIO()
        handler = logging.StreamHandler(capture)
        logger = logging.getLogger()
        logger.addHandler(handler)

        def fake_get(actual, **kwargs):
            sent.append(actual)
            if mode == "timeout":
                raise requests.Timeout("synthetic timeout: " + actual)
            if mode == "connection":
                raise requests.ConnectionError("synthetic connection: " + actual)
            return FakeResponse("<title>中文标题</title>" if mode == "utf8" else "<body>empty</body>" if mode == "missing"
                                else "<title>Alpha &amp; Beta</title>", 503 if mode == "http" else 200)

        try:
            with patch.object(requests, "get", fake_get), patch.object(requests.Session, "get",
                    lambda self, actual, **kwargs: fake_get(actual, **kwargs)), contextlib.redirect_stdout(capture), contextlib.redirect_stderr(capture):
                try:
                    value = function(url)
                    observed = repr(value)
                except Exception as error:
                    observed = type(error).__name__ + ": " + str(error)
            observed += "\n" + capture.getvalue()
        finally:
            logger.removeHandler(handler)
        if case in ("NORMAL", "UTF8"):
            expected = "Alpha & Beta" if mode == "normal" else "中文标题"
            passed = expected in observed
            scope = "CORE_TITLE_BEHAVIOR" if case == "UTF8" else "TITLE_AND_ENTITY_DIAGNOSTIC"
        else:
            passed = CANARY not in observed
            scope = "ENHANCED_PRIVACY_RULE" if enhanced else "SUPPLEMENTARY_RAW_PRIVACY"
        unchanged = all(actual == url for actual in sent)
        checks.append(dict(entry=label, case=case, scope=scope, pass_=passed,
                           requestArgumentsUnchanged=unchanged, requestCalls=len(sent), observed=observed[:1500],
                           syntheticOnly=True))


with patch.object(socket.socket, "connect", blocked), patch.object(requests.Session, "request", blocked):
    for i, block in enumerate(blocks("requests-title", "raw"), 1):
        path = save_source("raw", "raw-" + str(i) + ".py", block, "ORIGINAL_IMPLEMENTATION_WITH_DEMO")
        tree = ast.parse(block)
        nodes = []
        for node in tree.body:
            if isinstance(node, (ast.Import, ast.ImportFrom, ast.FunctionDef, ast.ClassDef)):
                nodes.append(node)
            elif isinstance(node, (ast.Assign, ast.AnnAssign)):
                try:
                    ast.literal_eval(node.value)
                    nodes.append(node)
                except (ValueError, TypeError):
                    pass
        module = types.ModuleType("raw_" + str(i))
        exec(compile(ast.Module(body=nodes, type_ignores=[]), str(path), "exec"), module.__dict__)
        name = ["get_page_title", "get_title", "get_title_regex"][i - 1]
        run_entry("raw-" + str(i), getattr(module, name), False)

    flash_blocks = blocks("requests-title", "enhanced")
    for i, block in enumerate(flash_blocks, 1):
        name = ["exceptions.py", "parser.py", "fetcher.py", "cli.py"][i - 1] if i <= 4 else "signature-" + str(i) + ".py"
        save_source("flash_pkg", name, block, "IMPLEMENTATION" if i <= 4 else "DOCUMENTED_SIGNATURE")
    package = types.ModuleType("flash_pkg")
    package.__path__ = [str(OUT / "flash_pkg")]
    sys.modules["flash_pkg"] = package
    flash = load_module("flash_pkg.fetcher", OUT / "flash_pkg/fetcher.py")
    run_entry("flash-enhanced", flash.fetch_page_title, True)

    pro_blocks = blocks("requests-title-pro", "enhanced")
    pro_path = save_source("pro", "title_fetcher.py", pro_blocks[0], "IMPLEMENTATION")
    pro = load_module("title_fetcher", pro_path)
    run_entry("pro-enhanced", pro.fetch_title, True)
    for i, block in enumerate(pro_blocks[1:], 2):
        name = "test_title_fetcher.py" if i == 3 else "block-" + str(i) + ".py"
        save_source("pro", name, block, "DOCUMENTED_SIGNATURE" if i == 2 else "MODEL_AUTHORED_TESTS" if i == 3 else "EXAMPLE_OR_OPTIONAL_CLI")
    test_module = load_module("test_title_fetcher", OUT / "pro/test_title_fetcher.py")
    stream = io.StringIO()
    authored = unittest.TextTestRunner(stream=stream).run(unittest.defaultTestLoader.loadTestsFromModule(test_module))

counts = {"total": len(checks), "passed": sum(row["pass_"] for row in checks), "failed": sum(not row["pass_"] for row in checks)}
result = {"compiledBlocks": compiled, "independent": counts, "checks": checks,
          "modelAuthoredTests": {"flash": {"state": "TEST_PLAN_ONLY_NO_EXECUTABLE_TEST_FILE"},
                               "pro": {"tests": authored.testsRun, "failures": len(authored.failures), "errors": len(authored.errors),
                                       "output": stream.getvalue()}},
          "batchKeyChecks": "NOT_APPLICABLE_NO_BATCH_RETURN_INTERFACE",
          "realNetworkRequests": 0, "originalWorksChanged": False,
          "adaptation": "Original files compiled unchanged. Raw top-level demonstrations not executed; original literal constants retained. Flash package namespace supplied by harness; no algorithm repair."}
(OUT / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps({"independent": counts, "proAuthoredTests": authored.testsRun, "proAuthoredFailures": len(authored.failures),
                  "proAuthoredErrors": len(authored.errors), "realNetworkRequests": 0}))
