"""执行前核对冻结源码及输入字节，任何漂移均阻止调用。"""
import hashlib
import json
from pathlib import Path
root = Path(__file__).resolve().parents[3]
protocol = json.loads((root / "docs/testing/evidence/text-polish-two-stage-2026-11-09/protocol.json").read_text(encoding="utf-8"))
for name, expected in protocol["sha256"].items():
    assert hashlib.sha256((root / name).read_bytes()).hexdigest() == expected, name
print("FROZEN_SOURCE_AND_INPUTS_OK")
