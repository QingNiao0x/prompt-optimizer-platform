"""Offline verification of the exact requests/title task, without imposing a historical interface.

An explicit mapping selects actual generated Python blocks and their authored entry points. Source
bytes are never repaired or AST-filtered. Optional robustness/privacy observations are not promoted
to original-task requirements; a separate semantic review must check any enhanced commitments.
"""
from __future__ import annotations

import argparse
import ast
import contextlib
import hashlib
import importlib.util
import io
import json
import logging
import os
from pathlib import Path
import re
import runpy
import subprocess
import sys
import unittest
import uuid

FENCE = re.compile(r"^[ \t]*(`{3,}|~{3,})([^\r\n]*)\r?\n(.*?)^[ \t]*\1[ \t]*\r?$", re.M | re.S)
CANARY = "SyntheticRequestsCanary_34_X7q"
HTML = {
    "normal": "<html><head><title>Local title 34</title></head><body>fixture</body></html>",
    "utf8": "<html><head><title>本机标题三十四</title></head><body>本机资料。</body></html>",
    "entity": "<html><head><title>Alpha &amp; Beta</title></head></html>",
    "missing": "<html><body>No title element</body></html>",
    "http": "<html><head><title>Upstream failure title</title></head></html>",
}
EXPECTED = {"normal": "Local title 34", "utf8": "本机标题三十四", "entity": "Alpha & Beta"}
ALLOWED_IMPORTS = {"requests", "bs4", "lxml", "html", "re", "json", "sys", "typing", "collections", "__future__",
                   "dataclasses", "logging", "argparse", "unittest", "urllib", "time", "warnings",
                   "contextlib", "io", "functools", "textwrap", "types", "inspect", "http", "traceback"}
BLOCKED_CALLS = {"open", "exec", "eval", "compile", "__import__", "system", "popen", "spawn", "fork",
                 "read_text", "read_bytes", "write_text", "write_bytes", "unlink", "rmdir", "mkdir",
    "chdir", "remove", "unlink", "startfile", "connect", "send", "sendall", "urlopen", "FileHandler", "HTTPHandler"}


def digest(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def load_work(path: Path) -> str:
    """Preserve Markdown line endings; JSON inputs use the actual returned message content."""
    text = path.read_bytes().decode("utf-8")
    if path.suffix.lower() != ".json":
        return text
    value = json.loads(text)
    if isinstance(value.get("content"), str):
        return value["content"]
    return value.get("response", value)["choices"][0]["message"]["content"]


def safe_sources(sources: dict[str, str]) -> list[str]:
    """Execution-tool capability review; these restrictions are not new product requirements."""
    reasons = []
    local = {Path(name).stem for name in sources}
    for name, source in sources.items():
        tree = ast.parse(source, filename=name)
        for node in ast.walk(tree):
            if isinstance(node, (ast.Import, ast.ImportFrom)):
                names = [alias.name for alias in node.names] if isinstance(node, ast.Import) else [node.module or ""]
                for module in names:
                    if module.split(".")[0] not in ALLOWED_IMPORTS | local:
                        reasons.append(name + ":IMPORT_REQUIRES_REVIEW")
            if isinstance(node, ast.Call):
                called = node.func.id if isinstance(node.func, ast.Name) else node.func.attr if isinstance(node.func, ast.Attribute) else ""
                if called in BLOCKED_CALLS:
                    reasons.append(name + ":CAPABILITY_REQUIRES_REVIEW")
    return sorted(set(reasons))


def worker(config_path: Path) -> None:
    """Execute untouched files in a short-lived credential-free child process with mocked transport."""
    import socket
    from unittest.mock import patch
    import requests

    config = json.loads(config_path.read_text(encoding="utf-8"))
    directory = Path(config["directory"])
    entry, mode, url = config["entry"], config["mode"], config["url"]
    sent = []
    output = io.StringIO()
    errors = io.StringIO()
    logs = io.StringIO()
    handler = logging.StreamHandler(logs)
    logger = logging.getLogger()
    logger.addHandler(handler)
    sys.path.insert(0, str(directory))
    sys.dont_write_bytecode = True

    def blocked(*args, **kwargs):
        raise RuntimeError("Real network blocked by independent checker")

    def fake_request(method, address, **kwargs):
        sent.append({"method": str(method).upper(), "url": address})
        if mode == "timeout":
            raise requests.Timeout("synthetic timeout: " + str(address))
        if mode == "connection":
            raise requests.ConnectionError("synthetic connection: " + str(address))
        response = requests.Response()
        response.status_code = 503 if mode == "http" else 200
        response.url = str(address)
        response.headers["Content-Type"] = "text/html; charset=utf-8"
        response.encoding = "utf-8"
        response._content = HTML.get(mode, HTML["normal"]).encode("utf-8")
        return response

    result = {"transportCalls": sent, "mode": mode, "exceptionType": None, "result": None,
              "actualNetworkRequests": 0, "sourceRepaired": False}
    try:
        with patch.object(socket.socket, "connect", blocked), patch.object(socket, "create_connection", blocked), \
             patch.object(requests.Session, "request", lambda self, method, url, **kw: fake_request(method, url, **kw)), \
             patch.object(requests, "get", lambda url, **kw: fake_request("GET", url, **kw)), \
             patch("builtins.input", return_value=url), contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors):
            if entry["kind"] == "authored-tests":
                test_file = directory / entry["file"]
                spec = importlib.util.spec_from_file_location(test_file.stem, test_file)
                module = importlib.util.module_from_spec(spec)
                sys.modules[test_file.stem] = module
                spec.loader.exec_module(module)
                suite = unittest.defaultTestLoader.loadTestsFromModule(module)
                if suite.countTestCases():
                    test_result = unittest.TextTestRunner(stream=errors).run(suite)
                    result["authoredTests"] = {"tests": test_result.testsRun, "failures": len(test_result.failures),
                                               "errors": len(test_result.errors), "skipped": len(test_result.skipped)}
                else:
                    result["authoredTests"] = {"tests": None, "state": "MODULE_EXECUTED_NO_UNITTEST_CASE_COUNT"}
            elif entry["kind"] == "script":
                sys.argv = [entry["file"]] + ([url] if entry.get("urlArgument") == "argv" else [])
                runpy.run_path(str(directory / entry["file"]), run_name="__main__")
            else:
                filename = directory / entry["file"]
                spec = importlib.util.spec_from_file_location(filename.stem, filename)
                module = importlib.util.module_from_spec(spec)
                sys.modules[filename.stem] = module
                spec.loader.exec_module(module)
                # Import/demo output remains diagnostic evidence but cannot make a wrong function return pass.
                result["importStdout"] = output.getvalue()
                output.seek(0); output.truncate(0)
                value = getattr(module, entry["function"])(url)
                result["returnRepresentation"] = repr(value)
                for part in entry.get("titlePath", []):
                    value = value[part]
                result["result"] = value if isinstance(value, (str, int, float, bool, type(None))) else repr(value)
    except SystemExit as error:
        result["scriptExitCode"] = error.code if isinstance(error.code, int) else 0 if error.code is None else 1
        if result["scriptExitCode"]:
            result["exceptionType"] = "SystemExit"
            result["exceptionMessage"] = str(error)
    except BaseException as error:
        result["exceptionType"] = type(error).__name__
        result["exceptionMessage"] = str(error)
    finally:
        logger.removeHandler(handler)
    result.update(stdout=output.getvalue(), stderr=errors.getvalue(), logs=logs.getvalue())
    result["canaryObserved"] = CANARY in "\n".join(str(result.get(key, "")) for key in
        ["returnRepresentation", "result", "importStdout", "stdout", "stderr", "logs", "exceptionMessage"])
    result["canarySentToTransport"] = any(CANARY in str(item["url"]) for item in sent)
    Path(config["output"]).write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def invoke(directory: Path, output: Path, entry: dict, mode: str, url: str) -> dict:
    config = {"directory": str(directory), "entry": entry, "mode": mode, "url": url, "output": str(output)}
    config_path = output.with_suffix(".input.json")
    config_path.write_text(json.dumps(config, indent=2), encoding="utf-8")
    env = {key: value for key, value in os.environ.items() if key.upper() in {"SYSTEMROOT", "WINDIR", "TEMP", "TMP"}}
    try:
        process = subprocess.run([sys.executable, "-B", str(Path(__file__).resolve()), "--worker", str(config_path)],
                                 cwd=directory, env=env, capture_output=True, timeout=12)
        if output.exists():
            return json.loads(output.read_text(encoding="utf-8"))
        return {"toolState": "WORKER_DID_NOT_RETURN", "exitCode": process.returncode}
    except subprocess.TimeoutExpired:
        return {"toolState": "TIMEOUT"}


def evaluate(work: Path, mapping_file: Path, output: Path) -> dict:
    """Bind each selected source/entry to the original work; do not reconstruct files or missing tests."""
    text = load_work(work)
    mapping = json.loads(mapping_file.read_text(encoding="utf-8"))
    output.mkdir(parents=True, exist_ok=True)
    run = output / ("run-" + uuid.uuid4().hex[:10]); run.mkdir()
    directory = run / "sources"; directory.mkdir()
    fences = list(FENCE.finditer(text))
    report = {"work": str(work.resolve()), "workSha256": digest(text.encode("utf-8")), "sourceRepaired": False,
              "externalModelCalls": 0, "actualNetworkRequests": 0, "mappingSha256": digest(mapping_file.read_bytes()),
              "extraction": {"status": "PASS", "files": []}, "syntax": {"status": "PASS", "files": []},
              "authoredTests": [], "entryPoints": mapping["entries"], "originalTaskChecks": [], "diagnostics": [],
              "enhancedCommitments": {"state": "OPEN_REQUIRES_EXACT_PROMPT_AND_WORK_REVIEW"},
              "uncovered": ["Exhaustive data-flow safety", "Real website behavior", "Statistical stability",
                            "Promise fulfillment beyond stated mechanical checks"]}
    sources = {}
    for item in mapping["files"]:
        name = item["name"]
        path = Path(name)
        if path.is_absolute() or len(path.parts) != 1 or path.suffix != ".py" or name in sources:
            raise ValueError("Each mapped file must have one unique safe Python basename")
        match = fences[item["block"] - 1]
        if not match.group(2).strip().lower().startswith(("python", "py")):
            raise ValueError("Only an actual Python fence can be selected")
        source = match.group(3)
        (directory / name).write_bytes(source.encode("utf-8")); sources[name] = source
        report["extraction"]["files"].append({"block": item["block"], "name": name, "role": item["role"], "sha256": digest(source.encode("utf-8"))})
        try:
            compile(source, name, "exec")
            report["syntax"]["files"].append({"name": name, "status": "PASS"})
        except SyntaxError as error:
            report["syntax"]["status"] = "FAIL"
            report["syntax"]["files"].append({"name": name, "status": "FAIL", "line": error.lineno})
    mapped = {item["block"] for item in mapping["files"]}
    report["unselectedPythonBlocks"] = [index for index, fence in enumerate(fences, 1)
        if fence.group(2).strip().lower().startswith(("python", "py")) and index not in mapped]
    if report["syntax"]["status"] == "PASS":
        report["executionSafetyReview"] = safe_sources(sources)
        if not report["executionSafetyReview"]:
            for index, entry in enumerate(mapping["entries"], 1):
                if entry["file"] not in sources or entry["kind"] not in {"function", "script"}:
                    raise ValueError("Entry must identify an extracted function or script")
                if entry["kind"] == "function":
                    tree = ast.parse(sources[entry["file"]])
                    if not any(isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and node.name == entry["function"] for node in tree.body):
                        raise ValueError("Selected function is absent; checker never supplies one")
                for mode in ["normal", "utf8", "entity", "token-success", "userinfo-success", "missing", "http", "timeout", "connection"]:
                    url = "https://example.invalid/title" + ("?token=" + CANARY if mode not in EXPECTED else "")
                    if mode == "userinfo-success": url = "https://user:" + CANARY + "@example.invalid/title"
                    observed = invoke(directory.resolve(), run / f"entry-{index}-{mode}.json", entry, mode, url)
                    row = {"entry": index, "mode": mode, "observation": observed}
                    title = observed.get("stdout", "").strip() if entry.get("output") == "stdout" or entry["kind"] == "script" else observed.get("result")
                    if isinstance(title, str) and (entry.get("output") == "stdout" or entry["kind"] == "script"):
                        lines = title.splitlines()
                        if "stdoutTitleLine" in entry:
                            try: title = lines[entry["stdoutTitleLine"]]
                            except IndexError: title = None
                        prefix = entry.get("stdoutTitlePrefix", "")
                        if prefix:
                            title = title[len(prefix):] if isinstance(title, str) and title.startswith(prefix) else None
                    if mode in {"normal", "utf8"}:
                        row["state"] = "PASS" if title == EXPECTED[mode] and observed.get("transportCalls") and observed.get("exceptionType") is None else "FAIL"
                        report["originalTaskChecks"].append(row)
                    else:
                        row["state"] = "OBSERVED_ONLY"
                        if mode == "entity": row["entityTitleCorrect"] = title == EXPECTED[mode]
                        if mode in {"token-success", "userinfo-success", "http", "timeout", "connection", "missing"}:
                            row["syntheticCredentialDiagnostic"] = "ECHO_OBSERVED" if observed.get("canaryObserved") else "NO_ECHO_IN_THIS_CASE" if observed.get("canarySentToTransport") else "NOT_EXERCISED_NO_DECLARED_URL_INPUT"
                        report["diagnostics"].append(row)
            for index, item in enumerate(mapping["files"], 1):
                if item["role"] != "tests": continue
                observed = invoke(directory.resolve(), run / f"authored-tests-{index}.json", {"kind": "authored-tests", "file": item["name"]}, "normal", "https://example.invalid/title")
                tests = observed.get("authoredTests", {})
                state = "FAIL" if observed.get("exceptionType") or tests.get("failures") or tests.get("errors") else "PASS" if tests.get("tests", 0) else "OPEN"
                report["authoredTests"].append({"file": item["name"], "state": state, "observation": observed})
    report["originalTaskMechanicalGate"] = "FAIL" if report["syntax"]["status"] == "FAIL" or any(row["state"] == "FAIL" for row in report["originalTaskChecks"]) else "PASS" if report["originalTaskChecks"] and not report.get("executionSafetyReview") else "OPEN"
    if report["unselectedPythonBlocks"]:
        report["uncovered"].append("Unselected Python blocks need role review; no silently omitted implementation or tests")
        report["originalTaskMechanicalGate"] = "OPEN"
    if not report["authoredTests"]:
        report["authoredTestsState"] = "NOT_PROVIDED_NOT_REQUIRED_BY_ORIGINAL_TASK"
    report["mechanicalGate"] = "FAIL" if report["originalTaskMechanicalGate"] == "FAIL" or report["syntax"]["status"] == "FAIL" or any(row["state"] == "FAIL" for row in report["authoredTests"]) else "OPEN" if report["originalTaskMechanicalGate"] == "OPEN" or any(row["state"] == "OPEN" for row in report["authoredTests"]) else "PASS"
    report["mechanicalGateScope"] = "Actual source syntax, original requests/title behavior and execution of authored tests when supplied; supplementary privacy/robustness observations are separate"
    (output / "result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--work", type=Path)
    parser.add_argument("--mapping", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--worker", type=Path)
    args = parser.parse_args()
    if args.worker:
        worker(args.worker); return
    if not all([args.work, args.mapping, args.output]): parser.error("--work --mapping --output required")
    report = evaluate(args.work.resolve(), args.mapping.resolve(), args.output.resolve())
    print(json.dumps({"originalTaskMechanicalGate": report["originalTaskMechanicalGate"], "syntax": report["syntax"]["status"],
                      "coreChecks": len(report["originalTaskChecks"]), "authoredTestFiles": len(report["authoredTests"]),
                      "externalModelCalls": 0}))


if __name__ == "__main__":
    main()
