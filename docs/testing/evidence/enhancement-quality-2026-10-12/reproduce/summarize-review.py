"""Decode independently submitted blind scores after both reviewers have finished."""
import collections, json, pathlib, sys
root = pathlib.Path(__file__).resolve().parent
stage = sys.argv[1]
key = json.loads((root / f'{stage}-review-key.json').read_text(encoding='utf-8'))
mapping = {row['pair']: row for row in key if row['kind'] == 'candidate'}
summaries, by_reviewer = [], []
for name in ['one', 'two']:
    source = root / 'review' / stage / f'reviewer-{name}.json'
    data = json.loads(source.read_text(encoding='utf-8-sig'))
    assert len(data) == len(mapping) and {r['pair'] for r in data} == set(mapping)
    counts, rows = collections.Counter(), []
    for result in data:
        candidate = mapping[result['pair']]
        letter = candidate['letter']
        verdict = result['verdict']
        outcome = {'平': 'tie', '平局': 'tie', '双方失败': 'both_failed'}.get(verdict)
        if outcome is None:
            assert verdict in ('A胜', 'B胜'), verdict
            outcome = 'win' if verdict[0] == letter else 'loss'
        counts[outcome] += 1
        for version in ['A', 'B']:
            for dimension in ['accuracy', 'completeness', 'clarity', 'executability']:
                assert 1 <= result[version][dimension] <= 5
        rows.append({**candidate, 'outcome': outcome,
                     'candidateScores': result[letter], 'reason': result['reason']})
    summaries.append({'reviewer': name, 'counts': dict(counts), 'results': rows})
    by_reviewer.append({r['pair']: r for r in rows})
disagreements = [{'pair': pair, 'case': mapping[pair]['case'],
                  'reviewerOne': by_reviewer[0][pair]['outcome'],
                  'reviewerTwo': by_reviewer[1][pair]['outcome']}
                 for pair in mapping if by_reviewer[0][pair]['outcome'] != by_reviewer[1][pair]['outcome']]
report = {'stage': stage, 'reviewerType': 'independent AI assisted; not human experts',
          'pairedBaselineIsReusedAcrossRounds': True, 'reviewers': summaries,
          'verdictDisagreements': disagreements}
(root / f'{stage}-blind-summary.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps({'stage': stage, 'counts': [r['counts'] for r in summaries],
                  'disagreements': len(disagreements)}, ensure_ascii=False))
