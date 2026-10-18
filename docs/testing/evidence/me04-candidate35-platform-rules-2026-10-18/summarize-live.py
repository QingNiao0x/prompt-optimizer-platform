"""Bind the one observed live chain to its original artifacts; no new request or work."""
from pathlib import Path
import hashlib
import json
import re

ROOT = Path(__file__).resolve().parents[4]
PUBLIC = Path(__file__).resolve().parent
PRIVATE = ROOT / 'tmp/me04-candidate35-live-20261018/live-results/REQUESTS/COMPACT'


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    trace_path = ROOT / 'tmp/me00a-20261012/traces/389.json'
    trace = read(trace_path)
    check = read(PUBLIC / 'live-checks/REQUESTS-COMPACT.json')
    result = read(PRIVATE / 'platform.private.json')['body']['data']
    body = (PRIVATE / 'native-copy.md').read_bytes().decode('utf-8')
    rules = read(ROOT / 'tmp/me04-candidate35-replay-20261018/candidate/summary.json')['cases'][0]['sourceConstraintGroups']['platformMandatory']
    assert trace['version'] == 'candidate35-live' and trace['httpStatus'] == 200
    assert check['success'] and check['nativeCopyMatches'] and check['accountReused']
    assert all(check['history'][name] for name in ('bodyMatches', 'formatMatches', 'sectionsMatch',
        'ambiguitiesMatch', 'constraintsMatch', 'constraintGroupsMatch', 'newRecordFound'))
    assert result['provider']['mock'] is False and result['promptFormat'] == 'COMPACT'
    assert body.replace('\r\n', '\n') == result['optimizedPrompt'].replace('\r\n', '\n')
    assert result['constraintGroups']['platformMandatory'] == rules and len(rules) == 5
    counts = [body.count(rule) for rule in rules]
    assert counts == [1] * 5
    assert '平台强制约束（不得删除或弱化）' not in body
    report = {
        'candidate': 'candidate35', 'codeCommit': '91d4bacf',
        'evidenceType': 'ONE_REAL_BROWSER_GENERATION_NATIVE_COPY_AND_DATABASE_HISTORY',
        'trace': 389, 'requestId': trace['requestId'], 'httpStatus': trace['httpStatus'],
        'model': trace['request']['model'], 'mock': False, 'actualNewCalls': 1,
        'protocolRepairCalls': 0, 'qualityRetries': 0, 'newDownstreamWorks': 0, 'newAIReviews': 0,
        'upstreamDurationMs': trace['elapsedMs'], 'platformLatencyMs': result['latencyMs'],
        'usage': trace['response'].get('usage'), 'sameAccount': True, 'newAccountsCreated': 0,
        'nativeCopyMatchesResponse': True, 'history': check['history'],
        'mandatoryRuleCount': len(rules), 'exactMandatoryOccurrences': counts,
        'legacyPlatformHeadingOccurrences': 0, 'ambiguityCount': len(result['ambiguities']),
        'bodyCodePointLength': len(body.replace('\r\n', '\n')),
        'bodyHanCount': len(re.findall('[\u4e00-\u9fff]', body)),
        'bodySha256': sha(PRIVATE / 'native-copy.md'), 'traceSha256': sha(trace_path),
        'sourceManifestSha256': sha(PUBLIC / 'candidate35-source.json'),
        'rawModelResponseSha256': hashlib.sha256(trace['response']['choices'][0]['message']['content'].encode()).hexdigest(),
        'platformResponseSha256': sha(PRIVATE / 'response.private.bin'),
        'hardGate': 'PASS_FOR_THIS_PRESENTATION_COPY_HISTORY_CASE',
        'qualityGate': 'OPEN_WITH_PRIOR_FAILURES_AND_NO_NEW_DOWNSTREAM_WORKS',
        'observations': [
            'Original code delivery is preserved; no model execution work was requested by the product.',
            'Action/error/test obligations remain repeated across model sections. Conservative platform projection retains differently worded requirements.',
            'Model constraint wording overlaps canonical credential and protected-path rules; exact rules remain authoritative and complete.',
            'No new example obligation was observed in this response; one observation does not close the prior OFF-switch failure.',
            'No new generated program exists here, so prior URL canary echo and missing-test gates remain open.'
        ],
        'budget': read(PUBLIC / 'batch-close-summary.json'),
    }
    with (PUBLIC / 'real-chain.json').open('x', encoding='utf-8', newline='\n') as stream:
        stream.write(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({key: report[key] for key in ('trace', 'httpStatus', 'actualNewCalls',
        'mandatoryRuleCount', 'bodyCodePointLength', 'bodyHanCount', 'hardGate', 'qualityGate')}))


if __name__ == '__main__':
    main()
