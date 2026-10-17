"""从当前生产POM生成隔离构建，不改依赖、业务配置或原始POM。"""
from pathlib import Path
import hashlib
import json
import xml.etree.ElementTree as ET


def main():
    """固定所有源码／资源入口，只把构建输出写入本候选私有目录。"""
    root = Path(__file__).resolve().parents[4]
    ns = "http://maven.apache.org/POM/4.0.0"
    ET.register_namespace("", ns)
    tree = ET.parse(root / "services/api/pom.xml")
    build = tree.getroot().find(f"{{{ns}}}build")
    if build is None:
        raise RuntimeError("Existing Maven build node missing")
    for name, relative in [("directory", "tmp/me00a-candidate27-r2-20261017/build"),
                           ("sourceDirectory", "services/api/src/main/java"),
                           ("testSourceDirectory", "services/api/src/test/java")]:
        for old in build.findall(f"{{{ns}}}{name}"):
            build.remove(old)
        ET.SubElement(build, f"{{{ns}}}{name}").text = (root / relative).as_posix()
    for collection, item, relative in [("resources", "resource", "services/api/src/main/resources"),
                                        ("testResources", "testResource", "services/api/src/test/resources")]:
        for old in build.findall(f"{{{ns}}}{collection}"):
            build.remove(old)
        node = ET.SubElement(ET.SubElement(build, f"{{{ns}}}{collection}"), f"{{{ns}}}{item}")
        ET.SubElement(node, f"{{{ns}}}directory").text = (root / relative).as_posix()
    work = root / "tmp/me00a-candidate27-r2-20261017"
    work.mkdir(exist_ok=True)
    tree.write(work / "pom.xml", encoding="utf-8", xml_declaration=True)
    parent = json.loads((root / "docs/testing/evidence/me00a-candidate26-r2-offline-2026-10-17/candidate26-r2-source.json").read_text(encoding="utf-8"))
    names = sorted({row["path"] for row in parent["sourceFiles"]} | {
        "services/api/src/test/java/com/promptoptimizer/enhancement/service/impl/CandidateTwentySevenReplayTest.java",
        "services/api/src/test/java/com/promptoptimizer/provider/infrastructure/openai/CandidateTwentySevenRepairProtocolTest.java"})
    # 在编译前固定输入，冻结时逐个核对，禁止把后来修改的源码冒充本次测试版本。
    rows = [{"path": name, "sha256": hashlib.sha256((root / name).read_bytes()).hexdigest()} for name in names]
    (work / "source-under-test.json").write_text(json.dumps(rows, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
