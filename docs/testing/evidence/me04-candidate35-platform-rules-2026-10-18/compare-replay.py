"""Compare saved assembler replays only; no model, HTTP, identity or history database access."""
import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
PUBLIC = Path(__file__).resolve().parent


def sha(data):
    return hashlib.sha256(data).hexdigest()


def load_private(value):
    """Accept only previously saved replay summaries in this repository's tmp tree."""
    path = (ROOT / value).resolve()
    if not path.is_relative_to(ROOT / 'tmp') or path.name != 'summary.json':
        raise ValueError('Private replay summary path required')
    data = path.read_bytes()
    result = json.loads(data)
    if not result.get('replayOnly') or result.get('newModelCalls') != 0 or result.get('newDownstreamWorks') != 0:
        raise ValueError('Unexpected evidence type')
    for row in result['cases']:
        if row.get('caseId') not in ['REQUESTS-COMPACT', 'REQUESTS-STRUCTURED']:
            raise ValueError('Unknown synthetic replay case')
        folder = path.parent / row['caseId']
        body = (folder / 'optimized-prompt.md').read_bytes()
        original = (folder / 'model-response-raw.json').read_bytes()
        if sha(body) != row['bodySha256'] or sha(original) != row['modelResponseSha256']:
            raise ValueError('Replay body or original response hash drift')
    return path, data, result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--before', required=True)
    parser.add_argument('--after', required=True)
    parser.add_argument('--output', default=str(PUBLIC / 'replay-comparison.json'))
    args = parser.parse_args()
    before_path, before_bytes, before = load_private(args.before)
    after_path, after_bytes, after = load_private(args.after)
    if before['runtimeCandidate'] != 'candidate34-r1' or after['runtimeCandidate'] != 'candidate35':
        raise ValueError('Declared parent and candidate replays required')
    if before['fixtureSha256'] != after['fixtureSha256']:
        raise ValueError('Replay fixtures are not byte-identical')
    expected = ['REQUESTS-COMPACT', 'REQUESTS-STRUCTURED']
    if [row['caseId'] for row in before['cases']] != expected or [row['caseId'] for row in after['cases']] != expected:
        raise ValueError('Expected two fixed replay modes')
    comparisons = []
    for left, right in zip(before['cases'], after['cases']):
        same_input = all(left[key] == right[key] for key in (
            'caseId', 'trace', 'requestedFormat', 'rawPromptSha256', 'modelResponseSha256',
            'missingPresentationNormalizedAsExistingProvider'))
        same_sources = left['sourceConstraintGroups'] == right['sourceConstraintGroups']
        same_applied = left['appliedConstraints'] == right['appliedConstraints']
        same_other = left['otherSectionsSha256'] == right['otherSectionsSha256']
        same_pending = left['ambiguities'] == right['ambiguities']
        metadata = right['resultConstraintGroups'] == right['sourceConstraintGroups']
        complete = left['canonicalPlatformRulesComplete'] and right['canonicalPlatformRulesComplete']
        counts = left['canonicalPlatformRules'] == right['canonicalPlatformRules']
        title_removed = left['legacyHeadingOccurrences'] == 1 and right['legacyHeadingOccurrences'] == 0
        only_title = left['bodyWithoutLegacyHeadingLineSha256'] == right['bodySha256']
        comparisons.append({
            'caseId': left['caseId'], 'trace': left['trace'], 'format': left['requestedFormat'],
            'sameRawAndSavedModelResponse': same_input,
            'modelResponseSha256': left['modelResponseSha256'],
            'beforeBodyCodePointLength': left['bodyCodePointLength'],
            'afterBodyCodePointLength': right['bodyCodePointLength'],
            'codePointLengthDelta': right['bodyCodePointLength'] - left['bodyCodePointLength'],
            'beforeHanCount': left['bodyHanCount'], 'afterHanCount': right['bodyHanCount'],
            'beforeLegacyHeadingOccurrences': left['legacyHeadingOccurrences'],
            'afterLegacyHeadingOccurrences': right['legacyHeadingOccurrences'],
            'canonicalPlatformRulesComplete': complete,
            'exactCanonicalRuleCountsUnchanged': counts,
            'canonicalRuleCountBefore': len(left['canonicalPlatformRules']),
            'canonicalRuleCountAfter': len(right['canonicalPlatformRules']),
            'eachCanonicalRuleExactOccurrencesBefore': [row['exactOccurrences'] for row in left['canonicalPlatformRules']],
            'eachCanonicalRuleExactOccurrencesAfter': [row['exactOccurrences'] for row in right['canonicalPlatformRules']],
            'appliedConstraintsUnchanged': same_applied,
            'sourceConstraintGroupsUnchanged': same_sources,
            'newResultMetadataPreservesServerGroups': metadata,
            'parentResultGroupsAbsentNotReconstructed': left['resultConstraintGroups'] is None,
            'otherSectionsUnchanged': same_other, 'ambiguitiesUnchanged': same_pending,
            'onlyBodyChangeIsLegacyHeadingLineRemoval': only_title,
            'beforeBodySha256': left['bodySha256'], 'afterBodySha256': right['bodySha256'],
            'checksPassed': all([same_input, same_sources, same_applied, same_other, same_pending,
                                 metadata, complete, counts, title_removed, only_title]),
        })
    report = {
        'candidate': 'candidate35', 'evidenceType': 'OFFLINE_SAVED_MODEL_RESPONSE_ASSEMBLY_REPLAY',
        'newModelCalls': 0, 'newDownstreamWorks': 0, 'actualClipboardOrDatabaseHistoryVerified': False,
        'fixtureSha256': before['fixtureSha256'],
        'parentSourceManifestSha256': before['manifestSha256'],
        'candidateSourceManifestSha256': after['manifestSha256'],
        'parentReplaySummarySha256': sha(before_bytes), 'candidateReplaySummarySha256': sha(after_bytes),
        'cases': comparisons, 'checksPassed': all(row['checksPassed'] for row in comparisons),
        'scopeLimit': 'Exact saved model responses and runtime manifests only. Canonical-string counts do not prove arbitrary paraphrase equivalence, and reduced prompt length does not prove downstream quality improvement.',
    }
    output = (ROOT / args.output).resolve()
    if output.parent != PUBLIC:
        raise ValueError('Public comparison must be written in this evidence directory')
    with output.open('x', encoding='utf-8', newline='\n') as stream:
        stream.write(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'checksPassed': report['checksPassed'], 'cases': len(comparisons),
                      'newModelCalls': 0, 'newDownstreamWorks': 0}))
    if not report['checksPassed']:
        raise SystemExit('Replay differences require attribution; keep both original outputs')


if __name__ == '__main__':
    main()
