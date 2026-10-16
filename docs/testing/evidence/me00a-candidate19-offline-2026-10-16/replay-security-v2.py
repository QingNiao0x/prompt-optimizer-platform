"""Diagnostic reference only: replay saved code, never replace a model work or a product response."""
import ast
import difflib
import hashlib
import json
from pathlib import Path
import py_compile
import re

import requests

ROOT = Path(__file__).resolve().parents[4]
OUT = Path(__file__).resolve().parent / 'security-replay-v2'
OUT.mkdir(exist_ok=False)
WORK = ROOT / 'docs/testing/evidence/me00a-candidate17-live-2026-10-16/real/requests-title/enhanced-work.md'
original = re.findall(r'```python\s*\n(.*?)```', WORK.read_text(encoding='utf-8'), re.S)[1]

# Only display and invalid-input messages change. HTTP continues to receive the original URL.
helper = '''
def _display_url(value):
    """Reference locator redaction; does not claim to detect arbitrary secrets or redact HTML titles."""
    import re
    from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit
    if not isinstance(value, str):
        return "<invalid-url>"
    try:
        parsed = urlsplit(value)
        keys = {"token", "accesstoken", "refreshtoken", "apikey", "password", "secret", "authorization"}
        pairs = parse_qsl(parsed.query, keep_blank_values=True)
        sensitive = lambda key: re.sub(r"[-_]", "", key).casefold() in keys
        auth = "@" in parsed.netloc
        if not auth and not any(sensitive(key) for key, _ in pairs):
            return value
        query = urlencode([(key, "[REDACTED]" if sensitive(key) else item) for key, item in pairs])
        return urlunsplit((parsed.scheme, parsed.netloc.rsplit("@", 1)[-1], parsed.path, query, parsed.fragment))
    except (ValueError, TypeError):
        return "<invalid-url>"
'''
repaired = original
for old, new in [
    ('raise ValueError(f"invalid url: {url!r}")', 'raise ValueError("invalid url: expected http(s) URL")'),
    ('result = {"url": url,', 'result = {"url": _display_url(url),'),
    ('results.append({"url": u,', 'results.append({"url": _display_url(u),'),
]:
    assert repaired.count(old) == 1, 'Reference patch requires the exact captured code'
    repaired = repaired.replace(old, new)
repaired += '\n' + helper


def load(code, name):
    """Compile unchanged full text, then omit external top-level demonstration calls."""
    path = OUT / name
    path.write_text(code, encoding='utf-8', newline='')
    py_compile.compile(str(path), doraise=True, cfile=str(path.with_suffix('.pyc')))
    tree = ast.parse(code)
    nodes = [n for n in tree.body if isinstance(n, (ast.Import, ast.ImportFrom, ast.FunctionDef, ast.ClassDef))
             or isinstance(n, ast.Assign) and all(isinstance(t, ast.Name) and t.id.isupper() for t in n.targets)]
    namespace = {'__name__': 'saved_work_diagnostic'}
    exec(compile(ast.Module(body=nodes, type_ignores=[]), str(path), 'exec'), namespace)
    return namespace


class LocalResponse:
    status_code = 200
    headers = {'Content-Type': 'text/html; charset=utf-8'}
    content = b'<html><title>Local title</title></html>'
    encoding = 'utf-8'
    apparent_encoding = 'utf-8'


class FakeSession:
    """No transport: records the exact address and optionally raises a synthetic exception."""
    def __init__(self, error=None):
        self.urls = []
        self.error = error

    def get(self, url, **kwargs):
        self.urls.append(url)
        if self.error:
            raise self.error
        return LocalResponse()


observations = []
urls = [
    ('query-token', 'https://example.invalid/a?token=SYNTHETIC_TEST_VALUE'),
    ('access-token', 'https://example.invalid/a?access_token=SYNTHETIC_TEST_VALUE&page=2'),
    ('mixed-case-api-key', 'https://example.invalid/a?Api_Key=SYNTHETIC_TEST_VALUE'),
    ('encoded-key', 'https://example.invalid/a?api%5Fkey=SYNTHETIC_TEST_VALUE'),
    ('duplicate-query', 'https://example.invalid/a?token=SYNTHETIC_TEST_VALUE&token=SYNTHETIC_TEST_VALUE'),
    ('userinfo', 'https://user:SYNTHETIC_TEST_VALUE@example.invalid/a'),
]
for arm, code in [('ORIGINAL_WORK', original), ('DIAGNOSTIC_REFERENCE_ONLY', repaired)]:
    namespace = load(code, arm.lower() + '.py')
    for name, url in urls:
        session = FakeSession()
        value = namespace['fetch_title'](url, session=session)
        observations.append({'arm': arm, 'case': name, 'pass': 'SYNTHETIC_TEST_VALUE' not in json.dumps(value),
                             'originalRequestPreserved': session.urls == [url], 'titleCorrect': value['title'] == 'Local title'})
    url = 'https://example.invalid/a?page=2&sort=date'
    session = FakeSession()
    value = namespace['fetch_title'](url, session=session)
    observations.append({'arm': arm, 'case': 'ordinary-query-unchanged', 'pass': value['url'] == url,
                         'originalRequestPreserved': session.urls == [url], 'titleCorrect': value['title'] == 'Local title'})
    url = urls[0][1]
    session = FakeSession(requests.exceptions.Timeout('synthetic timeout'))
    value = namespace['fetch_title'](url, session=session)
    observations.append({'arm': arm, 'case': 'timeout-output', 'pass': 'SYNTHETIC_TEST_VALUE' not in json.dumps(value),
                         'originalRequestPreserved': session.urls == [url], 'errorCorrect': value['error'] == 'timeout'})
    invalid = 'ftp://example.invalid/a?token=SYNTHETIC_TEST_VALUE'
    try:
        namespace['fetch_title'](invalid, session=FakeSession())
        message = 'unexpected success'
    except ValueError as error:
        message = str(error)
    observations.append({'arm': arm, 'case': 'invalid-url-error',
                         'pass': 'SYNTHETIC_TEST_VALUE' not in message and message != 'unexpected success'})
    value = namespace['fetch_titles']([invalid], session=FakeSession(), as_json=True)
    observations.append({'arm': arm, 'case': 'batch-json-error', 'pass': 'SYNTHETIC_TEST_VALUE' not in value})

report = {'modelCalls': 0, 'networkCalls': 0, 'originalWorkSha256': hashlib.sha256(WORK.read_bytes()).hexdigest(),
          'originalCodeSha256': hashlib.sha256(original.encode()).hexdigest(),
          'referenceCodeSha256': hashlib.sha256(repaired.encode()).hexdigest(), 'observations': observations,
          'scope': 'Reference demonstrates a possible local repair, NOT a new model output or a platform quality pass',
          'limitations': ['Not an arbitrary-secret detector', 'No claim to sanitize secrets in HTML content or unknown query keys',
                          'Does not prove a downstream model will generate the reference repair']}
(OUT / 'result.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
(OUT / 'reference.patch').write_text(''.join(difflib.unified_diff(original.splitlines(True), repaired.splitlines(True),
    fromfile='original-work-code.py', tofile='diagnostic-reference-only.py')), encoding='utf-8')
print(json.dumps({'checks': len(observations), 'arms': {arm: sum(o['pass'] for o in observations if o['arm'] == arm)
                 for arm in ('ORIGINAL_WORK', 'DIAGNOSTIC_REFERENCE_ONLY')}, 'newModelCalls': 0}))
