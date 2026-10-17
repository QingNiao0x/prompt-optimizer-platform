"""Summarize saved synthetic evidence without changing model originals, opinions or protocol."""
from pathlib import Path
import hashlib
import json

PUBLIC = Path(__file__).resolve().parent
ROOT = PUBLIC.parents[3]
PRIVATE = ROOT / 'tmp/me04-candidate33-live-20261018'


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    protocol = read(PUBLIC / 'protocol-closed.json')
    assert protocol['activeBatch']['actualCallsAfterBatch'] == 381
    records = []
    for number in range(370, 382):
        trace = ROOT / f'tmp/me00a-20261012/traces/{number:03d}.json'
        value = read(trace)
        records.append({'trace': number, 'sha256': sha(trace), 'purpose': value['purpose'],
                        'operation': value.get('operation'), 'httpStatus': value.get('httpStatus'),
                        'elapsedMs': value['elapsedMs'], 'usage': value['response']['usage'],
                        'model': value['request']['model'],
                        'finishReason': value['response']['choices'][0]['finish_reason']})
    copies = []
    for task in ('PATCH', 'MAIL'):
        for form in ('COMPACT', 'STRUCTURED'):
            directory = PRIVATE / 'live-results' / task / form
            check = read(directory / 'check.json')
            assert check['success'] and check['nativeCopyMatches'] and check['accountReused']
            assert all(check['history'][field] for field in ('bodyMatches', 'formatMatches', 'sectionsMatch', 'ambiguitiesMatch', 'constraintsMatch'))
            copies.append({'case': task, 'format': form, 'nativeCopySha256': sha(directory / 'native-copy.md'),
                           'copyCharacters': len((directory / 'native-copy.md').read_text(encoding='utf-8')),
                           'historyConsistent': True, 'currentStoreConsistent': check['currentStoreMatchesResponse'],
                           'checkFile': f'live-checks/{task}-{form}.json'})
    code = {}
    hard = read(PRIVATE / 'hard-checks.json')
    for arm in ('RAW', 'COMPACT', 'STRUCTURED'):
        report = read(PRIVATE / 'patch-checks' / arm / 'result.json')
        details = report['independentChecks']['details']
        code[arm] = {'compileTogether': report['compilation']['status'],
                     'generatedTests': report['generatedTests']['status'], 'eaEnabled': False,
                     'corePassed': details['corePassed'], 'coreFailed': details['coreFailed'],
                     'declaredChecksPassed': len(details['declarationChecks']) - details['declarationFailed'],
                     'declaredChecksFailed': details['declarationFailed'],
                     'reportSha256': sha(PRIVATE / 'patch-checks' / arm / 'result.json'),
                     'completeSemanticGate': 'OPEN'}
    source = read(PUBLIC / 'candidate33-source.json')
    runtime = ROOT / 'services/api/target/classes'
    assert all(sha(runtime / item['path']) == item['sha256'] for item in source['runtimeFiles'])
    summary = {'candidate': 'candidate33', 'sourceRevision': 'r1',
               'sourceManifestSha256': sha(PUBLIC / 'candidate33-source.json'),
               'automaticActual': 381, 'baselineActual': 70, 'candidateActual': 311,
               'formalCaps': {'total': 399, 'baseline': 80, 'candidate': 319},
               'batch': {'approvedMaximum': 20, 'actual': 12, 'generation': 4, 'execution': 6, 'review': 2,
                         'protocolRepairs': 0, 'transportFailures': 0, 'qualityRetries': 0,
                         'unusedClosedNotTransferable': 8, 'newAccounts': 0},
               'calls': records, 'nativeCopies': copies, 'codeChecks': code,
               'mailWholeBodyHan': {arm: hard['results']['MAIL'][arm]['mechanical']['maximumHan']['han']
                                    for arm in ('RAW', 'COMPACT', 'STRUCTURED')},
               'mailSemanticInspection': {'scope': 'Actual three texts versus original task',
                                          'replyExplicit': True, 'inventedAmountNameDeadline': False,
                                          'onlyBodyNoExamples': True},
               'cost': {'totalTokens': sum(row['usage']['total_tokens'] for row in records),
                        'sumRecordedUpstreamElapsedMs': sum(row['elapsedMs'] for row in records),
                        'timingScope': 'Sequential recorded upstream calls; excludes local setup, browser, compilation and analysis',
                        'repeatability': 'One sample per task-arm; no stability or global win-rate claim'},
               'mainRuntime': {'port': 9000, 'pidAtVerification': 17108, 'matchedFiles': len(source['runtimeFiles']),
                               'migrationsEnabled': False, 'sameFrozenBytes': True},
               'gates': {'modeReachesGeneration': 'PASS_BOUNDED', 'explicitRegenerationCopyHistory': 'PASS_FOUR_REQUESTS',
                         'compactnessAndDuplicateProjection': 'FAIL', 'enhancedPromptFactualConsistency': 'FAIL',
                         'codeMechanical': 'PASS_BOUNDED', 'emailWorks': 'PASS_THREE_SAMPLES',
                         'generalQualityImprovement': 'NOT_ESTABLISHED', 'independentReviewEvidence': 'PARTIAL_FAILURE',
                         'newRealPlanRound': 'NOT_IN_THIS_BATCH', 'overallRelease': 'NOT_PASSED'},
               'reviewAudit': 'review-audit.json', 'attribution': 'attribution.json',
               'unchangedOpenItems': ['Trace363 stale Plan confirmation projection',
                                      'Prior requests credential echo', 'Prior scoring/YLL defects', 'G1-G8']}
    with (PUBLIC / 'live-summary.json').open('x', encoding='utf-8') as stream:
        json.dump(summary, stream, ensure_ascii=False, indent=2)
        stream.write('\n')
    print(json.dumps({'actual': 381, 'batchCalls': 12, 'tokens': summary['cost']['totalTokens'],
                      'copyHistoryChecks': len(copies), 'overallRelease': 'NOT_PASSED'}))


if __name__ == '__main__':
    main()
