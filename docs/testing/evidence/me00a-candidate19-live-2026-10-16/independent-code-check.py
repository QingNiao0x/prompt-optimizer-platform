"""Compile saved implementation/tests unchanged; fake transport and independent synthetic checks only."""
import ast
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import py_compile
import re
import sys
import unittest
from unittest.mock import patch

import requests

ROOT = Path(__file__).resolve().parents[4]
WORKS = ROOT / 'tmp/me00a-candidate19-live-20261016/real/requests-title'
OUT = WORKS / 'code-check-v2'
OUT.mkdir(exist_ok=False)
compiled, entries = [], []
for arm in ('raw', 'enhanced'):
    work = (WORKS / f'{arm}-work.md').read_text(encoding='utf-8')
    blocks = re.findall(r'```(?:python|py)\s*\n(.*?)```', work, re.S)
    for number, block in enumerate(blocks, 1):
        name = 'web_title.py' if arm == 'enhanced' and number == 3 else 'test_web_title.py' if arm == 'enhanced' and number == 4 else f'{arm}-{number}.py'
        path = OUT / name
        path.write_text(block, encoding='utf-8', newline='')
        record = {'file': name, 'sha256': hashlib.sha256(block.encode()).hexdigest()}
        try:
            py_compile.compile(str(path), doraise=True, cfile=str(path.with_suffix('.pyc')))
            tree = ast.parse(block)
            record['compiled'] = True
        except (SyntaxError, py_compile.PyCompileError):
            # This first block explicitly declares signatures without bodies, rather than an implementation.
            assert arm == 'enhanced' and number == 1 and 'def fetch_title' in block
            record.update(compiled=False, role='DOCUMENTED_INTERFACE_SIGNATURE_NOT_EXECUTABLE')
            compiled.append(record)
            continue
        compiled.append(record)
        if arm == 'enhanced':
            record['role'] = {2: 'RETURN_SCHEMA', 3: 'IMPLEMENTATION', 4: 'MODEL_AUTHORED_EXECUTABLE_TESTS'}[number]
            continue
        kept = [node for node in tree.body if isinstance(node, (ast.Import, ast.ImportFrom, ast.FunctionDef, ast.ClassDef))
                or isinstance(node, ast.Assign) and all(isinstance(t, ast.Name) and t.id.isupper() for t in node.targets)]
        for node in kept:
            if isinstance(node, ast.Assign):
                ast.literal_eval(node.value)
        namespace = {'__name__': f'raw_saved_{number}'}
        exec(compile(ast.Module(body=kept, type_ignores=[]), str(path), 'exec'), namespace)
        function = next(n for n in ('get_page_title', 'get_title_by_regex', 'fetch_title') if n in namespace)
        entries.append((f'raw-{number}', namespace[function]))
        record['omittedTopLevelLines'] = [n.lineno for n in tree.body if n not in kept]


def load_module(name, file):
    spec = importlib.util.spec_from_file_location(name, OUT / file)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


implementation = load_module('web_title', 'web_title.py')
tests = load_module('test_web_title', 'test_web_title.py')
entries.append(('enhanced', implementation.fetch_title))


def no_network(*args, **kwargs):
    raise AssertionError('Real transport forbidden in saved-code checks')


with patch('requests.get', side_effect=no_network), patch('requests.sessions.Session.request', side_effect=no_network):
    model_result = unittest.TextTestRunner(stream=io.StringIO()).run(unittest.defaultTestLoader.loadTestsFromModule(tests))


def response(html, code=200, content_type='text/html; charset=utf-8'):
    value = requests.Response()
    value.status_code = code
    value.headers['Content-Type'] = content_type
    value._content = html.encode('utf-8')
    value.encoding = 'utf-8'
    value.url = 'https://example.invalid/a'
    return value


def title(value):
    if isinstance(value, dict):
        return value.get('title')
    if isinstance(value, tuple):
        return value[-1]
    return value


observations = []


def check(name, function, predicate, url='https://example.invalid/a', result=None, error=None):
    with patch('requests.get', return_value=result or response('<title>Local title</title>'), side_effect=error) as network:
        try:
            value = function(url)
            passed = bool(predicate(value))
            reason = None
        except Exception as failure:
            passed, reason = False, type(failure).__name__
        observations.append({'test': name, 'pass': passed, 'failureType': reason,
                             'requestArgumentPreserved': not network.called or network.call_args.args[0] == url})


for name, function in entries:
    check(name + '-normal', function, lambda v: title(v) == 'Local title')
    check(name + '-utf8', function, lambda v: title(v) == '本机标题', result=response('<title>本机标题</title>'))
    check(name + '-entity', function, lambda v: title(v) == 'A & B', result=response('<title>A &amp; B</title>'))

enhanced = implementation.fetch_title
secret = 'SYNTHETIC_TEST_VALUE'
safe_output = lambda value: secret not in json.dumps(value, ensure_ascii=False)
for name, url in [
    ('token-response', 'https://example.invalid/a?token=' + secret),
    ('api-key-response', 'https://example.invalid/a?api_key=' + secret),
    ('userinfo-response', 'https://user:' + secret + '@example.invalid/a'),
    ('invalid-url-response', 'ftp://example.invalid/a?token=' + secret),
]:
    check('enhanced-' + name, enhanced, safe_output, url=url)
check('enhanced-timeout-output', enhanced, safe_output, url='https://example.invalid/a?token=' + secret,
      error=requests.exceptions.Timeout('synthetic timeout'))
check('enhanced-connection-error-output', enhanced, safe_output, url='https://example.invalid/a?api_key=' + secret,
      error=requests.exceptions.ConnectionError('synthetic failure'))
check('enhanced-batch-map-keys', lambda url: implementation.fetch_titles([url]), safe_output,
      url='https://example.invalid/a?token=' + secret)
check('enhanced-ordinary-query', enhanced, lambda v: title(v) == 'Local title', url='https://example.invalid/a?page=2')
py_compile.compile(__file__, doraise=True, cfile=str(OUT / 'independent-test.pyc'))
report = {'compiledBlocks': compiled, 'modelAuthoredTests': {'run': model_result.testsRun,
          'failures': len(model_result.failures), 'errors': len(model_result.errors)},
          'independentChecks': observations, 'networkCalls': 0, 'independentTestCompiled': True,
          'scope': 'Unchanged implementation and executable tests; raw external demo calls omitted. Interface signature is not a runnable file.',
          'notAnAcceptanceShortcut': 'Model tests asserting that result.url retains credentials may pass while independent security fails',
          'securityRule': 'Existing platform copied rule forbids credentials in response/error/logs; all values synthetic',
          'caseSelection': 'Fixed core/title and synthetic-credential criteria; entry-point mapping adapts to saved names, no work repair'}
(OUT / 'result.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({'modelTests': report['modelAuthoredTests'], 'independent': len(observations),
                  'passed': sum(x['pass'] for x in observations), 'failed': sum(not x['pass'] for x in observations), 'modelCalls': 0}))
