"""Compile unchanged model blocks and check local-only behaviours; never execute external demos."""
import ast
import hashlib
import http.server
import io
import json
from pathlib import Path
import py_compile
import re
import threading
import unittest
from unittest.mock import patch
from urllib.parse import urlparse

import requests

ROOT = Path(__file__).resolve().parents[4]
WORKS = ROOT / 'tmp/me00a-candidate17-live-20261016/real/requests-title'
OUT = WORKS / 'code-check'
OUT.mkdir(exist_ok=False)
entries, compiled = [], []
for arm in ('raw', 'enhanced'):
    text = (WORKS / f'{arm}-work.md').read_text(encoding='utf-8')
    for number, block in enumerate(re.findall(r'```python\s*\n(.*?)```', text, re.S), 1):
        filename = OUT / f'{arm}-{number}.py'
        filename.write_text(block, encoding='utf-8', newline='')
        py_compile.compile(str(filename), doraise=True)
        interface_only = arm == 'enhanced' and number == 1
        compiled.append({'file': filename.name, 'sha256': hashlib.sha256(block.encode()).hexdigest(),
                         'compiled': True, 'role': 'INTERFACE_SPECIFICATION' if interface_only else 'IMPLEMENTATION'})
        if interface_only:
            continue
        tree = ast.parse(block)
        kept, omitted = [], []
        for node in tree.body:
            keep = isinstance(node, (ast.Import, ast.ImportFrom, ast.FunctionDef, ast.ClassDef))
            if isinstance(node, ast.Assign) and all(isinstance(t, ast.Name) and t.id.isupper() for t in node.targets):
                ast.literal_eval(node.value)
                keep = True
            (kept if keep else omitted).append(node)
        namespace = {'__name__': 'model_work_under_test'}
        exec(compile(ast.Module(body=kept, type_ignores=[]), str(filename), 'exec'), namespace)
        function = next(name for name in ('get_page_title', 'fetch_title', 'get_title') if name in namespace)
        entries.append({'arm': arm, 'number': number, 'namespace': namespace, 'function': function})
        compiled[-1]['omittedTopLevelNodeLines'] = [node.lineno for node in omitted]


class FixtureHandler(http.server.BaseHTTPRequestHandler):
    """Synthetic loopback HTML only; redirects cannot leave this server."""
    def do_GET(self):
        if self.path == '/redirect':
            self.send_response(302)
            self.send_header('Location', '/normal')
            self.end_headers()
            return
        status, kind = 200, 'text/html; charset=utf-8'
        path = self.path.split('?', 1)[0]
        body = {'/normal': '<html><title>Local title</title></html>',
                '/utf8': '<title>本机测试标题</title>' + '本机编码验证文本。' * 20,
                '/absent': '<html>No title</html>', '/empty': '<title></title>',
                '/entity': '<title>A &amp; B</title>', '/json': '{"title":"JSON fixture"}'}.get(path)
        if path == '/json':
            kind = 'application/json'
        if body is None:
            status, body = 503, '<title>Upstream error</title>'
        data = body.encode('utf-8')
        self.send_response(status)
        self.send_header('Content-Type', kind)
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *_):
        pass


server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), FixtureHandler)
threading.Thread(target=server.serve_forever, daemon=True).start()
BASE = f'http://127.0.0.1:{server.server_port}'
real_get = requests.get


def guarded_get(url, *args, **kwargs):
    """Only this independently created fixture server is reachable by the generated functions."""
    parsed = urlparse(url)
    if parsed.scheme != 'http' or parsed.hostname != '127.0.0.1' or parsed.port != server.server_port:
        raise AssertionError('External network attempt blocked')
    return real_get(url, *args, **kwargs)


def call(entry, route):
    return entry['namespace'][entry['function']](BASE + route)


def title(value):
    return value.get('title') if isinstance(value, dict) else value[1] if isinstance(value, tuple) else value


class WorkChecks(unittest.TestCase):
    """Per-arm requirements stay distinct; supplemental failures never rewrite the original request."""


labels = {}


def install(name, tier, fn):
    setattr(WorkChecks, name, fn)
    labels[name] = tier


for item in entries:
    def normal(self, e=item):
        self.assertEqual('Local title', title(call(e, '/normal')))

    def utf8(self, e=item):
        self.assertEqual('本机测试标题', title(call(e, '/utf8')))

    def absent(self, e=item):
        value = call(e, '/absent')
        self.assertEqual('no_title', value['error']) if isinstance(value, dict) else self.assertIn('无', title(value))

    def timeout(self, e=item):
        with patch('requests.get', side_effect=requests.Timeout('synthetic timeout')):
            value = call(e, '/normal')
        self.assertEqual('timeout', value['error']) if isinstance(value, dict) else self.assertRegex(title(value), '失败|错误')

    def http_error(self, e=item):
        value = call(e, '/503')
        self.assertEqual('http_503', value['error']) if isinstance(value, dict) else self.assertRegex(title(value), '失败|错误')

    def empty(self, e=item):
        value = call(e, '/empty')
        self.assertEqual('empty_title', value['error']) if isinstance(value, dict) else self.assertIn('无', title(value))

    def entity(self, e=item):
        self.assertEqual('A & B', title(call(e, '/entity')))

    for name, tier, fn in [('normal', 'CORE', normal), ('utf8', 'PREFROZEN_BOUNDARY', utf8),
                           ('absent', 'PREFROZEN_BOUNDARY', absent), ('timeout', 'PREFROZEN_BOUNDARY', timeout),
                           ('http_error', 'PREFROZEN_BOUNDARY', http_error), ('empty', 'SUPPLEMENTAL', empty),
                           ('entity', 'SUPPLEMENTAL', entity)]:
        install(f'test_{item["arm"]}_{item["number"]}_{name}', tier, fn)

enhanced = next(e for e in entries if e['arm'] == 'enhanced')


def non_html(self):
    self.assertEqual('non_html', call(enhanced, '/json')['error'])


def batch(self):
    urls = [BASE + r for r in ('/normal', '/503', '/absent', '/utf8')]
    values = enhanced['namespace']['fetch_titles'](urls)
    self.assertEqual(urls, [v['url'] for v in values])
    self.assertEqual(['Local title', None, None, '本机测试标题'], [v['title'] for v in values])


def invalid_url(self):
    with self.assertRaises(ValueError):
        enhanced['namespace']['fetch_title']('ftp://invalid')


def empty_batch(self):
    with self.assertRaises(ValueError):
        enhanced['namespace']['fetch_titles']([])


def json_output(self):
    value = enhanced['namespace']['fetch_titles'](BASE + '/normal', as_json=True)
    self.assertEqual('Local title', json.loads(value)[0]['title'])


def redirect(self):
    self.assertEqual('Local title', title(call(enhanced, '/redirect')))


def synthetic_token_response(self):
    # Added after code inspection: verifies the existing copied platform security rule,
    # not an extra user requirement or a new secret. No real credential enters the test.
    value = call(enhanced, '/normal?token=SYNTHETIC_TEST_VALUE')
    self.assertNotIn('SYNTHETIC_TEST_VALUE', json.dumps(value))


for name, fn in [('non_html', non_html), ('batch_order_and_failure_isolation', batch),
                 ('invalid_url', invalid_url), ('empty_batch', empty_batch),
                 ('json_output', json_output), ('redirect', redirect)]:
    install('test_enhanced_' + name, 'ENHANCED_COPIED_CONTRACT', fn)
install('test_enhanced_synthetic_token_response', 'POST_INSPECTION_EXISTING_SECURITY_RULE', synthetic_token_response)


class RecordingResult(unittest.TextTestResult):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.observations = []

    def addSuccess(self, test):
        super().addSuccess(test)
        self.observations.append({'test': test._testMethodName, 'tier': labels[test._testMethodName], 'status': 'PASS'})

    def addFailure(self, test, error):
        super().addFailure(test, error)
        self.observations.append({'test': test._testMethodName, 'tier': labels[test._testMethodName], 'status': 'FAIL', 'reason': str(error[1])})

    def addError(self, test, error):
        super().addError(test, error)
        self.observations.append({'test': test._testMethodName, 'tier': labels[test._testMethodName], 'status': 'ERROR', 'reason': f'{error[0].__name__}: {error[1]}'})


py_compile.compile(__file__, doraise=True, cfile=str(OUT / 'independent-test.pyc'))
try:
    with patch('requests.get', side_effect=guarded_get):
        result = unittest.TextTestRunner(stream=io.StringIO(), resultclass=RecordingResult).run(unittest.defaultTestLoader.loadTestsFromTestCase(WorkChecks))
finally:
    server.shutdown()
    server.server_close()
report = {'compiledBlocks': compiled, 'functionalImplementations': len(entries), 'modelAuthoredTestFiles': 0,
          'independentTestCompiled': True, 'tests': result.testsRun, 'failures': len(result.failures), 'errors': len(result.errors),
          'observations': result.observations, 'externalNetworkCallsByGeneratedCode': 0,
          'scope': 'Unchanged function/class definitions; external-site demonstrations not executed; no claim of full CLI acceptance',
          'securityTestTiming': 'Synthetic response-token check added after inspection, bound to an existing copied requirement'}
(OUT / 'result.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({k: report[k] for k in ('tests', 'failures', 'errors', 'functionalImplementations', 'modelAuthoredTestFiles')}))
