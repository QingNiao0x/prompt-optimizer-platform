"""Copy immutable synthetic evaluation evidence and the repair delta into the repository."""
from pathlib import Path
import difflib
import hashlib
import json
import shutil

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parent.parent
DEST = REPO / "docs/testing/evidence/requirement-boundaries-2026-10-11"
assert not DEST.exists(), "archive must not overwrite prior evidence"
DEST.mkdir(parents=True)

for path in ROOT.iterdir():
    if path.is_file() and path.suffix in {".json", ".log", ".java", ".ps1", ".py", ".xml"}:
        shutil.copy2(path, DEST / path.name)
    elif path.is_dir() and (path.name.startswith(("works-", "final-", "release-age-", "release-method-", "candidate-"))
                           and path.name not in {"candidate-target", "candidate-v1", "candidate-v2", "candidate-final"}):
        # Run directories contain only claims, traces, manifests and copied synthetic prompts.
        shutil.copytree(path, DEST / path.name)

reports = ROOT / "candidate-target/surefire-reports"
shutil.copytree(reports, DEST / "surefire-reports")
baseline = ROOT / "before/src/main"
main = REPO / "services/api/src/main"
source_files = []
patch = []
for path in main.rglob("*"):
    if not path.is_file():
        continue
    relative = path.relative_to(main)
    old = baseline / relative
    if old.exists() and old.read_bytes() == path.read_bytes():
        continue
    if relative.as_posix().startswith("java/com/promptoptimizer/identity/"):
        continue  # observed external work, not part of this enhancement repair
    target = DEST / "source-changes/services/api/src/main" / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(path, target)
    source_files.append(path.relative_to(REPO).as_posix())
    before = old.read_text(encoding="utf-8").splitlines(keepends=True) if old.exists() else []
    after = path.read_text(encoding="utf-8").splitlines(keepends=True)
    patch.extend(difflib.unified_diff(before, after, fromfile="before/" + path.relative_to(REPO).as_posix(),
                                   tofile="after/" + path.relative_to(REPO).as_posix()))
for name in ["enhancement/service/impl/RequirementBoundaryRegressionTest.java",
             "provider/infrastructure/openai/RequirementBoundaryProviderTest.java"]:
    path = REPO / "services/api/src/test/java/com/promptoptimizer" / name
    target = DEST / "source-changes" / path.relative_to(REPO)
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(path, target)
    source_files.append(path.relative_to(REPO).as_posix())
(DEST / "repair-source-delta.patch").write_text("".join(patch), encoding="utf-8")

original = REPO / "docs/testing/evidence/p1-task-view-2026-10-11"
selected = [
    "candidate-v4-live/QB-08-generation-trace-1.json", "candidate-v5-replay-rule/QB-08-generation.json",
    "candidate-v4-live/QB-08-direct-work.json", "candidate-v5-live/QB-06-generation.json",
    "candidate-v5-live/QB-06-direct-work.json", "candidate-v5-replay-pro/QB-02-generation.json",
    "candidate-v3-pro/QB-02-direct-work.json", "quality-review.json",
]
for name in selected:
    target = DEST / "original-p1" / name
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(original / name, target)
(DEST / "README.md").write_text("""# 需求保真边界修复证据

只读验证：`python verify-archive.py`，不调用外部模型。

- `original-p1/`：原 P1 分层原件与旧审核标签，保留用于更正归因。
- `candidate-*`、`final-age-*`、`final-data-*`：实际增强及各次尝试；最终数据范围版本为 `final-data-*`。
- `release-age-*`、`release-method-*`：最终源码的完整请求一致重放，零网络调用。
- `works-*`：全部18份真实作品，含先前候选；最终12份的准确映射以 `final-work-input-proof.json` 为准。
- `real-call-audit.json`：只统计真实 transport，30次请求及119694 Token。
- `final-test-summary.json`、`surefire-reports/`：相关回归，1740通过、6跳过，并发类排除。
- `source-changes/`、`repair-source-delta.patch`：本轮增强修复源码及测试，与此前未提交P1区分。
- `release-candidate-source.json`：评测主源码摘要；外部邮箱存储改动见独立差异记录。
- `protocol*.json`：采样前登记的固定范围和发现新问题后的有限补验。

归档工具沿用原执行路径，付费重跑须使用已授权范围并另建输出，不能覆盖本目录。
详情及人工审核结论见上级需求保真边界报告，不能由HTTP成功推断业务通过。
""", encoding="utf-8")

files = {path.relative_to(DEST).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest()
         for path in DEST.rglob("*") if path.is_file()}
(DEST / "artifact-index.json").write_text(json.dumps({"files": files, "repairSourceFiles": source_files,
                                                    "selfExcluded": "artifact-index.json"}, indent=2), encoding="utf-8")
print(json.dumps({"archive": str(DEST), "files": len(files), "repairSourceFiles": len(source_files)}))
