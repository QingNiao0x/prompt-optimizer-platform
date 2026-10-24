"""离线核对实际作品中的可确定契约；不替代事实含义和设计质量审阅。"""
import ast
import hashlib
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[4]
PRIVATE = ROOT / 'tmp/natural-multidimensional-2026-10-24/works'
PUBLIC = pathlib.Path(__file__).resolve().parent / 'works'


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def python_contract(content):
    """只运行经AST检查的纯计算函数；导入、反射和文件/进程等能力不进入执行域。"""
    text = content.strip()
    format_ok = not text.startswith('```')
    if text.startswith('```'):
        text = re.sub(r'^```(?:python)?\s*\n|\n```$', '', text)
    try:
        tree = ast.parse(text)
        assert len(tree.body) == 1 and isinstance(tree.body[0], ast.FunctionDef)
        assert tree.body[0].name == 'order' and not tree.body[0].decorator_list
        assert len(tree.body[0].args.args) == 1
        forbidden = (ast.Import, ast.ImportFrom, ast.ClassDef, ast.Global, ast.Nonlocal, ast.With, ast.AsyncWith)
        functions = {'isinstance', 'type', 'int', 'bool', 'list', 'TypeError', 'ValueError',
                     'sorted', 'any', 'all', 'len', 'range', 'enumerate', 'zip'}
        for node in ast.walk(tree):
            assert not isinstance(node, forbidden)
            if isinstance(node, ast.Name):
                assert not node.id.startswith('__')
            if isinstance(node, ast.Attribute):
                assert node.attr in {'sort', 'copy', 'append', 'extend', 'pop'}
            if isinstance(node, ast.Call) and isinstance(node.func, ast.Name):
                assert node.func.id in functions or node.func.id == 'order'
        builtin_values = {name: getattr(__import__('builtins'), name) for name in functions}
        namespace = {'__builtins__': builtin_values}
        exec(compile(tree, '<synthetic-order-work>', 'exec'), namespace)
        function = namespace['order']
        checks = []
        for values in [[], [2, 2, -1, 0], [3, 1, 2], [1]]:
            original = values.copy()
            actual = function(values)
            checks.append(actual == sorted(original) and values == original and actual is not values)
        for values in [None, (), '123', {1, 2}, [True], [1, False], [1, 2.0], ['1']]:
            try:
                function(values)
                checks.append(False)
            except TypeError:
                checks.append(True)
        return {'codeOnly': format_ok, 'contractChecksPassed': sum(checks), 'contractChecks': len(checks),
                'functionCorrect': all(checks), 'astRestrictedExecution': True}
    except Exception as failure:
        return {'codeOnly': format_ok, 'automatedCheckCompleted': False,
                'failureType': type(failure).__name__, 'needsManualReview': True}


def main():
    """只写新的核对报告，保留原始结果和模型评审；不调用外部服务。"""
    results = [read(path) for path in sorted((PRIVATE / 'results').glob('*_EXEC_R*.json'))]
    rows = []
    for row in results:
        content = row.get('body', {}).get('data', {}).get('content', '')
        item = {'id': row['id'], 'status': row['status'],
                'finishReason': row.get('body', {}).get('data', {}).get('finishReason'),
                'sha256': hashlib.sha256(content.encode()).hexdigest(), 'characters': len(content)}
        if row['caseId'] == 'T04':
            item['pythonContract'] = python_contract(content)
        if row['caseId'] == 'T07':
            item['exactThreeLines'] = content.rstrip('\r\n') == '1\n2\n3'
        if row['caseId'] == 'T08':
            item['sentenceCount'] = len([part for part in re.split(r'[。！？!?]+', content) if part.strip()])
            item['sentenceCountLimitPassed'] = item['sentenceCount'] <= 3
        rows.append(item)
    fingerprints = []
    for case in read(PUBLIC / 'cases.json')['cases']:
        prompts = {'ORIGINAL': case['rawPrompt']}
        for version in ['31', '44']:
            result = read(PRIVATE / 'results' / (case['id'] + '_V' + version + '_GEN.json'))
            prompts['V' + version] = result.get('body', {}).get('data', {}).get('optimizedPrompt', '')
        fingerprints.append({'case': case['id'], 'characters': {arm: len(prompt) for arm, prompt in prompts.items()},
            'sha256': {arm: hashlib.sha256(prompt.encode()).hexdigest() for arm, prompt in prompts.items()},
            'identicalPairs': [[a, b] for i, a in enumerate(prompts) for b in list(prompts)[i + 1:] if prompts[a] == prompts[b]]})
    output = {'scope': '确定性代码契约、格式、句数和提示词指纹；事实及建议质量另行复核。',
              'works': rows, 'promptFingerprints': fingerprints}
    with (PUBLIC / 'deterministic-work-checks.json').open('x', encoding='utf-8', newline='\n') as target:
        json.dump(output, target, ensure_ascii=False, indent=2)
        target.write('\n')
    print(json.dumps({'worksChecked': len(rows), 'rows': rows}, ensure_ascii=False))


if __name__ == '__main__':
    main()
