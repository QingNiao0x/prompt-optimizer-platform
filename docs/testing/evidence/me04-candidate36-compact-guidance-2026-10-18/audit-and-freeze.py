"""Audit saved offline replays and freeze candidate36; never send HTTP or model requests."""
from datetime import datetime, timezone
from pathlib import Path
import hashlib
import json
import shutil
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[4]
PUBLIC = Path(__file__).resolve().parent
PRIVATE = ROOT / 'tmp/me04-candidate36-20261018'
BUILD = PRIVATE / 'backend-tests/build'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def write_new(path, data):
    with path.open('x', encoding='utf-8', newline='\n') as stream:
        stream.write(json.dumps(data, ensure_ascii=False, indent=2) + '\n')


def tests(folder):
    rows = []
    for path in sorted(folder.glob('TEST-*.xml')):
        suite = ET.parse(path).getroot()
        rows.append({'name': suite.attrib['name'], 'sha256': sha(path),
                     **{k: int(suite.attrib[k]) for k in ('tests', 'failures', 'errors', 'skipped')}})
    return {'suites': rows, **{k: sum(row[k] for row in rows) for k in ('tests', 'failures', 'errors', 'skipped')}}


def audit_replays():
    before_dir, after_dir = PRIVATE / 'replay-before', PRIVATE / 'replay-after-final'
    before, after = load(before_dir / 'summary.json'), load(after_dir / 'summary.json')
    assert before['fixtureSha256'] == after['fixtureSha256']
    rows = []
    for left, right in zip(before['cases'], after['cases'], strict=True):
        assert left['id'] == right['id']
        name = left['id']
        x, y = load(before_dir / name / 'result.json'), load(after_dir / name / 'result.json')
        old_request = load(before_dir / name / 'constructed-request.json')
        new_request = load(after_dir / name / 'constructed-request.json')
        old_user = old_request['messages'][1]['content']
        new_user = new_request['messages'][1]['content']
        old_payload, new_payload = json.loads(old_user[old_user.index('{'):]), json.loads(new_user[new_user.index('{'):])
        old_instruction = old_payload['rewriteStrategy'].pop('instruction')
        assert old_payload == new_payload, 'Only instruction metadata may disappear from the user payload'
        assert old_instruction in old_request['messages'][0]['content']
        assert 'instruction' not in new_payload['rewriteStrategy']
        assert left['modelResponseSha256'] == right['modelResponseSha256']
        assert x['constraintGroups'] == y['constraintGroups'] and x['appliedConstraints'] == y['appliedConstraints']
        assert x['ambiguities'] == y['ambiguities']
        for side, folder in ((left, before_dir), (right, after_dir)):
            assert sha(folder / name / 'model-response-raw.json') == side['modelResponseSha256']
            assert sha(folder / name / 'optimized-prompt.md') == side['bodySha256']
        assert all(rule in y['optimizedPrompt'] for rule in y['constraintGroups']['platformMandatory'])
        assert x['sections'] == y['sections'] or left['trace'] in (383, 389)
        if left['trace'] == 389:
            assert '任务相关约束：\n- ' in x['optimizedPrompt']
            assert '\n- 为核心逻辑补充正常、异常和边界场景测试。' in x['optimizedPrompt']
            assert '\n- 为核心逻辑补充正常、异常和边界场景测试。' not in y['optimizedPrompt']
        # 旧模型语义错误保留，不能用新指导或重组装声明它们已修复。
        assert left['containsOldUnsupportedAbsence'] == right['containsOldUnsupportedAbsence']
        assert left['containsOldMergeContradiction'] == right['containsOldMergeContradiction']
        rows.append({'trace': left['trace'], 'id': name, 'modelResponseSha256': right['modelResponseSha256'],
                     'beforeBodyCodePoints': left['bodyCodePoints'], 'afterBodyCodePoints': right['bodyCodePoints'],
                     'bodyCodePointReduction': left['bodyCodePoints'] - right['bodyCodePoints'],
                     'beforeConstructedRequestUtf8Bytes': left['constructedRequestUtf8Bytes'],
                     'afterConstructedRequestUtf8Bytes': right['constructedRequestUtf8Bytes'],
                     'constructedRequestByteReduction': left['constructedRequestUtf8Bytes'] - right['constructedRequestUtf8Bytes'],
                     'sameUserTaskContextAndOptions': True, 'sameRuleSourcesAndPendingState': True,
                     'mandatoryRulesComplete': True, 'newUserPayloadContainsStrategyInstruction': False,
                     'oldUnsupportedAbsenceStillPresent': right['containsOldUnsupportedAbsence'],
                     'oldMergeContradictionStillPresent': right['containsOldMergeContradiction'],
                     'beforeBodySha256': left['bodySha256'], 'afterBodySha256': right['bodySha256']})
    assert len(rows) == 7
    return {'evidenceType': 'OFFLINE_SAVED_MODEL_ASSEMBLY_AND_REQUEST_CONSTRUCTION', 'newModelCalls': 0,
            'newDownstreamWorks': 0, 'actualClipboardOrDatabaseVerified': False,
            'beforeSummarySha256': sha(before_dir / 'summary.json'), 'afterSummarySha256': sha(after_dir / 'summary.json'),
            'fixtureSha256': before['fixtureSha256'], 'cases': rows,
            'limits': 'UTF-8 request bytes are not tokens, latency or measured cost. Replaying old responses does not test new guidance efficacy.'}


def main():
    java = tests(BUILD / 'surefire-reports')
    assert len(java['suites']) == 21 and java['tests'] > 0
    assert java['failures'] == java['errors'] == java['skipped'] == 0
    replay = audit_replays()
    snapshot = PRIVATE / 'frozen'
    assert not snapshot.exists(), 'Frozen candidates cannot be overwritten'
    sources = [{'path': path.relative_to(ROOT).as_posix(), 'sha256': sha(path)}
               for base in ('services/api/src', 'apps/web/src', 'apps/web/e2e')
               for path in sorted((ROOT / base).rglob('*')) if path.is_file()]
    parent_path = ROOT / 'docs/testing/evidence/me04-candidate35-platform-rules-2026-10-18/candidate35-source.json'
    parent = load(parent_path)
    old = {row['path']: row['sha256'] for row in parent['sourceFiles']}
    changed = [row['path'] for row in sources if old.get(row['path']) != row['sha256']]
    frontend = [row for row in sources if row['path'].startswith('apps/web/')]
    assert all(old.get(row['path']) == row['sha256'] for row in frontend)
    for row in sources:
        destination = snapshot / 'source' / row['path']
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(ROOT / row['path'], destination)
    shutil.copytree(BUILD / 'classes', snapshot / 'classes')
    runtime = [{'path': path.relative_to(BUILD / 'classes').as_posix(), 'sha256': sha(path)}
               for path in sorted((BUILD / 'classes').rglob('*')) if path.is_file()]
    manifest = {'version': 'candidate36', 'baseHead': subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip(),
                'frozenAt': datetime.now(timezone.utc).isoformat(), 'parentCandidate': 'candidate35',
                'parentManifestSha256': sha(parent_path), 'parentCodeSnapshot': 'parent-production-source.json in private evidence',
                'sourceSnapshot': (snapshot / 'source').relative_to(ROOT).as_posix(),
                'runtimeSnapshot': (snapshot / 'classes').relative_to(ROOT).as_posix(),
                'sourceFiles': sources, 'runtimeFiles': runtime, 'changedSourcePathsSinceCandidate35': changed,
                'policyFiles': [row for row in sources if row['path'] in changed and row['path'].startswith('services/api/src/main/')]}
    write_new(PUBLIC / 'candidate36-source.json', manifest)
    write_new(PUBLIC / 'replay-comparison.json', replay)
    write_new(PUBLIC / 'verification.json', {'candidate': 'candidate36', 'java': java,
              'redReproduction': {'initial': tests(PRIVATE / 'backend-tests/red-reports'),
                                   'terminator': tests(PRIVATE / 'backend-tests/terminator-red-reports')},
              'frontend': {'unchangedHashedFiles': len(frontend), 'newTestRuns': 0,
                           'reference': 'candidate35 80 unit and 14 controlled-browser checks are historical, not recounted'},
              'offlineReplayedCases': 7, 'newModelCalls': 0, 'newDownstreamWorks': 0, 'newAIReviews': 0,
              'qualityGate': 'OPEN_PENDING_NEW_GUIDANCE_AND_WORK_EVALUATION',
              'toolFailures': ['First JVM command split an unquoted -D argument in PowerShell; no main method ran.',
                               'Default RestClient initialization required sandbox-blocked loopback; replaced with a request factory that throws on any HTTP attempt.'],
              'previousResultsNotAdded': True})
    write_new(PUBLIC / 'analysis-tools.json', {'newModelCalls': 0, 'files': [
        {'path': path.relative_to(ROOT).as_posix(), 'sha256': sha(path)} for path in
        (PUBLIC / 'audit-and-freeze.py', PUBLIC / 'Candidate36Replay.java',
         ROOT / 'services/api/src/test/resources/fixtures/candidate36-existing-model-replay.json')]})
    print(json.dumps({'candidate': 'candidate36', 'javaTests': java['tests'], 'suites': len(java['suites']),
                      'sourceFiles': len(sources), 'runtimeFiles': len(runtime), 'newModelCalls': 0,
                      'replayBodyReductions': {row['trace']: row['bodyCodePointReduction'] for row in replay['cases']},
                      'sourceManifestSha256': sha(PUBLIC / 'candidate36-source.json')}))


if __name__ == '__main__':
    main()
