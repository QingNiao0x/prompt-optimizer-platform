"""Reproduce bounded AA checks from public http-results.json after exact reviewed-code hash checks.

No credentials, private files, environment configuration, services or model calls are used.
Only the three frozen synthetic code blocks are loaded; main/file/interactive paths are not called.
"""
import ast
import hashlib
import json
import pathlib
import argparse
import re
from decimal import Decimal

EVIDENCE = pathlib.Path(__file__).resolve().parent
PUBLIC_RESULTS = json.loads((EVIDENCE / 'http-results.json').read_text(encoding='utf-8-sig'))
INDEX = {row['id']: row for row in PUBLIC_RESULTS}
KNOWN_CODE = {
    'RAW': '58af3955277933f53a89192dfb7c54da3cba128ae409cea1524de6af31fd2a17',
    'V4': 'af9c81e6418b6b88b55d30a7382f2183cf385f6fac866db58eb3772f600ed8cb',
    'V20': '52a6e9f55408232695c9b38604fe468c45cbdc9a6578a589a89e94ce55004a0a',
}
MODULES = {}
SAFETY = []
for arm in ['RAW', 'V4', 'V20']:
    record = INDEX[f'SHARED_EXPENSE_TOOL_{arm}_EXEC']
    body = record['body']['data']['content']
    blocks = re.findall(r'```python\s*\n(.*?)```', body, re.S)
    if len(blocks) != 1:
        raise ValueError(f'{arm}: expected exactly one Python code block')
    code = blocks[0]
    fingerprint = hashlib.sha256(code.encode()).hexdigest()
    # Only the exact, manually reviewed artifacts may execute; edited input fails closed.
    if fingerprint != KNOWN_CODE[arm]:
        raise ValueError(f'{arm}: code differs from the manually reviewed artifact')
    tree = ast.parse(code)
    SAFETY.append({
        'arm': arm,
        'codeSha256': fingerprint,
        'lineCount': len(code.splitlines()),
        'imports': [ast.unparse(n) for n in ast.walk(tree) if isinstance(n, (ast.Import, ast.ImportFrom))],
        'manualStaticReview': 'PASS_FOR_SELECTED_PURE_FUNCTIONS',
        'executionBoundary': 'Loaded with non-main run_name; no interactive/main/file-reading branch invoked. Standard-library imports and pure parser/calculation functions only.',
    })
    namespace = {'__name__': 'aa_review_module'}
    exec(compile(code, f'captured-AA-{arm}.py', 'exec'), namespace)
    MODULES[arm] = namespace


def cents(value):
    return int((Decimal(str(value)) * 100).quantize(Decimal('1')))


def evaluate(arm, people, rows):
    mod = MODULES[arm]
    # Each offered representation is adapted; no shared rounding or transfer algorithm is imposed.
    if arm == 'RAW':
        parsed = [mod['parse_expense'](' '.join([r['payer'], r['amount']] + ([','.join(r['participants'])] if 'participants' in r else []))) for r in rows]
        paid, owed, net, transfers = mod['settle'](parsed, people)
        paid = {p: cents(paid[p]) for p in people}
        owed = {p: cents(owed[p]) for p in people}
        net = {p: cents(net[p]) for p in people}
        transfers = [(a, b, cents(v)) for a, b, v in transfers]
    elif arm == 'V4':
        people, paid, owed, _ = mod['validate_and_compute']({'people': people, 'expenses': rows})
        net = {p: paid[p] - owed[p] for p in people}
        transfers = mod['settle'](people, paid, owed)
    else:
        parsed = [mod['parse_expense_line'](' '.join([r['payer'], r['amount']] + ([','.join(r['participants'])] if 'participants' in r else [])), people) for r in rows]
        owed, paid = mod['compute_balances'](people, parsed)
        net = {p: paid[p] - owed[p] for p in people}
        transfers = mod['settle'](owed, paid)
    residual = dict(net)
    for payer, receiver, amount in transfers:
        residual[payer] += amount
        residual[receiver] -= amount
    total = sum(cents(r['amount']) for r in rows)
    return {
        'paidCents': dict(paid), 'owedDisplayedCents': dict(owed), 'netCents': net,
        'transfersCents': transfers, 'residualCentsAfterTransfers': residual,
        'totalCents': total, 'paidTotalCents': sum(paid.values()), 'owedDisplayedTotalCents': sum(owed.values()),
        'conservesDisplayedCents': sum(paid.values()) == total == sum(owed.values()) and sum(net.values()) == 0,
        'settlesToZero': all(v == 0 for v in residual.values()),
    }


common = [
    ('equal_three', ['A', 'B', 'C'], [{'payer': 'A', 'amount': '60.00'}]),
    ('multiple_payers', ['A', 'B', 'C'], [{'payer': 'A', 'amount': '30.00'}, {'payer': 'B', 'amount': '12.00'}]),
    ('cent_remainder_three', ['A', 'B', 'C'], [{'payer': 'A', 'amount': '100.00'}]),
    ('offered_subset', ['A', 'B', 'C'], [{'payer': 'A', 'amount': '60.00'}, {'payer': 'B', 'amount': '30.00', 'participants': ['A', 'B']}]),
]
checks = []
for case_id, people, rows in common:
    for arm in ['RAW', 'V4', 'V20']:
        checks.append({'caseId': case_id, 'arm': arm, 'input': {'people': people, 'expenses': rows}, **evaluate(arm, people, rows)})

published = [
    ('RAW', ['张三', '李四', '王五'], [
        {'payer': '张三', 'amount': '300.00'},
        {'payer': '李四', 'amount': '120.00', 'participants': ['张三', '李四']},
        {'payer': '王五', 'amount': '80.00', 'participants': ['王五', '李四']},
    ], {'张三': 14667, '李四': 14667, '王五': 10667}),
    ('V4', ['张三', '李四', '王五', '赵六'], [
        {'payer': '张三', 'amount': '480.00'},
        {'payer': '李四', 'amount': '150.00', 'participants': ['张三', '李四', '王五']},
        {'payer': '王五', 'amount': '100.00', 'participants': ['张三', '李四', '赵六'], 'mode': 'shares', 'weights': {'张三': 1, '李四': 1, '赵六': 2}},
    ], {'张三': 19500, '李四': 19500, '王五': 17000, '赵六': 17000}),
    ('V20', ['小明', '小红', '小刚', '小丽'], [
        {'payer': '小明', 'amount': '200.00'},
        {'payer': '小红', 'amount': '88.50', 'participants': ['小明', '小红', '小刚']},
        {'payer': '小刚', 'amount': '36.60', 'participants': ['小刚', '小丽']},
    ], {'小明': 8617, '小红': 7950, '小刚': 7950, '小丽': 7983}),
]
examples = []
for arm, people, rows, declared in published:
    actual = evaluate(arm, people, rows)
    examples.append({
        'arm': arm, 'input': {'people': people, 'expenses': rows},
        'publishedOwedCents': declared, 'publishedExampleMatchesCode': actual['owedDisplayedCents'] == declared,
        **actual,
    })

result = {
    'batch': 'natural-single-pass-fidelity-2026-10-22',
    'kind': 'OFFLINE_GENERATED_ARTIFACT_EXECUTION',
    'actualModelCalls': 0,
    'sourceResultPattern': 'SHARED_EXPENSE_TOOL_{RAW|V4|V20}_EXEC.json',
    'safety': SAFETY,
    'commonCoreChecks': checks,
    'publishedExampleChecks': examples,
    'summary': {
        'RAW': 'Ordinary whole-cent cases pass; splitting 100.00 across three leaves a one-cent residual after independently rounding net amounts. Published sample differs from its code.',
        'V4': 'All four core cases conserve cents and settle; its own weighted sample matches the code.',
        'V20': 'All four core cases conserve cents and settle; its published corresponding output does not match the code. A following paragraph admits and corrects owed amounts, leaving the example internally contradictory.',
        'v20VersusV4': 'NO_WORK_WIN: fewer optional modes can reduce input choices, but the incorrect worked output is a concrete usability regression. Valid remainder distributions and different settlement paths are accepted.',
    },
    'limits': ['No network, protected-path access, external files or production actions.', 'Only manually reviewed in-memory APIs executed; interactive and file-loading UX not exercised.', 'This bounded execution is not exhaustive validation of either generated application.', 'These are downstream artifacts; the first occurrence of a defect alone does not establish sole enhancement-model causation.'],
}
parser = argparse.ArgumentParser(description='Replay manually reviewed synthetic AA artifacts from public http-results.json; no network or account.')
parser.add_argument('--output', type=pathlib.Path, help='Optional report path; omitted means JSON to stdout.')
args = parser.parse_args()
rendered = json.dumps(result, ensure_ascii=False, indent=2) + '\n'
if args.output:
    args.output.write_text(rendered, encoding='utf-8')
    print('Completed offline AA checks; no model calls.')
else:
    print(json.dumps(result, ensure_ascii=True, indent=2))
