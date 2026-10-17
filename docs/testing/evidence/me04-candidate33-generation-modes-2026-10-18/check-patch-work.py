"""Compile generated PATCH implementation/tests together and independently verify frozen requirements.

No model calls, dependency downloads, source repairs or generated-test substitution occur here.
Null/empty/nested design checks require an explicit declaration with an exact quotation from the work.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import uuid

HERE = Path(__file__).resolve().parent
JAVA_HOME = Path("E:/Delelop/Java/jdk-21")
LEXICAL = re.compile(r'""".*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\r\n]*|/\*.*?\*/', re.S)
FENCE = re.compile(r"^[ \t]*(`{3,}|~{3,})([^\r\n]*)\r?\n(.*?)^[ \t]*\1[ \t]*\r?$", re.M | re.S)
CLASS = re.compile(r"\b(?:class|record|enum|interface)\s+(PatchMergerTest|PatchMerger)\b")
PACKAGE = re.compile(r"(?m)^\s*package\s+([\w.]+)\s*;")
UNSAFE = re.compile(r"\b(?:ProcessBuilder|Runtime|ClassLoader|URLClassLoader|Socket|ServerSocket|HttpClient|URLConnection|FileInputStream|FileOutputStream|FileReader|FileWriter|RandomAccessFile)\b|\bSystem\s*\.\s*(?:exit|getenv|setSecurityManager)\b|\bFiles\s*\.\s*(?:write|delete|read|new|create|copy|move|list|walk|lines)\w*\b")


def mask(source: str) -> str:
    """Comments/literals cannot manufacture class, import, logging or assertion findings."""
    return LEXICAL.sub(lambda m: re.sub(r"[^\r\n]", " ", m.group()), source)


def work_text(path: Path) -> str:
    data = path.read_bytes().decode("utf-8")
    if path.suffix.lower() != ".json":
        return data
    value = json.loads(data)
    if isinstance(value, dict) and isinstance(value.get("content"), str):
        return value["content"]
    response = value.get("response", value)
    return response["choices"][0]["message"]["content"]


def extract(work: Path) -> tuple[dict[str, str], str, list[dict]]:
    """Select only complete, explicitly generated files; ambiguity/missing files fails instead of repair."""
    candidates: dict[str, list[str]] = {"PatchMerger": [], "PatchMergerTest": []}
    provenance: list[dict] = []
    if work.is_dir():
        texts = []
        for name in candidates:
            files = list(work.rglob(name + ".java"))
            if len(files) != 1:
                raise ValueError("Exactly one generated " + name + ".java is required")
            source = files[0].read_bytes().decode("utf-8")
            candidates[name].append(source)
            texts.append(source)
            provenance.append({"name": name + ".java", "source": str(files[0]), "sha256": hashlib.sha256(files[0].read_bytes()).hexdigest()})
        return {k: v[0] for k, v in candidates.items()}, "\n".join(texts), provenance
    text = work_text(work)
    for index, match in enumerate(FENCE.finditer(text), 1):
        language = match.group(2).strip().lower()
        if language and not language.startswith("java") and ".java" not in language:
            continue
        code = match.group(3)
        names = set(CLASS.findall(mask(code)))
        if len(names) > 1:
            raise ValueError("Multiple target classes in one block need explicit separate files; source is not rewritten")
        for name in names:
            candidates[name].append(code)
            provenance.append({"name": name + ".java", "block": index, "sha256": hashlib.sha256(code.encode("utf-8")).hexdigest()})
    for name, values in candidates.items():
        if len(values) != 1:
            raise ValueError("Exactly one complete generated block for " + name + " is required")
    return {k: v[0] for k, v in candidates.items()}, text, provenance


def run(command: list[str], cwd: Path, timeout: int = 20) -> dict:
    """Use Java 21 without Maven, inherited model credentials, annotation processing or user classpaths."""
    env = {k: v for k, v in os.environ.items() if k.upper() in {"SYSTEMROOT", "WINDIR", "TEMP", "TMP"}}
    try:
        result = subprocess.run(command, cwd=cwd, env=env, capture_output=True, timeout=timeout)
        return {"status": "PASS" if result.returncode == 0 else "FAIL", "exitCode": result.returncode,
                "stdout": result.stdout.decode("utf-8", errors="replace"), "stderr": result.stderr.decode("utf-8", errors="replace")}
    except subprocess.TimeoutExpired:
        return {"status": "TIMEOUT", "exitCode": None, "stdout": "", "stderr": "Execution exceeded checker deadline"}


def declarations(policy: Path | None, text: str) -> tuple[list[str], list[str]]:
    """A policy is reviewer-supplied evidence extraction, never an additional requirement silently added to work."""
    names = ["nullCurrent", "nullPatch", "emptyCurrent", "emptyPatch", "nested"]
    if policy is None:
        return [], names
    values = json.loads(policy.read_text(encoding="utf-8"))
    flags, uncovered = [], []
    for name in names:
        item = values.get(name)
        if item is None:
            uncovered.append(name)
            continue
        mode, evidence = item.get("mode", ""), item.get("evidence", "")
        allowed = {"EMPTY_MAP", "ALLOW"} if name != "nested" else {"SHALLOW_REPLACE", "DEEP_MERGE"}
        valid_rejection = re.fullmatch(r"REJECT(?::[A-Za-z_$][\w$]*(?:Exception|Error))?", mode)
        if (mode not in allowed and not valid_rejection) or not evidence or evidence not in text:
            raise ValueError("Declared policy needs an allowed mode and exact work quotation: " + name)
        flags.append(name + "=" + mode)
    return flags, uncovered


def evaluate(work: Path, output: Path, policy: Path | None = None, java_home: Path = JAVA_HOME) -> dict:
    """Keep compilation, generated tests and independent checks as separate gates with their own evidence."""
    output.mkdir(parents=True, exist_ok=True)
    sandbox = output / ("run-" + uuid.uuid4().hex[:10])
    sandbox.mkdir()
    result = {"work": str(work.resolve()), "runDirectory": str(sandbox.resolve()), "externalModelCalls": 0,
              "sourceRepaired": False, "extraction": {"status": "NOT_RUN"}, "compilation": {"status": "NOT_RUN"},
              "generatedTests": {"status": "NOT_RUN"}, "independentChecks": {"status": "NOT_RUN"},
              "notCovered": ["Live services, filesystem or network integrations", "Statistical model stability", "Independent semantic review of explanatory prose",
                             "Exhaustive data-flow analysis or logger handlers retaining external streams"]}
    stage = "extraction"
    try:
        if work.is_file():
            result["workSha256"] = hashlib.sha256(work_text(work).encode("utf-8")).hexdigest()
        sources, text, provenance = extract(work)
        stage = "declarationPolicy"
        flags, uncovered = declarations(policy, text)
        result["extraction"] = {"status": "PASS", "files": provenance}
        result["notCovered"].extend("Declared design not independently judged: " + n for n in uncovered)
        imports, unsafe, allowed_test_exit = [], [], False
        for name, source in sources.items():
            clean = mask(source)
            imports.extend(re.findall(r"(?m)^\s*import\s+(?:static\s+)?([\w.*]+)\s*;", clean))
            # A test's literal exit code terminates only its isolated child JVM; no application JVM is used.
            capability_source = clean
            if name == "PatchMergerTest":
                capability_source, count = re.subn(r"\bSystem\s*\.\s*exit\s*\(\s*[01]\s*\)\s*;", "", clean)
                allowed_test_exit = count > 0
            if UNSAFE.search(capability_source):
                unsafe.append(name)
            (sandbox / (name + ".java")).write_bytes(source.encode("utf-8"))
        third_party = [value for value in imports if not value.startswith(("java.", "javax.", "jdk."))]
        generated_assertions = bool(re.search(r"\bassert\s+", mask(sources["PatchMergerTest"])))
        result["staticChecks"] = {"thirdPartyImports": third_party, "thirdPartyImportGatePassed": not third_party,
                                  "generatedJavaAssertStatements": generated_assertions,
                                  "generatedTestEaIndependence": "REVIEW_REQUIRED" if generated_assertions else "NO_JAVA_ASSERT_STATEMENTS",
                                  "literalGeneratedTestExitAllowedInIsolatedJvm": allowed_test_exit,
                                  "executionCapabilityReviewRequired": unsafe}
        stage = "executionTool"
        if unsafe:
            result["compilation"] = {"status": "BLOCKED_FOR_REVIEW", "reason": "Unexpected process, environment, file or network capability in generated source"}
        else:
            classes = sandbox / "classes"
            classes.mkdir()
            javac = str(java_home / "bin/javac.exe")
            java = str(java_home / "bin/java.exe")
            compilation = run([javac, "--release", "21", "-encoding", "UTF-8", "-proc:none", "-d", str(classes.resolve()),
                               str((sandbox / "PatchMerger.java").resolve()), str((sandbox / "PatchMergerTest.java").resolve())], sandbox, 35)
            result["compilation"] = compilation
            if compilation["status"] == "PASS":
                def binary(name: str) -> str:
                    package = PACKAGE.search(mask(sources[name]))
                    return (package.group(1) + "." if package else "") + name
                runtime = [java, "-Xmx128m", "-XX:ActiveProcessorCount=2", "-Dfile.encoding=UTF-8"]
                result["generatedTests"] = run(runtime + ["-cp", str(classes.resolve()), binary("PatchMergerTest")], sandbox, 12)
                result["generatedTests"]["eaEnabled"] = False
                checker_classes = sandbox / "independent-classes"
                checker_classes.mkdir()
                checker_compile = run([javac, "--release", "21", "-encoding", "UTF-8", "-proc:none", "-d", str(checker_classes.resolve()),
                                       str(HERE / "PatchIndependentChecks.java")], sandbox, 35)
                result["independentCheckerCompilation"] = checker_compile
                if checker_compile["status"] == "PASS":
                    independent = run(runtime + ["-cp", str(checker_classes.resolve()) + os.pathsep + str(classes.resolve()),
                                                  "PatchIndependentChecks", binary("PatchMerger"), *flags], sandbox, 12)
                    if independent["status"] == "PASS":
                        try:
                            detail = json.loads(independent["stdout"].strip())
                            independent["details"] = detail
                            independent["status"] = "PASS" if detail["coreFailed"] == 0 and detail["declarationFailed"] == 0 else "FAIL"
                        except (ValueError, KeyError):
                            independent["status"] = "TOOL_RESULT_INVALID"
                    result["independentChecks"] = independent
    except (ValueError, KeyError, OSError) as error:
        if stage == "extraction":
            result["extraction"] = {"status": "FAIL", "reason": str(error)}
        else:
            result["toolError"] = {"stage": stage, "reason": str(error)}
    result["requiredCodeGatesPassed"] = all(result[name]["status"] == "PASS" for name in ["extraction", "compilation", "generatedTests", "independentChecks"])
    result["requiredCodeGatesPassed"] &= not result.get("staticChecks", {}).get("thirdPartyImports", [])
    result["requiresGeneratedTestReview"] = result.get("staticChecks", {}).get("generatedJavaAssertStatements", False)
    # A zero exit with JVM assertions disabled cannot certify checks implemented only by Java assert statements.
    if result["requiresGeneratedTestReview"]:
        result["requiredCodeGatesPassed"] = False
    (output / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--work", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--policy", type=Path)
    parser.add_argument("--java-home", type=Path, default=JAVA_HOME)
    args = parser.parse_args()
    result = evaluate(args.work.resolve(), args.output.resolve(), args.policy, args.java_home)
    print(json.dumps({"result": str((args.output / "result.json").resolve()), "requiredCodeGatesPassed": result["requiredCodeGatesPassed"],
                      "compilation": result["compilation"]["status"], "generatedTests": result["generatedTests"]["status"],
                      "independentChecks": result["independentChecks"]["status"], "requiresGeneratedTestReview": result["requiresGeneratedTestReview"]}))


if __name__ == "__main__":
    main()
