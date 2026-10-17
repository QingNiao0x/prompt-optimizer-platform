"""编译保存的完整代码与模型测试，再运行独立契约检查；不修改模型算法或沿用其自报通过。"""
from pathlib import Path
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[4]
PUBLIC = Path(__file__).resolve().parent
WORK = ROOT / "tmp/me00a-candidate31-live-20261017"
NS = "http://maven.apache.org/POM/4.0.0"


def extract(text):
    """仅接受冻结工作区的控制器和JUnit文件；先核对全部路径再写盘。"""
    files = {}
    for match in re.finditer(r"```java[^\n]*\n(.*?)```", text, re.S):
        before = text[max(0, match.start() - 220):match.start()]
        block = match.group(1)
        named = re.findall(r"((?:src/(?:main|test)/java/)[A-Za-z0-9_./-]+\.java)", before + "\n" + block.split("\n", 1)[0])
        if not named:
            raise ValueError("Java code block has no explicit workspace path")
        name = named[-1]
        if name != "src/main/java/sample/orders/user/LoginController.java" and not re.fullmatch(
                r"src/test/java/sample/orders/user/[A-Za-z0-9_]+Test\.java", name):
            raise ValueError("Output path outside frozen change scope")
        if ".." in name or name in files or name.endswith("/IndependentLoginAcceptanceTest.java"):
            raise ValueError("Unsafe, duplicate or reserved path")
        if not re.search(r"package\s+sample\.orders\.user\s*;", block):
            raise ValueError("Unexpected package")
        if re.search(r"System\.getenv|ProcessBuilder|Runtime\.getRuntime|java\.net|java\.nio\.file|DriverManager|Class\.forName", block):
            raise ValueError("Generated code requires execution outside the synthetic local fixture")
        files[name] = block
    if "src/main/java/sample/orders/user/LoginController.java" not in files or not any("/test/" in name for name in files):
        raise ValueError("Implementation and executable test files are both required")
    return files


def run(label, files):
    """使用原工程依赖的隔离POM；子进程环境不继承模型密钥或短信凭据。"""
    if label not in {"RAW", "PLAN", "CONTROL_GOOD", "CONTROL_BAD"}:
        raise ValueError("Unknown frozen arm")
    target = WORK / ("compiled-" + label)
    target.mkdir(exist_ok=False)
    lock = json.loads((PUBLIC / "execution-project-lock.json").read_text(encoding="utf-8"))
    for row in lock["files"]:
        source = PUBLIC / "execution-project" / row["path"]
        assert hashlib.sha256(source.read_bytes()).hexdigest() == row["sha256"]
        destination = target / row["path"]
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, destination)
    for name, content in files.items():
        destination = target / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(content, encoding="utf-8")
    tests = target / "src/test/java/sample/orders/user"
    tests.mkdir(parents=True, exist_ok=True)
    shutil.copy2(PUBLIC / "IndependentLoginAcceptanceTest.java", tests / "IndependentLoginAcceptanceTest.java")
    ET.register_namespace("", NS)
    tree = ET.parse(ROOT / "services/api/pom.xml")
    build = tree.getroot().find(f"{{{NS}}}build")
    for name, path in [("directory", target / "build"), ("sourceDirectory", target / "src/main/java"),
                       ("testSourceDirectory", target / "src/test/java")]:
        for old in build.findall(f"{{{NS}}}{name}"):
            build.remove(old)
        ET.SubElement(build, f"{{{NS}}}{name}").text = path.as_posix()
    # 不将平台application.yml或真实业务测试资源放进这个合成执行环境。
    for name in ("resources", "testResources"):
        for old in build.findall(f"{{{NS}}}{name}"):
            build.remove(old)
    tree.write(target / "pom.xml", encoding="utf-8", xml_declaration=True)
    java = "E:/Delelop/Java/jdk-21"
    args = ["E:/Delelop/apache-maven-3.9.14/bin/mvn.cmd", "-o", "-f", str(target / "pom.xml"),
            "-Dmaven.compiler.fork=true", f"-Dmaven.compiler.executable={java}/bin/javac.exe", f"-Djvm={java}/bin/java.exe",
            "-DargLine=-javaagent:E:/Delelop/localRepository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar -Dfile.encoding=UTF-8 -XX:ActiveProcessorCount=2",
            "test"]
    allowed = {"PATH", "PATHEXT", "SYSTEMROOT", "WINDIR", "COMSPEC", "USERPROFILE", "APPDATA", "LOCALAPPDATA", "TEMP", "TMP"}
    env = {name: value for name, value in os.environ.items() if name.upper() in allowed}
    env["JAVA_HOME"] = java
    with (target / "maven.log").open("wb") as log:
        completed = subprocess.run(args, cwd=target, env=env, stdout=log, stderr=subprocess.STDOUT, timeout=240)
    reports = []
    for path in (target / "build/surefire-reports").glob("TEST-*.xml"):
        suite = ET.parse(path).getroot()
        reports.append({"class": suite.attrib["name"], **{key: int(suite.attrib[key]) for key in ("tests", "failures", "errors", "skipped")}})
    log = (target / "maven.log").read_text(encoding="utf-8", errors="replace")
    passed = completed.returncode == 0 and "BUILD SUCCESS" in log and bool(reports) and all(
            row["failures"] == row["errors"] == row["skipped"] == 0 for row in reports)
    result = {"arm": label, "passed": passed, "mavenExit": completed.returncode, "reports": reports,
              "implementationAndModelTestsCompiledTogether": True, "sourceFiles": [
                  {"path": name, "sha256": hashlib.sha256(content.encode()).hexdigest()} for name, content in files.items()],
              "modelAlgorithmRepaired": False, "realModelCalls": 0, "databaseIntegrationCovered": False}
    (target / "result.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    return result


def main():
    label = sys.argv[1]
    if label in {"RAW", "PLAN"}:
        files = extract((WORK / "works" / label / "work.md").read_text(encoding="utf-8"))
    else:
        name = "src/main/java/sample/orders/user/LoginController.java"
        body = (PUBLIC / "execution-project" / name).read_text(encoding="utf-8")
        if label == "CONTROL_GOOD":
            body = body.replace('throw new UnsupportedOperationException("Pending implementation");', '''
        if (request == null || request.accountName() == null || request.accountName().isBlank()
                || request.password() == null || request.password().isBlank()) {
            return ResponseEntity.badRequest().body(new ApiEnvelope<>("INVALID_INPUT", null, "Invalid input"));
        }
        var user = users.findByAccountName(request.accountName()).orElse(null);
        if (user == null || !user.enabled() || !passwords.matches(request.password(), user.passwordHash())) {
            return ResponseEntity.status(401).body(new ApiEnvelope<>("AUTH_FAILED", null, "Authentication failed"));
        }
        return ResponseEntity.ok(new ApiEnvelope<>("OK", new LoginResult(tokens.issue(user.id()), "Bearer", user.id()), "Success"));
''')
        files = {name: body}
    result = run(label, files)
    print(json.dumps({"arm": label, "passed": result["passed"], "mavenExit": result["mavenExit"], "reports": result["reports"]}))


if __name__ == "__main__":
    main()
