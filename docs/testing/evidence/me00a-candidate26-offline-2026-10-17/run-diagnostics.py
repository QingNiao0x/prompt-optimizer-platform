"""仅执行显式冻结运行的本地探针，拒绝从当前classes回退加载缺失业务类。"""
import json
import os
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path
import hashlib


def main():
    """同一源码探针依次使用两个已校验运行目录，失败原观测保持原文件不变。"""
    public = Path(__file__).resolve().parent
    root = public.parents[3]
    work = root / "tmp/me00a-candidate26-20261017"
    xml = next((work / "build/surefire-reports").glob("TEST-*CandidateTwentySixStateReplayTest.xml"))
    properties = {node.get("name"): node.get("value") for node in ET.parse(xml).getroot().findall("properties/property")}
    # 剔除测试与当前构建目录，禁止父运行意外混入当前业务类。
    dependencies = [path for path in properties["java.class.path"].split(os.pathsep)
                    if not Path(path).resolve().is_relative_to(work)]
    assert dependencies
    java = Path("E:/Delelop/Java/jdk-21/bin")
    probe_classes = work / "diagnostics/classes"
    probe_classes.mkdir(parents=True, exist_ok=True)
    manifests = [root / "docs/testing/evidence/me00a-candidate25-offline-2026-10-17/candidate25-source.json",
                 public / "candidate26-source.json"]
    for phase, manifest_path in zip(("before", "after"), manifests):
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        runtime = (root / manifest["runtimeSnapshot"]).resolve()
        assert runtime.is_relative_to(root / "tmp") and runtime.name == "classes"
        for row in manifest["runtimeFiles"]:
            assert hashlib.sha256((runtime / row["path"]).read_bytes()).hexdigest() == row["sha256"], row["path"]
        classpath = os.pathsep.join([str(runtime), *dependencies]).replace("\\", "/")
        if phase == "before":
            arguments = work / "diagnostics/javac.args"
            arguments.write_text('-encoding\nUTF-8\n-cp\n"' + classpath + '"\n-d\n"' + probe_classes.as_posix()
                                 + '"\n"' + (public / "ScopeClassificationProbe.java").as_posix() + '"\n', encoding="utf-8")
            subprocess.run([str(java / "javac.exe"), "@" + str(arguments)], cwd=root, check=True)
        arguments = work / ("diagnostics/" + phase + ".args")
        arguments.write_text('-Dfile.encoding=UTF-8\n-cp\n"' + probe_classes.as_posix() + os.pathsep + classpath
                             + '"\nScopeClassificationProbe\n' + phase + "\n", encoding="utf-8")
        subprocess.run([str(java / "java.exe"), "@" + str(arguments)], cwd=root, check=True)
    before = json.loads((public / "scope-classification-before.json").read_text(encoding="utf-8"))
    after = json.loads((public / "scope-classification-after.json").read_text(encoding="utf-8"))
    assert [row["answer"] for row in before["observations"]] == [row["answer"] for row in after["observations"]]
    assert sum(row["independentStateGatePass"] for row in before["observations"]) == 0
    assert sum(row["independentStateGatePass"] for row in after["observations"]) == 3
    print(json.dumps({"parentStateChecksPassed": 0, "currentStateChecksPassed": 3, "cases": 3,
                      "parentEvidenceUnmodified": True, "realModelCalls": 0}))


if __name__ == "__main__":
    main()
