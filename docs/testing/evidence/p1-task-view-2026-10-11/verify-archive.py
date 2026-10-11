"""Verify frozen local evidence only; no model, network, database or shell call."""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parents[3]


def read(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    index = read(ROOT / "artifact-index.json")
    for item in index["files"]:
        path = (ROOT / item["path"]).resolve()
        if not path.is_relative_to(ROOT.resolve()) or sha(path) != item["sha256"] or path.stat().st_size != item["bytes"]:
            raise ValueError("Archive content changed")
    listed = {item["path"] for item in index["files"]}
    actual = {p.relative_to(ROOT).as_posix() for p in ROOT.rglob("*") if p.is_file() and p.name != "artifact-index.json"}
    if actual != listed:
        raise ValueError("Archive file set changed")
    for path in ROOT.rglob("*.json"):
        read(path)
    audit = read(ROOT / "execution-audit-final.json")
    for pair in audit["finalPairs"]:
        generation, work, old = [read(ROOT / pair[key]) for key in ("generation", "work", "oldWork")]
        if generation["optimizedPromptSha256"] != work["promptSha256"] or work["settings"] != old["settings"]:
            raise ValueError("Final copied prompt and work settings differ")
        text = generation["result"]["optimizedPrompt"].encode("utf8")
        if hashlib.sha256(text).hexdigest() != work["promptSha256"]:
            raise ValueError("Final copied prompt text differs")
        output = ROOT / Path(pair["work"]).with_suffix(".md")
        if sha(output) != work["outputSha256"]:
            raise ValueError("Downstream work changed")
    changed = [name for name, expected in read(ROOT / "candidate-v5-source.json")["files"].items()
               if sha(REPO / "services/api/src/main" / name) != expected]
    if changed:
        raise ValueError("Current main source differs from frozen candidate")
    print(json.dumps({"status": "VERIFIED", "files": len(listed), "jsonFiles": len(list(ROOT.rglob('*.json'))),
                      "finalPairs": len(audit["finalPairs"]), "currentMainMatchesFreeze": True, "networkCalls": 0}, ensure_ascii=False))


if __name__ == "__main__":
    main()
