"""Tool self-checks use synthetic correct/wrong programs; they are not model work or business acceptance."""
from pathlib import Path
import importlib.util
import json
import sys
from concurrent.futures import ThreadPoolExecutor

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[3]
WORK = REPO / "tmp/me04-candidate33-20261018/patch-checker-selftest"
SPEC = importlib.util.spec_from_file_location("patch_checker", HERE / "check-patch-work.py")
sys.dont_write_bytecode = True
CHECKER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECKER)

CORRECT_BODY = """
        Map<String,Object> result = new LinkedHashMap<>();
        if (current != null) result.putAll(current);
        if (patch != null) for (var entry : patch.entrySet())
            if (entry.getValue() != null) result.put(entry.getKey(), entry.getValue());
        return result;
"""
WEAK_TEST = """import java.util.*;
public class PatchMergerTest {
    public static void main(String[] args) {
        var result = PatchMerger.merge(new LinkedHashMap<>(Map.of("a",1)), Map.of("b",2));
        if (!Integer.valueOf(2).equals(result.get("b"))) throw new AssertionError("normal merge");
    }
}
"""
DECLARATION = "Design: null inputs act as empty maps; empty maps are valid; nested values are replaced shallowly."


def fixture(name: str, body: str, *, test: str = WEAK_TEST, missing_test: bool = False,
            package: bool = False, third_party: bool = False) -> tuple[str, dict]:
    directory = WORK / name
    directory.mkdir(parents=True, exist_ok=True)
    prefix = "package generated.fixture;\n" if package else ""
    source = prefix + "import java.util.*;\n" + ("import example.external.Missing;\n" if third_party else "")
    source += "public class PatchMerger { public static Map<String,Object> merge(Map<String,Object> current, Map<String,Object> patch) {\n" + body + "\n}}\n"
    document = DECLARATION + "\n\n```java\n" + source + "```\n"
    if not missing_test:
        document += "\n```java\n" + prefix + test + "```\n"
    work = directory / "work.md"
    work.write_text(document, encoding="utf-8")
    policy = directory / "policy.json"
    policy.write_text(json.dumps({key: {"mode": mode, "evidence": DECLARATION} for key, mode in {
        "nullCurrent": "EMPTY_MAP", "nullPatch": "EMPTY_MAP", "emptyCurrent": "ALLOW", "emptyPatch": "ALLOW", "nested": "SHALLOW_REPLACE"}.items()}), encoding="utf-8")
    result = CHECKER.evaluate(work.resolve(), (directory / "checks").resolve(), policy)
    return name, result


def main() -> None:
    WORK.mkdir(parents=True, exist_ok=True)
    cases = [
        ("correct", CORRECT_BODY, {}),
        ("correct-packaged", CORRECT_BODY, {"package": True}),
        ("correct-literal-test-exit", CORRECT_BODY, {"test": WEAK_TEST.replace('throw new AssertionError("normal merge");', 'System.exit(1);')}),
        ("wrong-null-overwrite", "var result = new LinkedHashMap<String,Object>(current); result.putAll(patch); return result;", {}),
        ("wrong-falsy-drop", CORRECT_BODY.replace("entry.getValue() != null", "entry.getValue() != null && !entry.getValue().equals(0) && !entry.getValue().equals(false) && !entry.getValue().equals(\"\")"), {}),
        ("wrong-input-mutation", "if (patch != null) current.putAll(patch); return current;", {}),
        ("wrong-input-printing", "System.out.println(current); System.err.println(patch);\n" + CORRECT_BODY, {}),
        ("missing-generated-test", CORRECT_BODY, {"missing_test": True}),
        ("third-party-import", CORRECT_BODY, {"third_party": True}),
        ("assert-only-generated-test", CORRECT_BODY, {"test": "public class PatchMergerTest { public static void main(String[] args) { assert false : \"disabled assertion\"; } }\n"}),
    ]
    with ThreadPoolExecutor(max_workers=2) as executor:
        results = dict(executor.map(lambda item: fixture(item[0], item[1], **item[2]), cases))
    checks = []

    def verify(name: str, condition: bool) -> None:
        checks.append({"name": name, "passed": bool(condition)})

    for name in ["correct", "correct-packaged", "correct-literal-test-exit"]:
        verify(name + "_passes", results[name]["requiredCodeGatesPassed"])
    for name in ["wrong-null-overwrite", "wrong-falsy-drop", "wrong-input-mutation", "wrong-input-printing"]:
        verify(name + "_independent_rejects", results[name]["independentChecks"]["status"] == "FAIL")
        verify(name + "_weak_generated_test_does_not_mask_failure", results[name]["generatedTests"]["status"] == "PASS"
               and not results[name]["requiredCodeGatesPassed"])
    verify("missing_file_not_fabricated", results["missing-generated-test"]["extraction"]["status"] == "FAIL"
           and results["missing-generated-test"]["compilation"]["status"] == "NOT_RUN")
    verify("third_party_does_not_compile", results["third-party-import"]["compilation"]["status"] == "FAIL"
           and not results["third-party-import"]["staticChecks"]["thirdPartyImportGatePassed"])
    verify("assert_only_is_not_certified_by_zero_exit", results["assert-only-generated-test"]["generatedTests"]["status"] == "PASS"
           and results["assert-only-generated-test"]["requiresGeneratedTestReview"] and not results["assert-only-generated-test"]["requiredCodeGatesPassed"])
    outcome = {"syntheticFixtures": len(cases), "checks": checks, "passed": sum(c["passed"] for c in checks),
               "failed": sum(not c["passed"] for c in checks), "externalModelCalls": 0,
               "results": {name: str(WORK / name / "checks/result.json") for name in results},
               "businessAcceptance": False}
    (WORK / "verification.json").write_text(json.dumps(outcome, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: v for k, v in outcome.items() if k != "results"}))
    if outcome["failed"]:
        sys.exit(1)


if __name__ == "__main__":
    main()
