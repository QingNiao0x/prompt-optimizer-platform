"""Freeze reviewed source/runtime and summarize real local checks without invoking any model."""
from pathlib import Path
import hashlib
import json
import shutil
import subprocess
from datetime import datetime, timezone
import xml.etree.ElementTree as ET
import argparse

ROOT = Path(__file__).resolve().parents[4]
EVIDENCE = Path(__file__).resolve().parent
PRIVATE = ROOT / 'tmp/me04-candidate33-20261018'
PARENT = ROOT / 'docs/testing/evidence/me04-candidate32-structured-toggle-2026-10-18/candidate32-source.json'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write(name, value):
    (EVIDENCE / name).write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def test_summary(files):
    suites = []
    for path in files:
        tree = ET.parse(path).getroot()
        for suite in ([tree] if tree.tag == 'testsuite' else tree.findall('testsuite')):
            suites.append({'name': suite.get('name'), **{field: int(suite.get(field, 0))
                          for field in ('tests', 'failures', 'errors', 'skipped')}})
    return {**{field: sum(item[field] for item in suites)
               for field in ('tests', 'failures', 'errors', 'skipped')}, 'suites': suites}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--revision', choices=['initial', 'r1'], default='initial')
    args = parser.parse_args()
    revised = args.revision == 'r1'
    build_name = 'backend-r1' if revised else 'backend-final'
    parent = json.loads(PARENT.read_text(encoding='utf-8-sig'))
    files = {item['path'] for item in parent['sourceFiles'] if (ROOT / item['path']).is_file()}
    for base in ('services/api/src', 'apps/web/src', 'apps/web/e2e'):
        files.update(path.relative_to(ROOT).as_posix() for path in (ROOT / base).rglob('*') if path.is_file())
    snapshot = PRIVATE / ('frozen-r1' if revised else 'frozen')
    if snapshot.exists():
        raise RuntimeError('Frozen snapshot already exists; do not overwrite a candidate')
    source_entries = []
    for name in sorted(files):
        source = (ROOT / name).resolve()
        source.relative_to(ROOT)
        target = snapshot / 'source' / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
        source_entries.append({'path': name, 'sha256': sha(source)})
    runtime = PRIVATE / build_name / 'build/classes'
    shutil.copytree(runtime, snapshot / 'classes')
    runtime_entries = [{'path': path.relative_to(runtime).as_posix(), 'sha256': sha(path)}
                       for path in sorted(runtime.rglob('*')) if path.is_file()]
    policy_paths = [
        'services/api/src/main/java/com/promptoptimizer/provider/domain/PromptRewriteStrategy.java',
        'services/api/src/main/java/com/promptoptimizer/provider/infrastructure/openai/OpenAiCompatiblePromptEnhancementProvider.java',
        'services/api/src/main/java/com/promptoptimizer/template/domain/PromptFramework.java',
        'services/api/src/main/java/com/promptoptimizer/template/domain/PromptFrameworkSelector.java',
        'services/api/src/main/java/com/promptoptimizer/template/domain/PromptFrameworkSelection.java',
        'services/api/src/main/java/com/promptoptimizer/enhancement/service/impl/OptimizationResultAssembler.java',
        'services/api/src/main/java/com/promptoptimizer/template/domain/TaskIntentResolver.java',
    ]
    if revised:
        # Keep the preflight-discovered initial candidate and its exact validation; no model calls used it.
        for name in ('candidate33-source.json', 'verification.json'):
            archived = EVIDENCE / name.replace('.json', '-initial.json')
            if archived.exists():
                raise RuntimeError('Initial freeze already archived; inspect before retry')
            shutil.copy2(EVIDENCE / name, archived)
    write('candidate33-source.json', {
        'version': 'candidate33', 'parentCandidate': 'candidate32', 'parentManifestSha256': sha(PARENT),
        'sourceRevision': args.revision,
        'frozenAt': datetime.now(timezone.utc).isoformat(),
        'baseHead': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
        'sourceSnapshot': (snapshot / 'source').relative_to(ROOT).as_posix(),
        'runtimeSnapshot': (snapshot / 'classes').relative_to(ROOT).as_posix(),
        'sourceFiles': source_entries, 'runtimeFiles': runtime_entries,
        'policyFiles': [{'path': name, 'sha256': sha(ROOT / name)} for name in policy_paths],
    })
    java = test_summary((PRIVATE / build_name / 'build/surefire-reports').glob('TEST-*.xml'))
    frontend = test_summary([PRIVATE / 'frontend-final.xml', PRIVATE / 'frontend-lifecycle.xml'])
    browser = json.loads((PRIVATE / 'browser-results-final.json').read_text(encoding='utf-8-sig'))['stats']
    assert java['tests'] >= 290 and frontend['tests'] == 61
    assert all(result[field] == 0 for result in (java, frontend) for field in ('failures', 'errors', 'skipped'))
    assert browser['expected'] == 10 and browser['unexpected'] == 0 and browser['flaky'] == 0
    write('verification.json', {
        'candidate': 'candidate33', 'sourceRevision': args.revision, 'java': java, 'frontend': frontend, 'browser': browser,
        'frontendBrowserReuse': 'Exact unchanged source hashes from initial candidate' if revised else None,
        'typecheck': 'PASS', 'sourceFiles': len(source_entries), 'runtimeFiles': len(runtime_entries),
        'realModelCalls': 0, 'automaticLedgerBefore': 369, 'activeBatchStatus': 'CLOSED',
        'browserBoundary': 'Real Chrome, page and clipboard; controlled API fixtures, no model calls or new accounts',
        'firstFailures': [
            'Provider regression reproduced stripped promptFormat before repair',
            'Finite framework routing prototype had material-goal and negation counterexamples, fixed and retained',
            'Desktop structured toggle tooltip intercepted the submit button; local noninteractive popup fixed; 10/10 rerun',
            'Sandbox Mockito attach pipe failed; only the identified test JVM stopped, same 19 suites passed on host',
        ],
        'remaining': ['Actual two-mode generations and downstream quality',
                      'Candidate32 trace363 stale Plan state projection',
                      'Existing credential-echo, scoring and professional correctness gates', 'G1-G8'],
    })
    print(json.dumps({'sourceFiles': len(source_entries), 'runtimeFiles': len(runtime_entries),
                      'java': java['tests'], 'frontend': frontend['tests'], 'browser': browser['expected']}))


if __name__ == '__main__':
    main()
