"""复用已冻结的16项CSV行为检查；执行前源码由主代理阅读，不把生成结果当作可信指令。"""
import hashlib
import json
from pathlib import Path
import subprocess
import sys

repo = Path(__file__).resolve().parents[4]
evidence = Path(__file__).resolve().parent
private = repo / 'tmp' / 'natural-writer-input70-2026-10-29' / 'simple-code-checks'
trace = json.loads((evidence / 'traces/SIMPLE_N2_EXEC.json').read_text(encoding='utf-8'))
payload = json.loads(trace['response']['choices'][0]['message']['content'])
assert list(payload) == ['files'] and len(payload['files']) == 1
file = payload['files'][0]
assert file['path'] == 'daily_net.py' and isinstance(file['content'], str)
private.mkdir(parents=True, exist_ok=True)
source = private / 'daily_net.py'
assert not source.exists()
source.write_text(file['content'], encoding='utf-8')
# 已阅读源码：仅使用标准库和指定CSV/临时文件，无联网、外部命令或账号访问。
checker = repo / 'docs/testing/evidence/natural-writer-input70-2026-10-29/verify_csv_contract.py'
result = subprocess.run([sys.executable, str(checker), str(source), str(private / 'fixtures')],
                        cwd=repo, capture_output=True, text=True, encoding='utf-8', timeout=120)
assert result.returncode == 0, result.stderr
checks = json.loads(result.stdout)
checks.update(sourceSha256=hashlib.sha256(file['content'].encode()).hexdigest(),
              checkerSha256=hashlib.sha256(checker.read_bytes()).hexdigest(),
              inspectedByRootAgent=True, humanReviewerParticipated=False)
target = evidence / 'simple-csv-checks.json'
assert not target.exists()
target.write_text(json.dumps(checks, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({'checks': checks['checks'], 'passed': checks['passed'],
                  'failed': [item['case'] for item in checks['results'] if not item['passed']]}))
