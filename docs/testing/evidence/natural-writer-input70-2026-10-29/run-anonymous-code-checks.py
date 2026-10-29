"""运行主代理已逐份查看的四个合成作品；版本映射在内部AI结论保存前保持隐藏。"""
import hashlib
import json
from pathlib import Path
import subprocess
import sys

repo = Path(__file__).resolve().parents[4]
evidence = Path(__file__).resolve().parent
private = repo / 'tmp' / 'natural-writer-input70-2026-10-29' / 'code-checks'
private.mkdir(parents=True, exist_ok=True)
works = json.loads((evidence / 'anonymous-works.json').read_text(encoding='utf-8'))['works']
results = []
for work in works:
    if work['caseId'] != 'W70-N2':
        continue
    assert work['workId'] in ('W02', 'W04', 'W07', 'W08')
    assert work['status'] == 200
    payload = json.loads(work['content'])
    assert isinstance(payload, dict) and list(payload) == ['files']
    assert len(payload['files']) == 1
    file = payload['files'][0]
    assert file['path'] == 'daily_net.py' and isinstance(file['content'], str)
    root = private / work['workId']
    root.mkdir(parents=True, exist_ok=True)
    source = root / 'daily_net.py'
    assert not source.exists()
    source.write_text(file['content'], encoding='utf-8')
    # 主代理已查看这些源码：仅访问命令参数指定的本机CSV、输出及临时文件，无网络或外部命令。
    process = subprocess.run([sys.executable, str(evidence / 'verify_csv_contract.py'), str(source), str(root / 'fixtures')],
                             cwd=repo, capture_output=True, text=True, encoding='utf-8', timeout=120)
    assert process.returncode == 0, process.stderr
    result = json.loads(process.stdout)
    result['workId'] = work['workId']
    result['sourceSha256'] = hashlib.sha256(file['content'].encode()).hexdigest()
    result['jsonSingleFileContractPassed'] = True
    result['sourceInspectedByRootAgentBeforeExecution'] = True
    result['humanReviewerParticipated'] = False
    results.append(result)
target = evidence / 'code-contract-results.json'
assert not target.exists()
target.write_text(json.dumps({'works': results, 'predeclaredChecksPerWork': 16,
                             'versionLabelsMasked': True, 'realModelCalls': 0}, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps([{'workId': item['workId'], 'checks': item['checks'], 'passed': item['passed'],
                   'failed': [test['case'] for test in item['results'] if not test['passed']]} for item in results]))
