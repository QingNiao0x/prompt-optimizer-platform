"""预算机隔离压测：真实 HTTP/认证/持久化，延迟模型模拟器，无付费模型调用。

只在忽略的 target 目录保存临时测试凭据；结果禁止包含请求正文与凭据。
生成器和模拟上游在宿主机运行，待测 API/DB/Redis 在受限 Docker 里。
"""
from __future__ import annotations

import argparse
import base64
import concurrent.futures
import hashlib
import http.client
import io
import json
import math
import re
import secrets
import socket
import subprocess
import threading
import time
import zipfile
from collections import Counter
from http.cookies import SimpleCookie
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RUN = ROOT / 'target' / 'budget-load-20261006'
API_PORT = 19080
SIM_PORT = 19081
REDIS_PORT = 19379
COMPOSE = ROOT / 'infra' / 'load-test' / 'docker-compose.yml'
PHASE_LOCK = threading.Lock()
CURRENT_PHASE = 'setup'
PROMPT = '请为团队活动写一份中文通知的提示词。活动信息稍后补充。'


def save_json(path: Path, value: object) -> None:
    """只保存调用方已脱敏的指标和运行配置。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')


def secret(name: str) -> str:
    """只从本轮生成的非配置保护路径读取随机测试凭据。"""
    return (RUN / 'secrets' / name).read_text(encoding='utf-8').strip()


def prepare() -> None:
    """创建测试凭据；已有凭据继续使用，不覆盖文件。"""
    directory = RUN / 'secrets'
    directory.mkdir(parents=True, exist_ok=True)
    for name in ('spring.datasource.password', 'app.security.bootstrap-user.password',
                 'app.provider.openai-compatible.api-key'):
        path = directory / name
        if not path.exists():
            with path.open('x', encoding='utf-8') as stream:
                stream.write('B' + secrets.token_hex(24) + '7')
    manifest = []
    for path in sorted((RUN / 'build' / 'src' / 'main').rglob('*')):
        if path.is_file():
            manifest.append({'path': str(path.relative_to(RUN / 'build')).replace('\\', '/'),
                             'sha256': hashlib.sha256(path.read_bytes()).hexdigest()})
    save_json(RUN / 'source-manifest.json', manifest)
    print('prepared: random credentials in ignored target directory; no values printed', flush=True)


class Simulator:
    """可控延迟和故障，不存储请求内容或授权头。"""
    delay = 0.05
    status = 200
    active = 0
    peak = 0
    calls = 0
    lock = threading.Lock()


class SimulatorHandler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def log_message(self, *_args: object) -> None:
        pass

    def respond(self, status: int, body: object) -> None:
        data = json.dumps(body, ensure_ascii=False).encode('utf-8')
        try:
            self.send_response(status)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        except (BrokenPipeError, ConnectionResetError):
            pass  # 超时注入时，客户端按设计关闭连接。

    def authorized(self) -> bool:
        expected = 'Bearer ' + secret('app.provider.openai-compatible.api-key')
        return secrets.compare_digest(self.headers.get('Authorization', ''), expected)

    def do_GET(self) -> None:
        if self.path == '/health':
            self.respond(200, {'ready': True})
        elif self.path == '/control/metrics' and self.authorized():
            with Simulator.lock:
                state = {'calls': Simulator.calls, 'active': Simulator.active,
                         'peak': Simulator.peak, 'delay': Simulator.delay, 'status': Simulator.status}
            self.respond(200, state)
        else:
            self.respond(404, {'error': 'not_found'})

    def do_POST(self) -> None:
        if not self.authorized():
            self.respond(401, {'error': 'unauthorized'})
            self.close_connection = True
            return
        try:
            # Spring 的流式 HTTP 客户端可能发送 chunked，而非 Content-Length。
            chunks = []
            length = 0
            if self.headers.get('Transfer-Encoding', '').lower() == 'chunked':
                while True:
                    size = int(self.rfile.readline().strip().split(b';')[0], 16)
                    if size == 0:
                        while self.rfile.readline().strip():
                            pass
                        break
                    length += size
                    if length > 2 * 1024 * 1024:
                        raise ValueError('payload too large')
                    chunks.append(self.rfile.read(size))
                    if self.rfile.read(2) != b'\r\n':
                        raise ValueError('invalid chunk')
                raw = b''.join(chunks)
            else:
                length = int(self.headers.get('Content-Length', '0'))
                if length < 1 or length > 2 * 1024 * 1024:
                    raise ValueError('invalid size')
                raw = self.rfile.read(length)
            payload = json.loads(raw)
            if self.path == '/control':
                delay = float(payload.get('delay', 0.05))
                status = int(payload.get('status', 200))
                if not 0 <= delay <= 70 or status not in (200, 429, 503):
                    raise ValueError('control out of range')
                with Simulator.lock:
                    Simulator.delay, Simulator.status = delay, status
                self.respond(200, {'configured': True})
                return
            if self.path != '/chat/completions':
                self.respond(404, {'error': 'not_found'})
                return
        except (ValueError, TypeError, KeyError):
            self.respond(400, {'error': 'invalid_input'})
            self.close_connection = True
            return
        with Simulator.lock:
            delay, status = Simulator.delay, Simulator.status
            Simulator.active += 1
            Simulator.calls += 1
            Simulator.peak = max(Simulator.peak, Simulator.active)
        try:
            time.sleep(delay)
            if status != 200:
                self.respond(status, {'error': {'message': 'synthetic fault', 'type': 'load_test'}})
                return
            structured = {'sections': [
                {'type': 'BACKGROUND', 'title': '背景', 'content': '需要为团队活动整理通知写作要求。活动信息稍后补充。'},
                {'type': 'TASK', 'title': '任务', 'content': '为团队活动编写一份中文通知的提示词，等待具体活动信息。'},
                {'type': 'OUTPUT', 'title': '输出', 'content': '输出中文通知写作提示词，明确通知对象与活动信息的占位。'},
                {'type': 'CONSTRAINTS', 'title': '约束', 'content': '未知活动信息保留为待补充，不编造时间地点。'}
            ], 'ambiguities': []}
            self.respond(200, {'id': 'synthetic-completion', 'object': 'chat.completion',
                              'model': payload.get('model', 'budget-simulator'),
                              'choices': [{'index': 0, 'message': {'role': 'assistant',
                                                                   'content': json.dumps(structured, ensure_ascii=False)},
                                           'finish_reason': 'stop'}],
                              'usage': {'prompt_tokens': 1200, 'completion_tokens': 160, 'total_tokens': 1360}})
        finally:
            with Simulator.lock:
                Simulator.active -= 1


class SimulatorServer(ThreadingHTTPServer):
    request_queue_size = 256
    daemon_threads = True


def serve() -> None:
    print('simulator listening; paid model routes disabled', flush=True)
    SimulatorServer(('0.0.0.0', SIM_PORT), SimulatorHandler).serve_forever()


def control(delay: float, status: int = 200) -> dict:
    client = http.client.HTTPConnection('127.0.0.1', SIM_PORT, timeout=5)
    try:
        client.request('POST', '/control', json.dumps({'delay': delay, 'status': status}),
                       {'Content-Type': 'application/json',
                        'Authorization': 'Bearer ' + secret('app.provider.openai-compatible.api-key')})
        response = client.getresponse()
        result = json.loads(response.read())
        if response.status != 200:
            raise RuntimeError('simulator control failed')
        return result
    finally:
        client.close()


def redis_command(*args: str) -> bytes | list | int | None:
    """极小 RESP 客户端，仅读取隔离 Redis 内当前测试账户的验证码。"""
    parts = [arg.encode() for arg in args]
    request = b'*' + str(len(parts)).encode() + b'\r\n'
    request += b''.join(b'$' + str(len(part)).encode() + b'\r\n' + part + b'\r\n' for part in parts)
    with socket.create_connection(('127.0.0.1', REDIS_PORT), timeout=5) as connection:
        connection.sendall(request)
        stream = connection.makefile('rb')

        def read() -> bytes | list | int | None:
            marker, line = stream.read(1), stream.readline().rstrip(b'\r\n')
            if marker == b'$':
                size = int(line)
                if size < 0:
                    return None
                data = stream.read(size)
                stream.read(2)
                return data
            if marker == b'*':
                return [read() for _ in range(int(line))]
            if marker == b':':
                return int(line)
            if marker == b'+':
                return line
            raise RuntimeError('isolated Redis command failed')
        return read()


class Account:
    """各虚拟用户独立 Cookie、CSRF；认证数据只存于内存。"""
    def __init__(self, number: int = 1) -> None:
        self.number = number
        self.cookies: dict[str, str] = {}
        self.csrf = ''

    def request(self, method: str, path: str, body: object = None,
                binary: bool = False, timeout: int = 75) -> dict:
        connection = http.client.HTTPConnection('127.0.0.1', API_PORT, timeout=timeout)
        headers = {'Cookie': '; '.join(name + '=' + value for name, value in self.cookies.items())}
        if method not in ('GET', 'HEAD'):
            headers['X-XSRF-TOKEN'] = self.csrf
        data = body if binary else (json.dumps(body, ensure_ascii=False).encode() if body is not None else None)
        headers['Content-Type'] = 'application/octet-stream' if binary else 'application/json'
        started = time.perf_counter()
        try:
            connection.request(method, path, data, headers)
            response = connection.getresponse()
            for name, value in response.getheaders():
                if name.lower() == 'set-cookie':
                    cookie = SimpleCookie()
                    cookie.load(value)
                    self.cookies.update({key: item.value for key, item in cookie.items()})
            raw = response.read()
            try:
                parsed = json.loads(raw) if 'json' in response.getheader('Content-Type', '') else {}
            except ValueError:
                parsed = {}
            error = parsed.get('error') or {}
            code = parsed.get('code') or (error.get('code') if isinstance(error, dict) else None)
            return {'status': response.status, 'code': str(code or 'OK'), 'body': parsed,
                    'seconds': time.perf_counter() - started}
        except (OSError, http.client.HTTPException) as exception:
            return {'status': 0, 'code': type(exception).__name__, 'body': {},
                    'seconds': time.perf_counter() - started}
        finally:
            connection.close()

    def login(self) -> None:
        response = self.request('GET', '/api/v1/auth/csrf')
        self.csrf = response['body'].get('data', {}).get('token', '')
        if self.request('GET', '/api/v1/auth/captcha')['status'] != 200:
            raise RuntimeError('test captcha issuance failed')
        encoded = self.cookies.get('SESSION', '')
        try:
            session = base64.b64decode(encoded).decode()
        except (ValueError, UnicodeDecodeError):
            raise RuntimeError('test session decoding failed') from None
        value = redis_command('HGET', 'prompt-optimizer:session:sessions:' + session,
                              'sessionAttr:LOGIN_CAPTCHA')
        if not isinstance(value, bytes):
            raise RuntimeError('test captcha not found in isolated session')
        if value.startswith(b'\xac\xed'):
            captcha = value[-4:].decode('ascii')
        else:
            captcha = json.loads(value)
            if isinstance(captcha, list):
                captcha = captcha[-1]
        if not isinstance(captcha, str) or not re.fullmatch('[A-Z2-9]{4}', captcha):
            raise RuntimeError('unexpected test captcha format')
        response = self.request('POST', '/api/v1/auth/login',
                                {'identifier': f'budget-{self.number}@load.invalid',
                                 'password': secret('app.security.bootstrap-user.password'), 'captcha': captcha})
        if response['status'] != 200:
            raise RuntimeError('test login failed: ' + str(response['status']) + ' ' + response['code'])
        response = self.request('GET', '/api/v1/auth/csrf')
        self.csrf = response['body'].get('data', {}).get('token', '')
        if self.request('GET', '/api/v1/auth/me')['status'] != 200:
            raise RuntimeError('authenticated session verification failed')


def summarize(samples: list[dict]) -> dict:
    times = sorted(row['seconds'] for row in samples)
    def quantile(p: float) -> float:
        return round(times[max(0, math.ceil(len(times) * p) - 1)], 3) if times else 0.0
    return {'requests': len(samples), 'successes': sum(200 <= r['status'] < 300 for r in samples),
            'http': dict(Counter(str(r['status']) for r in samples)),
            'codes': dict(Counter(r['code'] for r in samples)),
            'p50_seconds': quantile(.5), 'p95_seconds': quantile(.95),
            'max_seconds': round(max(times, default=0), 3)}


def set_phase(name: str) -> None:
    global CURRENT_PHASE
    with PHASE_LOCK:
        CURRENT_PHASE = name
    print('phase: ' + name, flush=True)


def compose(*args: str) -> subprocess.CompletedProcess:
    import os
    env = dict(os.environ, BUDGET_LOAD_RUN_DIR=RUN.as_posix())
    return subprocess.run(['docker-compose', '-f', str(COMPOSE), '-p', 'prompt-optimizer-budget', *args],
                          env=env, capture_output=True, text=True, timeout=25)


def monitor(stop: threading.Event) -> None:
    """记录各容器实际用量及 WSL 整机内存，避免将堆上限当实际用量。"""
    # 后续文档上下文专项保留前面已经完成的资源采样。
    sample_path = RUN / 'resource-samples.json'
    samples = json.loads(sample_path.read_text(encoding='utf-8')) if sample_path.is_file() else []
    while not stop.is_set():
        try:
            data = subprocess.run(['docker', 'stats', '--no-stream', '--format', '{{json .}}',
                                   'prompt-optimizer-budget-api-1', 'prompt-optimizer-budget-postgres-1',
                                   'prompt-optimizer-budget-redis-1'], capture_output=True, text=True, timeout=12)
            rows = [json.loads(line) for line in data.stdout.splitlines() if line.startswith('{')]
            with PHASE_LOCK:
                phase = CURRENT_PHASE
            memory = subprocess.run(['docker', 'exec', 'prompt-optimizer-budget-redis-1',
                                     'cat', '/proc/meminfo'], capture_output=True, text=True, timeout=5)
            meminfo = {name: int(kb) for name, kb in re.findall(
                r'^(MemTotal|MemAvailable|SwapTotal|SwapFree):\s+(\d+) kB', memory.stdout, re.M)}
            samples.append({'timestamp': time.time(), 'phase': phase, 'containers': rows,
                            'wsl_memory_kib': meminfo})
            save_json(RUN / 'resource-samples.json', samples)
        except (subprocess.TimeoutExpired, ValueError):
            samples.append({'timestamp': time.time(), 'sampling_error': True})
        stop.wait(3)


def optimize(account: Account, context: dict | None = None) -> dict:
    result = account.request('POST', '/api/v1/optimizations',
                             {'rawPrompt': PROMPT, 'context': context or {'customDescription': '', 'files': []}})
    if result['status'] == 200:
        data = result['body'].get('data') or {}
        types = {section.get('type') for section in data.get('sections', [])}
        if not {'BACKGROUND', 'TASK', 'OUTPUT', 'CONSTRAINTS'} <= types or not data.get('optimizedPrompt'):
            result['status'], result['code'] = 0, 'INVALID_SUCCESS_BODY'
    return {k: result[k] for k in ('status', 'code', 'seconds')}


def generation(accounts: list[Account], concurrency: int, duration: int, delay: float,
               name_override: str | None = None, contexts: dict[int, dict] | None = None) -> dict:
    name = name_override or f'generation-{concurrency}-users-{int(delay)}s'
    set_phase(name)
    control(delay)
    deadline = time.monotonic() + duration
    started = time.monotonic()
    stop = threading.Event()
    probes: list[dict] = []
    ready = threading.Barrier(concurrency)

    def worker(account: Account) -> list[dict]:
        samples = []
        ready.wait()
        while time.monotonic() < deadline:
            row = optimize(account, (contexts or {}).get(account.number))
            samples.append(row)
            if row['status'] not in (200, 201):
                time.sleep(.5)
        return samples

    def probe() -> None:
        account = accounts[-1]
        while not stop.is_set():
            for path in ('/api/v1/auth/me', '/api/v1/optimization-history?current=1&size=10'):
                result = account.request('GET', path, timeout=10)
                probes.append({k: result[k] for k in ('status', 'code', 'seconds')})
            stop.wait(2)

    probe_thread = threading.Thread(target=probe, daemon=True)
    probe_thread.start()
    with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as pool:
        samples = [row for rows in pool.map(worker, accounts[:concurrency]) for row in rows]
    stop.set()
    probe_thread.join(timeout=15)
    elapsed = time.monotonic() - started
    result = {'name': name, 'virtual_users': concurrency, 'configured_duration_seconds': duration,
              'elapsed_seconds': round(elapsed, 2), 'upstream_delay_seconds': delay,
              **summarize(samples), 'light_requests': summarize(probes)}
    result['successes_per_minute'] = round(result['successes'] / elapsed * 60, 2)
    return result


def document_fixture(kind: str, size: int) -> bytes:
    """确定的合成语料；无真实用户文件。DOCX 使用有效的 OOXML 包。"""
    unit = '团队活动安排及通知资料。日期地点稍后补充。阅读材料仅供测试。\n'.encode()
    text = unit * (size // len(unit)) + b' ' * (size % len(unit))
    if kind == 'text':
        return text
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, 'w', compression=zipfile.ZIP_STORED) as package:
        package.writestr('[Content_Types].xml', '<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>')
        package.writestr('_rels/.rels', '<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>')
        paragraph = '<w:p><w:r><w:t>团队活动通知测试材料，信息稍后补充。</w:t></w:r></w:p>'
        document = '<?xml version="1.0" encoding="UTF-8"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>'
        document += paragraph * max(1, size // len(paragraph.encode()))
        document += '</w:body></w:document>'
        package.writestr('word/document.xml', document)
    return buffer.getvalue()


def upload(account: Account, content: bytes, language: str, keep: bool = False) -> dict:
    started = time.monotonic()
    path = 'synthetic-large.' + ('docx' if language == 'docx' else 'txt')
    result = account.request('POST', '/api/v1/context/documents',
                             {'path': path, 'language': language, 'sizeBytes': len(content)})
    if result['status'] != 201:
        return {'status': result['status'], 'code': result['code'], 'seconds': time.monotonic() - started}
    status = result['body']['data']
    document_id, chunk = status['documentId'], status['chunkSizeBytes']
    for index, offset in enumerate(range(0, len(content), chunk)):
        result = account.request('PUT', f'/api/v1/context/documents/{document_id}/chunks/{index}',
                                 content[offset:offset+chunk], binary=True)
        if result['status'] != 200:
            return {'status': result['status'], 'code': result['code'], 'seconds': time.monotonic() - started}
    uploaded = time.monotonic()
    result = account.request('POST', f'/api/v1/context/documents/{document_id}/complete', {})
    deadline = time.monotonic() + 180
    while result['status'] == 200 and time.monotonic() < deadline:
        status = result['body']['data']
        if status['phase'] in ('READY', 'PARTIAL', 'FAILED', 'CANCELLED'):
            break
        time.sleep(.3)
        result = account.request('GET', f'/api/v1/context/documents/{document_id}')
    row = {'status': 200 if status['phase'] == 'READY' else 500, 'code': status['phase'],
           'seconds': time.monotonic() - started, 'upload_seconds': uploaded - started,
           'extracted_characters': status.get('extractedCharacters', 0), 'chunks': status.get('chunkCount', 0)}
    if keep:
        row['context'] = {'customDescription': '', 'files': [{'path': path, 'language': language,
                                                            'content': '', 'documentId': document_id,
                                                            'sizeBytes': len(content)}]}
    return row


def documents(accounts: list[Account], users: int, language: str, size: int) -> dict:
    set_phase(f'upload-{users}-{language}-{size // 1048576}MiB')
    content = document_fixture(language, size)
    ready = threading.Barrier(users)
    def worker(account: Account) -> dict:
        ready.wait()
        return upload(account, content, language)
    with concurrent.futures.ThreadPoolExecutor(max_workers=users) as pool:
        samples = list(pool.map(worker, accounts[:users]))
    return {'name': CURRENT_PHASE, 'virtual_users': users, 'bytes_per_document': len(content),
            **summarize(samples), 'details': samples}


def boundaries(accounts: list[Account]) -> list[dict]:
    """验证限流/上游故障后的释放、输入边界和跨用户访问拒绝。"""
    set_phase('boundaries-and-recovery')
    account = accounts[0]
    result = []
    control(.05)
    for name, path, body in (
        ('empty_prompt', '/api/v1/optimizations', {'rawPrompt': ''}),
        ('oversized_document', '/api/v1/context/documents', {'path': 'test.txt', 'language': 'text', 'sizeBytes': 52428801}),
        ('invalid_path', '/api/v1/context/documents', {'path': '../test.txt', 'language': 'text', 'sizeBytes': 1})):
        row = account.request('POST', path, body)
        result.append({'name': name, **{k: row[k] for k in ('status', 'code', 'seconds')}})
    created = account.request('POST', '/api/v1/context/documents',
                              {'path': 'ownership.txt', 'language': 'text', 'sizeBytes': 10})
    if created['status'] == 201:
        document_id = created['body']['data']['documentId']
        row = accounts[1].request('GET', '/api/v1/context/documents/' + document_id)
        result.append({'name': 'cross_user_document', **{k: row[k] for k in ('status', 'code', 'seconds')}})
        row = account.request('PUT', f'/api/v1/context/documents/{document_id}/chunks/-1', b'x', binary=True)
        result.append({'name': 'negative_chunk_index', **{k: row[k] for k in ('status', 'code', 'seconds')}})
    history = account.request('GET', '/api/v1/optimization-history?current=1&size=10')
    records = history['body'].get('data', {}).get('records', [])
    if records:
        row = accounts[1].request('GET', '/api/v1/optimization-history/' + str(records[0]['id']))
        result.append({'name': 'cross_tenant_history', **{k: row[k] for k in ('status', 'code', 'seconds')}})
    for name, delay, status in (('upstream_429', .05, 429), ('upstream_503', .05, 503),
                                ('upstream_timeout', 65, 200)):
        control(delay, status)
        row = optimize(account)
        result.append({'name': name, **row})
        control(.05)
        result.append({'name': name + '_recovery', **optimize(account)})
    control(3)
    barrier = threading.Barrier(4)
    def call(_index: int) -> dict:
        barrier.wait()
        return optimize(account)
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        rows = list(pool.map(call, range(4)))
    result.append({'name': 'per_user_limit_3', **summarize(rows)})
    control(.05)
    result.append({'name': 'per_user_limit_recovery', **optimize(account)})
    if len(accounts) >= 101:
        control(5)
        barrier = threading.Barrier(101)
        def global_call(other: Account) -> dict:
            barrier.wait()
            return optimize(other)
        with concurrent.futures.ThreadPoolExecutor(max_workers=101) as pool:
            rows = list(pool.map(global_call, accounts[:101]))
        result.append({'name': 'global_limit_100', **summarize(rows)})
        control(.05)
        result.append({'name': 'global_limit_recovery', **optimize(account)})
    return result


def run(mode: str) -> None:
    stop = threading.Event()
    monitor_thread = threading.Thread(target=monitor, args=(stop,), daemon=True)
    monitor_thread.start()
    report: dict = {'started_at': time.strftime('%Y-%m-%d %H:%M:%S'), 'mode': mode,
                    'model': 'local synthetic OpenAI-compatible upstream; no paid calls', 'phases': []}
    try:
        account_count = 4 if mode == 'smoke' else 6 if mode == 'contexts' else 106
        accounts = [Account(n) for n in range(1, account_count + 1)]
        print('logging in synthetic users through normal CSRF/captcha/password flow', flush=True)
        for n, account in enumerate(accounts):
            account.login()
            if (n + 1) % 20 == 0:
                print(f'authenticated users: {n + 1}', flush=True)
        control(.05)
        smoke = optimize(accounts[0])
        report['smoke'] = smoke
        save_json(RUN / ('results-' + mode + '.json'), report)
        if smoke['status'] != 200:
            raise RuntimeError('enhancement smoke failed: ' + str(smoke['status']) + ' ' + smoke['code'])
        if mode == 'contexts':
            set_phase('indexed-context-document-preparation')
            content = document_fixture('docx', 10*1048576)
            with concurrent.futures.ThreadPoolExecutor(max_workers=5) as pool:
                uploads = list(pool.map(lambda account: upload(account, content, 'docx', keep=True), accounts[:5]))
            report['uploads'] = [{k: v for k, v in row.items() if k != 'context'} for row in uploads]
            if any(row['status'] != 200 for row in uploads):
                raise RuntimeError('indexed context preparation failed')
            contexts = {account.number: row['context'] for account, row in zip(accounts, uploads)}
            report['phases'].append(generation(accounts, 5, 90, 10,
                                               'indexed-context-5-users-10MiB-docx', contexts))
        elif mode == 'smoke':
            report['boundaries'] = boundaries(accounts)
            report['phases'].append(documents(accounts, 1, 'text', 1048576))
        else:
            for users, duration, delay in ((5, 60, 10), (10, 60, 10), (20, 120, 10),
                                            (50, 120, 10), (100, 120, 10), (50, 300, 30)):
                result = generation(accounts, users, duration, delay)
                report['phases'].append(result)
                save_json(RUN / ('results-' + mode + '.json'), report)
                print(json.dumps(result, ensure_ascii=False), flush=True)
            control(.05)
            for users, kind, size in ((1, 'text', 10*1048576), (5, 'text', 10*1048576),
                                       (10, 'text', 10*1048576), (5, 'docx', 10*1048576),
                                       (10, 'docx', 10*1048576), (2, 'text', 50*1048576)):
                result = documents(accounts, users, kind, size)
                report['phases'].append(result)
                save_json(RUN / ('results-' + mode + '.json'), report)
                print(json.dumps({k: v for k, v in result.items() if k != 'details'}), flush=True)
            mixed_name = 'mixed-20-generating-5-uploading-10MiB-docx'
            set_phase(mixed_name)
            content = document_fixture('docx', 10*1048576)
            with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
                generating = pool.submit(generation, accounts, 20, 90, 30, mixed_name)
                uploads = list(pool.map(lambda account: upload(account, content, 'docx'), accounts[20:25]))
                generated = generating.result()
            report['phases'].append({'name': mixed_name, 'generating': generated,
                                     'documents': summarize(uploads), 'document_details': uploads})
            save_json(RUN / ('results-' + mode + '.json'), report)
            control(.05)
            report['boundaries'] = boundaries(accounts)
        report['completed'] = True
    except Exception as exception:
        report['completed'] = False
        report['failure_type'] = type(exception).__name__
        print('load test stopped: ' + type(exception).__name__ + ' ' + str(exception), flush=True)
    finally:
        stop.set()
        monitor_thread.join(timeout=15)
        report['ended_at'] = time.strftime('%Y-%m-%d %H:%M:%S')
        save_json(RUN / ('results-' + mode + '.json'), report)
    if not report['completed']:
        raise SystemExit(1)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('prepare', 'serve', 'smoke', 'full', 'contexts'))
    args = parser.parse_args()
    if args.action == 'prepare':
        prepare()
    elif args.action == 'serve':
        serve()
    else:
        run(args.action)


if __name__ == '__main__':
    main()
