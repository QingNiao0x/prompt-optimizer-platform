"""Append the frozen ME-00-A continuation without rewriting its earlier archive.

This offline tool uses an explicit synthetic-evidence allowlist. It never calls a
provider or copies accounts, browser state, configuration, runtime logs or classes.
An existing destination is verified, never overwritten.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path


REPO = Path(__file__).resolve().parent.parent
RUNTIME = REPO / "tmp/me00a-20261012"
PARENT = REPO / "docs/testing/evidence/me00a-2026-10-12"
DESTINATION = REPO / "docs/testing/evidence/me00a-2026-10-13-continuation"
PHASE_FILES = {
    "input.json", "platform.json", "copied.md", "raw-input.md",
    "enhanced-input.md", "raw-work.md", "work.md", "raw-execution.json",
    "execution.json", "context-prepared.json", "plan.json", "actual-answers.json",
    "history-check.json", "failure.json", "identity-check.json",
}
SENSITIVE_KEY = re.compile(
    r"^(?:password|passwd|passwordHash|api[_-]?key|access[_-]?token|"
    r"refresh[_-]?token|id[_-]?token|authorization|cookies?|set-cookie|"
    r"csrf[_-]?token|xsrf[_-]?token|private[_-]?key|client[_-]?secret|"
    r"session[_-]?(?:token|cookie)|connectionString)$", re.IGNORECASE
)
SECRET_VALUE = re.compile(
    r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|"
    r"\bBearer\s+[A-Za-z0-9_.~+/=-]{12,}|"
    r"\bsk-[A-Za-z0-9_-]{20,}|"
    r"\b(?:ghp_|github_pat_)[A-Za-z0-9_]{20,}"
)


def sha256(data: bytes) -> str:
    """Hash exact source or archive bytes, including intentional transformations."""
    return hashlib.sha256(data).hexdigest()


def json_bytes(value: object) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def read_json(path: Path) -> object:
    return json.loads(path.read_text(encoding="utf-8-sig"))


def scan_content(relative_path: str, content: bytes) -> None:
    """Fail closed on credential fields/patterns without printing suspect values."""
    text = content.decode("utf-8-sig")
    if SECRET_VALUE.search(text):
        raise ValueError(f"Sensitive value pattern in {relative_path}; no value printed")
    if not relative_path.endswith(".json"):
        return

    def inspect(value: object) -> None:
        if isinstance(value, dict):
            if any(SENSITIVE_KEY.fullmatch(key) for key in value):
                raise ValueError(f"Sensitive key in {relative_path}; no value printed")
            for child in value.values():
                inspect(child)
        elif isinstance(value, list):
            for child in value:
                inspect(child)

    inspect(json.loads(text))


def verify_parent() -> dict[str, str]:
    """Keep the prior 564-file evidence set intact, including incomplete trace 142."""
    hashes = read_json(PARENT / "artifact-hashes.json")
    if len(hashes) != 564:
        raise ValueError("Unexpected parent archive size; inspect before archiving")
    for relative_path, digest in hashes.items():
        target = (PARENT / relative_path).resolve()
        if not target.is_relative_to(PARENT.resolve()) or sha256(target.read_bytes()) != digest:
            raise ValueError(f"Parent evidence mismatch: {relative_path}")
    return hashes


def budget_snapshot() -> dict:
    """Count attempts from traces; phase limits cannot borrow from one another."""
    traces = [read_json(path) for path in sorted((RUNTIME / "traces").glob("*.json"))]
    phases = {phase: sum(row.get("phase") == phase for row in traces)
              for phase in ("baseline", "candidateValidation")}
    if len(traces) != 169 or phases != {"baseline": 70, "candidateValidation": 99}:
        raise ValueError("Unexpected runtime budget; this archive is frozen at 169 attempts")
    return {
        "actualCalls": 169, "phaseCalls": phases, "maximumActualModelCalls": 180,
        "phaseLimits": {"baseline": 80, "candidateValidation": 100},
        "remaining": {"total": 11, "baseline": 10, "candidateValidation": 1},
        "interpretation": "HTTP success is not quality acceptance; phase balances are not transferable.",
    }


def build_payload() -> tuple[dict[str, bytes], dict]:
    """Prepare and inspect every output before creating the immutable directory."""
    parent_hashes = verify_parent()
    budget = budget_snapshot()
    payload: dict[str, bytes] = {}
    entries = []

    def add(source: Path, target: str, normalize_trace: bool = False) -> None:
        source_bytes = source.read_bytes()
        output = source_bytes
        transformation = "byte-for-byte copy"
        if normalize_trace:
            value = json.loads(source_bytes.decode("utf-8-sig"))
            if "trace" in value:
                filename = re.split(r"[/\\]", value["trace"])[-1]
                number = int(Path(filename).stem)
                prefix = "traces/" if number >= 142 else "../me00a-2026-10-12/traces/"
                value["trace"] = prefix + filename
            output = json_bytes(value)
            transformation = "JSON UTF-8; replace execution.trace absolute local path with archive-root-relative trace path"
        scan_content(target, output)
        if target in payload:
            raise ValueError(f"Duplicate target: {target}")
        payload[target] = output
        entries.append({
            "source": source.relative_to(REPO).as_posix(),
            "sourceSha256": sha256(source_bytes), "target": target,
            "targetSha256": sha256(output), "transformation": transformation,
        })

    new_phase_files = []
    for phase in sorted(RUNTIME.glob("*-r[12]")):
        if phase.is_dir():
            for source in sorted(phase.rglob("*")):
                if source.is_file() and source.name in PHASE_FILES:
                    relative_path = source.relative_to(RUNTIME).as_posix()
                    if not (PARENT / relative_path).exists():
                        new_phase_files.append(relative_path)
                        add(source, relative_path, source.name.endswith("execution.json"))
    if len(new_phase_files) != 87:
        raise ValueError("Unexpected incremental phase inventory; expected 87 files")

    old_142 = read_json(PARENT / "traces/142.json")
    completed_142 = read_json(RUNTIME / "traces/142.json")
    if not all(completed_142.get(key) == value for key, value in old_142.items()):
        raise ValueError("Trace 142 does not match the earlier request snapshot")
    for number in range(142, 170):
        add(RUNTIME / f"traces/{number:03}.json", f"traces/{number:03}.json")

    for source, target in [
        (RUNTIME / "protocol-amendment-02.json", "protocol-amendment-02.json"),
        (RUNTIME / "candidate5/source.json", "candidate5-source.json"),
        (RUNTIME / "remaining-review-summary.json", "remaining-review-summary.json"),
        (RUNTIME / "review-remaining/reviewer-one.json", "review/reviewer-one.json"),
        (RUNTIME / "review-remaining/reviewer-two.json", "review/reviewer-two.json"),
        (RUNTIME / "review-remaining/rubric.md", "review/rubric.md"),
        (RUNTIME / "remaining-review-mapping.private.json", "review/mapping.json"),
        (RUNTIME / "mechanical-audit.json", "mechanical-audit.json"),
        (RUNTIME / "final-test-summary.json", "historical-final-test-summary.json"),
        (REPO / "tmp/me00a-20261013-handoff-audit.json", "handoff-audit.json"),
    ]:
        add(source, target)

    readme = """# ME-00-A 追加证据归档（2026-10-13）

父证据：[2026-10-12 冻结归档](../me00a-2026-10-12/)。本目录只追加后来发现的本机合成评测证据，没有重新调用模型或重新运行业务测试，没有覆盖父目录。

- 当前归档候选为 candidate5。`candidate5-source.json` 是历史源码冻结，不能以本目录日期宣称本轮重新生成或完成全候选回归。
- `historical-final-test-summary.json` 是此前保存的 2191 项测试摘要（2153 通过、38 跳过）及 3 项脚本测试；其时间早于 candidate5，不是 candidate5 完整回归，也不是本轮新跑。
- 真实调用共 169 次：基线 70/80、候选 99/100。候选只剩 1 次；总额余额不可挪作候选余额。
- `traces/142.json` 为父目录同一 requestId/request 的完成版，增加响应及耗时。父目录的未完成快照完整保留。143—169 为新增调用。
- 新增阶段材料共 87 文件。执行记录中的绝对本机 trace 路径已转成相对归档根目录的路径；原始 SHA256、目标 SHA256 及规则见 `manifest.json`。跨目录引用使用父证据目录。
- `review/` 为两位独立 AI 辅助评审原件、匿名量表和合成作品映射，不是人工专家评审。23 包、39 对比较、9 项分歧保留。candidate5 Plan 对同信息直接增强均判负，对原始执行存在分歧，不能关闭质量门禁。
- G1—G8、完整最终候选七题两轮、自然 Plan 收益和真实浏览器全部闭环仍未因归档而完成。

归档白名单排除账户、Cookie、配置、日志及私钥。`manifest.json` 记录来源与转换，`artifact-hashes.json` 记录本目录哈希。可运行 `python scripts/me00a-archive-continuation.py --verify` 离线核验；已存在目录不会被脚本覆盖。
"""
    payload["README.md"] = readme.encode("utf-8")
    manifest = {
        "schemaVersion": 1,
        "kind": "immutable-continuation-of-historical-synthetic-evidence",
        "parentArchive": "../me00a-2026-10-12",
        "parentArtifactHashesSha256": sha256((PARENT / "artifact-hashes.json").read_bytes()),
        "parentFilesVerified": len(parent_hashes),
        "newProviderRequestsByArchive": 0, "businessTestsRunByArchive": False,
        "newPhaseFiles": len(new_phase_files), "budget": budget,
        "trace142": {
            "prior": "../me00a-2026-10-12/traces/142.json", "completed": "traces/142.json",
            "priorSha256": sha256((PARENT / "traces/142.json").read_bytes()),
            "sharedRequestFieldsUnchanged": True,
            "addedFields": sorted(set(completed_142) - set(old_142)),
        },
        "historicalEvidenceBoundary": "Source freeze and saved test reports are historical. No fresh model or business-test success is claimed.",
        "entries": entries,
        "generatedFiles": ["README.md", "manifest.json", "artifact-hashes.json"],
    }
    payload["manifest.json"] = json_bytes(manifest)
    payload["artifact-hashes.json"] = json_bytes({name: sha256(data) for name, data in sorted(payload.items())})
    for name, data in payload.items():
        scan_content(name, data)
    return payload, manifest


def verify_archive() -> dict:
    """Verify exact output, linked sources, trace budget and unchanged parent files."""
    verify_parent()
    hashes = read_json(DESTINATION / "artifact-hashes.json")
    actual_files = {path.relative_to(DESTINATION).as_posix()
                    for path in DESTINATION.rglob("*") if path.is_file()}
    if actual_files != set(hashes) | {"artifact-hashes.json"}:
        raise ValueError("Continuation contains missing or unexpected files")
    for name, digest in hashes.items():
        path = (DESTINATION / name).resolve()
        if not path.is_relative_to(DESTINATION.resolve()) or sha256(path.read_bytes()) != digest:
            raise ValueError(f"Continuation hash mismatch: {name}")
        scan_content(name, path.read_bytes())
    manifest = read_json(DESTINATION / "manifest.json")
    for entry in manifest["entries"]:
        if sha256((REPO / entry["source"]).read_bytes()) != entry["sourceSha256"]:
            raise ValueError(f"Source changed since archive: {entry['source']}")
        if hashes[entry["target"]] != entry["targetSha256"]:
            raise ValueError(f"Manifest target mismatch: {entry['target']}")
    if budget_snapshot() != manifest["budget"]:
        raise ValueError("Budget differs from the frozen archive")
    return {"filesVerified": len(hashes), "parentFilesVerified": 564,
            "actualCalls": 169, "candidateCalls": 99,
            "newModelCalls": 0, "existingEvidenceOverwritten": False}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verify", action="store_true", help="Verify only; write nothing")
    options = parser.parse_args()
    if not options.verify and not DESTINATION.exists():
        payload, _ = build_payload()
        DESTINATION.mkdir(parents=True, exist_ok=False)
        for relative_path, content in payload.items():
            target = DESTINATION / relative_path
            target.parent.mkdir(parents=True, exist_ok=True)
            with target.open("xb") as output:
                output.write(content)
    print(json.dumps(verify_archive(), ensure_ascii=False))


if __name__ == "__main__":
    main()
