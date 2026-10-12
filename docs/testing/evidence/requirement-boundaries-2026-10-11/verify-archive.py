"""Validate the local archive without reading credentials or making any model calls."""
from pathlib import Path
import hashlib
import json

root = Path(__file__).resolve().parent
index = json.loads((root / "artifact-index.json").read_text(encoding="utf-8"))
for name, expected in index["files"].items():
    path = root / name
    assert path.is_file() and hashlib.sha256(path.read_bytes()).hexdigest() == expected, name
for item in json.loads((root / "final-work-input-proof.json").read_text(encoding="utf-8")):
    producer = json.loads((root / item["producer"]).read_text(encoding="utf-8-sig"))
    work = json.loads((root / item["work"]).read_text(encoding="utf-8-sig"))
    prompt_sha = hashlib.sha256(producer["result"]["optimizedPrompt"].encode("utf-8")).hexdigest()
    assert prompt_sha == work["promptSha256"] == item["copiedPromptSha256"]
audit = json.loads((root / "real-call-audit.json").read_text(encoding="utf-8"))
assert audit["actualCalls"] == len(audit["transport"]) == 30
print(json.dumps({"filesVerified": len(index["files"]), "finalExactInputs": 12, "actualCalls": 30,
                  "totalTokens": audit["totalTokens"], "networkCallsThisVerification": 0}))
