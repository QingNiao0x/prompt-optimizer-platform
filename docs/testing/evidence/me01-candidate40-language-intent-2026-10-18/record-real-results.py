"""Publish bounded synthetic originals, retain reviewer disagreements, and close without moving unused calls."""
from pathlib import Path
from datetime import datetime, timezone
import json
import hashlib
import shutil

PUBLIC = Path(__file__).resolve().parent
ROOT = PUBLIC.parents[3]
LIVE = ROOT / 'tmp/me01-candidate40-live-20261018'
LEDGER = ROOT / 'tmp/me00a-20261012'
sha = lambda data: hashlib.sha256(data).hexdigest()


def save(name, value):
    with (PUBLIC / name).open('x', encoding='utf-8') as output:
        json.dump(value, output, ensure_ascii=False, indent=2)
        output.write('\n')


def copy(source, name):
    target = PUBLIC / name
    target.parent.mkdir(parents=True, exist_ok=True)
    with target.open('xb') as output:
        output.write(source.read_bytes())


def main():
    mapping = {'A': 'PLAN', 'B': 'RAW'}
    texts = {arm: (LIVE / f'works/TS/{arm}/work.md').read_text(encoding='utf-8') for arm in ['RAW', 'PLAN']}
    original_reviews = {}
    audits = {}
    for reviewer in ['R1', 'R2']:
        data = json.loads((LIVE / f'reviews/{reviewer}/review.md').read_text(encoding='utf-8'))
        assert {w['id'] for w in data['works']} == set(mapping) and len(data['works']) == 2
        invalid = []
        for work in data['works']:
            for dimension in ['accuracy', 'completeness', 'clarity', 'executability']:
                score = work['scores'][dimension]
                assert isinstance(score, int) and 1 <= score <= 5
                for quote in work['evidence'][dimension]['quotes']:
                    if quote not in texts[mapping[work['id']]]:
                        invalid.append({'work': work['id'], 'dimension': dimension, 'quote': quote})
        original_reviews[reviewer] = data
        audits[reviewer] = {'preferredOriginal': data['preferred'],
                             'preferredArm': mapping.get(data['preferred'], data['preferred']),
                             'nonVerbatimOccurrences': len(invalid), 'nonVerbatimQuotes': invalid,
                             'distinctNonVerbatimQuotes': len({v['quote'] for v in invalid}),
                             'scoresOriginal': {w['id']: w['scores'] for w in data['works']},
                             'notReleaseApproval': True}
        save(f'review-{reviewer.lower()}-original.json', data)
    audit = {'sameSupplierIndependentAiReviews': True, 'humanExpertReview': False,
             'blinding': 'Work identities hidden; same task and submitted answers; no access to the other review',
             'mappingForPostReviewAudit': mapping, 'reviews': audits,
             'preferenceDisagreement': audits['R1']['preferredOriginal'] != audits['R2']['preferredOriginal'],
             'adjudication': [
                 'R1 says the RAW work supplied no export/packaging although it contains export function dedupeLines; that factual criticism is unsupported.',
                 'R2 gives enhanced executability 5 despite the supplied failed author test and does not resolve the recorded compiler-profile failure.',
                 'The RAW input did not request authored tests; do not apply the enhanced prompt extra test promise to RAW.',
                 'Lone CR normalization is an explicit downstream assumption outside the frozen LF/CRLF checks; preserve uncertainty instead of adding a post-hoc common hard test.'
             ], 'meanScoreOrWinRateUsedForRelease': False, 'overallQualityGate': 'FAIL_ENHANCED_TEST_DELIVERY'}
    save('review-audit.json', audit)
    for arm in texts:
        copy(LIVE / f'works/TS/{arm}/work.md', f'works/{arm.lower()}.md')
    copy(LIVE / 'flow/TS-IMPLICIT/native-copy.md', 'native-copy.md')
    copy(LIVE / 'hard-checks.json', 'real-work-checks.json')
    copy(LIVE / 'execution-claims.json', 'execution-claims.json')
    for case in ['TS-IMPLICIT', 'TS-USER-CHOICE']:
        copy(LIVE / f'flow/{case}/plan-observation.json', f'{case.lower()}-plan.json')
    rows = []
    for number in range(416, 423):
        source = LEDGER / f'traces/{number}.json'
        record = json.loads(source.read_text(encoding='utf-8'))
        assert record['version'] == 'candidate40-live' and record['httpStatus'] == 200
        assert not any(choice.get('message', {}).get('reasoning_content') for choice in record['response'].get('choices', []))
        copy(source, f'traces/{number}.json')
        rows.append({'trace': number, 'purpose': record['purpose'], 'model': record['request']['model'],
                     'status': record['httpStatus'], 'elapsedMs': record['elapsedMs'],
                     'usage': record['response']['usage'], 'rawRecordSha256': sha(source.read_bytes())})
    check = json.loads((LIVE / 'flow/TS-IMPLICIT/check.json').read_text(encoding='utf-8'))
    assert check['success'] and check['historyMatches'] and check['nativeCopyMatches'] and check['historyTotalDelta'] == 1
    save('real-chain.json', {'candidate': 'candidate40', 'sourceCommit': '00bdf04a',
         'sourceManifestSha256': check['sourceManifestSha256'], 'sameAccount': True, 'accountsCreated': 0,
         'isolatedLocalPorts': [9001, 5174, 9325], 'dailyBackendReplacedDuringEvaluation': False,
         'nativeCopyMatches': True, 'storeMatches': True, 'historyMatches': True, 'newHistoryRecords': 1,
         'copySha256': check['copySha256'], 'actualAnswers': check['actualAnswers'],
         'normalTaskQuestionCount': 4, 'userReservedChoiceQuestionCount': 4,
         'taskIdentityGate': 'PASS', 'userReservedChoiceGate': 'PASS', 'ordinaryPackagingGate': 'FAIL',
         'enhancementModelAttempts': 1, 'newExtraExampleRequirement': False,
         'sameInformationExecution': True, 'copiedBodyPatched': False,
         'commonFunctionComparison': 'TIE_12_OF_12_EACH', 'completeEnhancedDelivery': 'FAIL',
         'reviews': audits, 'calls': rows, 'totalTokens': sum(v['usage']['total_tokens'] for v in rows),
         'upstreamElapsedMsSum': sum(v['elapsedMs'] for v in rows),
         'notAnEndToEndDuration': True, 'qualityRetries': 0, 'overallQualityGate': 'OPEN_OR_FAIL_NOT_RELEASED'})
    save('assembly-origin-review.json', {
        'sourceTrace': 418, 'modelResponseOriginalPreserved': True,
        'modelTaskAlreadyStates': ['exact whole-line matching', 'LF/CRLF to LF', 'first empty line', 'first-occurrence order'],
        'platformStillAdds': ['按整行完全相同去重。', '支持LF与CRLF，输出统一LF，保留首次出现的空行。', '保留第一次出现的顺序。', '包装由实现者合理处理'],
        'attribution': 'PlanDecisionIntegrator coverage misses finite rephrasing; task-rule grouping also repeats input validation. Model TASK/ACCEPTANCE may have independently useful checks.',
        'ordinaryQuestionAttribution': 'RoutineCodePackaging.QUESTION does not accept the generated task-bound delivery-form wording; its metadata whitelist also does not explain all library/class wording. Conservative retention is not a fix.',
        'changeAdoptedDuringLive': False,
        'nextBoundary': 'Replay the complete question and metadata; establish object/condition/state coverage before omission, preserving user choice and unknown independent content. Do not widen keywords or add global bans.',
        'oldCredentialEchoes': 'FAIL_RETAINED_12', 'oldUnsupportedExecutionClaims': 'FAIL_RETAINED_9',
        'newWorkAuthoredTestFailure': 'DOWNSTREAM_INTERNAL_IMPLEMENTATION_TEST_INCONSISTENCY',
        'newCompilerFailure': 'DOWNSTREAM_RUN_INSTRUCTION_CONFIGURATION_GAP',
        'newNoClaimFinding': 'NOT_CLAIMED_IN_TWO_WORKS_NOT_UNIVERSAL_TRUTHFULNESS_PASS'})
    path = LEDGER / 'protocol.json'
    before = path.read_bytes()
    protocol = json.loads(before)
    batch = protocol['activeBatch']
    assert batch['version'] == 'candidate40-live' and batch['status'] == 'OPEN'
    traces = list((LEDGER / 'traces').glob('*.json'))
    assert len(traces) == 422
    counts = {key: sum(v['purpose'] == key for v in rows) for key in batch['purposeMaximums']}
    assert counts == {'PLANNING': 2, 'PLATFORM_GENERATION': 1, 'EXECUTION': 2, 'REVIEW': 2}
    for key, count in counts.items():
        assert count <= batch['purposeMaximums'][key]
    batch.update(status='CLOSED', actualNewCalls=7, actualCallsAfterBatch=422, actualPurposeCounts=counts,
                 phases={'baseline': 70, 'candidateValidation': 352}, failedOrIncompleteModelCallsCounted=0,
                 localToolFailuresPreserved=True, unusedAllowance=6, unusedAllowanceStatus='CLOSED_NOT_TRANSFERABLE',
                 qualityRetriesPerformed=0, closedAt=datetime.now(timezone.utc).isoformat())
    assert path.read_bytes() == before
    backup = LEDGER / 'protocol-before-candidate40-close.json'
    with backup.open('xb') as output:
        output.write(before)
    staged = LEDGER / 'protocol-candidate40-close-staged.json'
    with staged.open('x', encoding='utf-8') as output:
        json.dump(protocol, output, ensure_ascii=False, indent=2)
    staged.replace(path)
    save('batch-closed.json', batch)
    save('batch-close-summary.json', {'actualNewCalls': 7, 'approvedMaximum': 13, 'actualTotal': 422,
         'baseline': 70, 'candidate': 352, 'formal': [438, 80, 358], 'unusedClosed': 6,
         'qualityRetries': 0, 'failedModelCalls': 0, 'localToolFailuresNotHidden': True,
         'sameInformationWorks': 2, 'independentAiReviews': 2, 'overallQualityPass': False})
    print(json.dumps({'actualCalls': 422, 'newCalls': 7, 'unusedClosed': 6,
                      'reviewBadQuotes': {k: v['nonVerbatimOccurrences'] for k, v in audits.items()},
                      'reviewPreferenceDisagreement': audit['preferenceDisagreement']}))


if __name__ == '__main__':
    main()
