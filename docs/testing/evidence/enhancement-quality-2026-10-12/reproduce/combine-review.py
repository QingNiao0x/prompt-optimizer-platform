"""Combine decoded rounds without averaging away failures or reviewer disagreements."""
import collections
import json
import pathlib

root = pathlib.Path(__file__).resolve().parent
packets = ['candidate5-first', 'candidate5-second']
reports = [json.loads((root / f'{packet}-blind-summary.json').read_text(encoding='utf-8'))
           for packet in packets]
reviewers = []
for name in ['one', 'two']:
    rows = []
    for packet, report in zip(packets, reports):
        reviewer = next(item for item in report['reviewers'] if item['reviewer'] == name)
        assert len(reviewer['results']) == 14
        rows.extend({**row, 'packet': packet} for row in reviewer['results'])
    assert len({(row['round'], row['case'], row['model']) for row in rows}) == 28
    counts = collections.Counter(row['outcome'] for row in rows)
    reviewers.append({'reviewer': name, 'counts': dict(counts), 'results': rows})
disagreements = [{**item, 'packet': packet}
                 for packet, report in zip(packets, reports)
                 for item in report['verdictDisagreements']]
combined = {'stage': 'candidate5', 'reviewerType': 'independent AI assisted; not human experts',
            'pairedBaselineIsReusedAcrossRounds': True, 'independentTaskCount': 7,
            'comparisonCountPerReviewer': 28, 'reviewers': reviewers,
            'verdictDisagreements': disagreements,
            'releaseGate': 'BLOCKED: serious failures remain; average scores cannot override them'}
target = root / 'candidate5-blind-summary.json'
with target.open('x', encoding='utf-8') as output:
    json.dump(combined, output, ensure_ascii=False, indent=2)
print(json.dumps({'counts': [reviewer['counts'] for reviewer in reviewers],
                  'disagreements': len(disagreements)}, ensure_ascii=False))
