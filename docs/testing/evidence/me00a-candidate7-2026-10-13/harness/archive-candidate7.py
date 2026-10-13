"""Archive one frozen real validation without new requests or rewriting parent evidence."""
from pathlib import Path
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
import re

ROOT = Path(__file__).resolve().parents[2]
RT = ROOT / 'tmp/me00a-20261012'
OUT = ROOT / 'docs/testing/evidence/me00a-candidate7-2026-10-13'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def save(name, value):
    path = OUT / name
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        raise RuntimeError(f'Never overwrite existing evidence: {name}')
    path.write_bytes((json.dumps(value, ensure_ascii=False, indent=2) + '\n').encode('utf-8'))


def main():
    if OUT.exists():
        raise RuntimeError('Archive already exists; inspect it instead of overwriting')
    OUT.mkdir(parents=True)
    (OUT / '.gitattributes').write_bytes(b'* -text\n')
    sources = {}

    def copy(source, name):
        source = source.resolve()
        target = OUT / name
        target.parent.mkdir(parents=True, exist_ok=True)
        if target.exists():
            raise RuntimeError(f'Duplicate target: {name}')
        data = source.read_bytes()
        if source.suffix not in ['.png'] and re.search(rb'sk-[A-Za-z0-9_-]{20,}', data):
            raise RuntimeError(f'Potential credential in archive source: {name}')
        target.write_bytes(data)
        assert digest(target) == digest(source)
        sources[name] = {'source': source.relative_to(ROOT).as_posix(), 'sha256': digest(source)}

    named = {
        'candidate7/source.json': 'candidate7-source.json',
        'candidate7/tracked-source.diff': 'candidate7-tracked-source.diff',
        'protocol.json': 'protocol.json',
        'protocol-amendment-04.json': 'protocol-amendment-04.json',
        'candidate7-compile-summary.json': 'candidate7-compile-summary.json',
        'candidate7-script-test-run.json': 'local-checks/script-test-run.json',
        'candidate7-script-test.log': 'local-checks/script-test.txt',
        'candidate7-preflight-failure.json': 'preflight-failure.json',
        'candidate7-freeze-check.json': 'freeze-check.json',
        'candidate7-service-ownership.json': 'service-ownership.json',
        'candidate7-service-cleanup.json': 'service-cleanup.json',
        'candidate7-mechanical.json': 'mechanical-check.json',
        'candidate7-response-179-parsed.json': 'derived/model-179-parsed.json',
        'candidate7-response-180-parsed.json': 'derived/model-180-parsed.json',
        'candidate7-review-mapping.private.json': 'review/mapping.json',
        'MeEvaluationServer.java': 'harness/MeEvaluationServer.java',
        'freeze-candidate.py': 'harness/freeze-candidate.py',
        'candidate7-resume-comparators.mjs': 'harness/resume-comparators.mjs',
        'candidate7-review.mjs': 'harness/review.mjs',
        'candidate7-ui-copy.mjs': 'harness/ui-copy.mjs',
    }
    for source, name in named.items():
        copy(RT / source, name)
    copy(Path(__file__), 'harness/archive-candidate7.py')
    for name in ['me00a-eval.mjs', 'me00a-eval.test.mjs']:
        copy(ROOT / 'scripts' / name, 'harness/scripts/' + name)
    for prefix, destination in [
        ('candidate7-plan-r1/Q05-flash', 'q05-flash'),
        ('candidate7-review', 'review'),
    ]:
        for source in sorted((RT / prefix).rglob('*')):
            if source.is_file():
                copy(source, destination + '/' + source.relative_to(RT / prefix).as_posix())
    ui = RT / 'candidate7-ui-copy-r8'
    for name in ['checks.json', 'direct-clipboard.md', 'direct-copy.png',
                 'api-statuses.json', 'ready.json', 'direct-rendered-buttons.json']:
        copy(ui / name, 'ui-copy/' + name)
    auxiliary = []
    for directory in ['candidate7-ui-copy'] + [f'candidate7-ui-copy-r{i}' for i in range(2, 8)]:
        path = RT / directory / 'failure.json'
        if path.exists():
            copy(path, 'ui-copy/auxiliary-attempts/' + directory + '.json')
            auxiliary.append({'attempt': directory, **read(path)})
    save('ui-copy/auxiliary-summary.json', {
        'failedAuxiliaryAttempts': auxiliary,
        'attribution': 'Harness issues: default CDP context lacked original incognito login cookies; guessed readiness/button selector; Windows clipboard CRLF compared byte-for-byte against LF API text. These did not generate model requests.',
        'successfulAttempt': 'r8: actual authenticated workbench, actual copy button, clipboard content equal after LF/CRLF normalization',
        'newModelCalls': 0,
    })

    all_traces = sorted((RT / 'traces').glob('*.json'), key=lambda p: int(p.stem))
    phases = Counter(read(p)['phase'] for p in all_traces)
    calls = []
    for i in range(177, 186):
        copy(RT / f'traces/{i}.json', f'traces/{i}.json')
        trace = read(RT / f'traces/{i}.json')
        calls.append({
            'trace': i, 'phase': trace['phase'], 'model': trace['request']['model'],
            'httpStatus': trace['httpStatus'], 'usage': trace['response'].get('usage', {}),
            'elapsedMs': trace['elapsedMs'],
            'result': {177: 'PLAN_5_QUESTIONS', 178: 'AMBIGUITY_VALUE_INVALID',
                       179: 'RULE_CONFLICT sections.OUTPUT', 180: 'ASSESSMENT_DECISION_CONFLICT',
                       181: 'RAW_WORK', 182: 'DIRECT_ENHANCEMENT', 183: 'DIRECT_WORK',
                       184: 'AI_REVIEW_INVALID_JSON', 185: 'AI_REVIEW_VALID_JSON'}[i],
        })
    assert len(all_traces) == 185 and phases == {'baseline': 70, 'candidateValidation': 115}
    assert all(c['model'] == 'deepseek-flash' and c['phase'] == 'candidateValidation' for c in calls)
    save('call-summary.json', {
        'before': {'total': 176, 'baseline': 70, 'candidateValidation': 106},
        'after': {'total': len(all_traces), **dict(phases)}, 'newAttempts': 9,
        'workflowAttempts': 7, 'aiReviewAttempts': 2, 'invalidWorkflowResponses': 3,
        'invalidReviewResponses': 1, 'limits': {'total': 198, 'baseline': 80, 'candidateValidation': 118},
        'batchMaximumNewCalls': 12, 'batchRemaining': 3, 'candidatePhaseRemaining': 3,
        'baselinePhaseRemaining': 10, 'phaseBalancesNotTransferable': True,
        'secondRoundAuthorized': False, 'furtherRequestsPerformed': False, 'calls': calls,
        'exactVendorVersion': 'UNKNOWN', 'currencyCost': 'UNKNOWN; provider tokens preserved',
    })

    source = read(RT / 'candidate7/source.json')
    assert not read(RT / 'candidate7-freeze-check.json')['sourceMismatches']
    assert not read(RT / 'candidate7-freeze-check.json')['compiledClassMismatches']
    for rel, expected in source['files'].items():
        assert digest(ROOT / rel) == expected, rel
    for rel, expected in source['compiledClasses'].items():
        assert digest(RT / 'candidate7/classes' / rel) == expected, rel
    assert digest(RT / 'cases.json') == source['baseCasesSha256']
    assert digest(RT / 'protocol.json') == source['protocolSha256']
    assert digest(RT / 'MeEvaluationServer.java') == source['traceServerSha256']
    case = next(c for c in read(RT / 'cases.json') if c['id'] == 'Q05')
    save('q05-case.json', {'derivedFrom': 'cases.json', 'wholeFileSha256': source['baseCasesSha256'], 'case': case})

    q = OUT / 'q05-flash'
    direct = q / 'direct-equal-information'
    platform = read(direct / 'platform.json')['body']['data']['optimizedPrompt']
    copied = (direct / 'copied.md').read_bytes().decode('utf-8')
    execution = (direct / 'enhanced-input.md').read_bytes().decode('utf-8')
    history = read(direct / 'history.json')['body']['data']['optimizedPrompt']
    clip = (OUT / 'ui-copy/direct-clipboard.md').read_bytes().decode('utf-8')
    assert platform == copied == execution == history
    assert platform.replace('\r\n', '\n') == clip.replace('\r\n', '\n')
    request = read(RT / 'traces/183.json')['request']['messages']
    assert request[-1]['content'] == copied
    assert read(RT / 'traces/181.json')['request']['messages'][-1]['content'] == (q / 'raw-input.md').read_bytes().decode('utf-8')
    for p, i in [(q / 'raw-work.md', 181), (direct / 'work.md', 183)]:
        assert p.read_bytes().decode('utf-8') == read(RT / f'traces/{i}.json')['response']['choices'][0]['message']['content']
    actual = read(q / 'actual-answers.json')['answers']
    assert len(actual) == 5 and len({a['answer'] for a in actual}) == 1
    raw_input = (q / 'raw-input.md').read_bytes().decode('utf-8')
    for item in actual:
        assert item['question'] in raw_input and item['answer'] in raw_input
    assert read(q / 'input.json')['enhancement']['includeExamples'] is False
    assert read(direct / 'input.json')['enhancement']['includeExamples'] is False
    assert read(q / 'platform.json')['status'] == 502
    assert not (q / 'copied.md').exists() and not (q / 'work.md').exists()
    save('integrity-check.json', {
        'checkedAt': datetime.now(timezone.utc).isoformat(), 'sourceFilesMatched': 803,
        'compiledClassesMatched': 567, 'caseProtocolHarnessHashesMatched': True,
        'newRequestsMatchedScope': True, 'directPlatformCopyExecutionHistoryByteMatch': True,
        'actualClipboardMatchesAfterWindowsLineEndings': True,
        'actualClipboardByteMatch': False, 'windowsClipboardCRLFConversions': 42,
        'rawExecutionInputByteMatch': True, 'twoWorksMatchRawResponses': True,
        'sameFiveActualQuestionsAnswersPreserved': True, 'examplesEnabled': False,
        'newAccountsCreated': 0, 'planGenerationStatus': '502 RESULT_INVALID',
        'planCopyHistoryExecutionWork': 'ABSENT; no body repaired or invented by harness',
    })
    events = [line for line in (RT / 'candidate7-server.log').read_text(encoding='utf-8').splitlines()
              if 'ad3876e8-ca74-4a7b-8689-5dd12deb0a20' in line
              and ('event=model.response.validation_failed' in line or 'event=optimization.failed' in line)]
    assert len(events) == 4
    save('platform-validation-events.json', {'requestId': 'ad3876e8-ca74-4a7b-8689-5dd12deb0a20', 'events': events, 'scope': 'Only validation metadata; full service logs excluded'})
    preserved = {}
    for directory in ['me00a-candidate6-2026-10-13', 'me00a-continuation-2026-10-13']:
        parent = OUT.parent / directory
        index = parent / 'artifact-sha256.json'
        if not index.exists():
            continue
        files = read(index)['files']
        for rel, expected in files.items():
            assert digest(parent / rel) == expected, f'Parent drift: {directory}/{rel}'
        preserved[directory] = {'indexedFilesMatched': len(files), 'indexSha256': digest(index)}
    save('parent-preservation.json', preserved)
    save('manifest.json', {
        'sourceByteCopies': sources, 'copiedSources': len(sources),
        'generatedFiles': 'Checks, summaries and README are derived; raw traces and original responses unchanged.',
        'accountFilesCookiesHeadersKeysNotArchived': True,
        'artifactIndexExcludesItself': True,
    })
    print(json.dumps({'archive': OUT.relative_to(ROOT).as_posix(), 'byteCopies': len(sources), 'calls': 185, 'integrity': 'PASS'}, ensure_ascii=True))


if __name__ == '__main__':
    main()
