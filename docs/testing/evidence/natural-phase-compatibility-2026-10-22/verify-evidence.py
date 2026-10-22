"""从公开原件复算本轮调用、作品与兼容证据，不调用模型，不改变评分。"""
from pathlib import Path
from collections import Counter
import hashlib
import json

root = Path(__file__).resolve().parent
read = lambda name: json.loads((root / name).read_text(encoding='utf-8-sig'))
protocol = read('protocol.json')
closure = read('closure.json')
results = read('http-results.json')
traces = [json.loads(line) for line in (root / 'transports.jsonl').read_text(encoding='utf-8').splitlines()]
assert len(traces) == closure['actualCalls'] <= protocol['maximumNewActualCalls']
assert dict(Counter(trace['phase'] for trace in traces)) == closure['phaseCounts']
assert all(trace['jobId'] in protocol['allowedJobs'] for trace in traces)
assert all('response' in trace or 'transportFailure' in trace for trace in traces)
assert sum(count - 1 for count in Counter(trace['jobId'] for trace in traces).values()) == closure['protocolRetries']
assert closure['newAuthorizationActual'] == protocol['taskPreviouslyActual'] + len(traces) <= protocol['taskMaximumNewActualCalls']
assert all(row['equal'] for row in read('same-information-checks.json')['checks'])
history = read('history-checks.json')['checks']
assert len(history) == sum(result['phase'] == 'GENERATION' and result['status'] == 200 for result in results)
assert all(row['status'] == 200 and all(row[key] for key in ['bodyEqual', 'versionEqual', 'noDefaultRules', 'rulesEqual', 'noInternalReviewOnWire']) for row in history)
works = [result for result in results if result['phase'] == 'EXECUTION']
assert len(works) == 4 and all(result['status'] == 200 and result['body']['data']['finishReason'] == 'stop' for result in works)
for result in works:
    assert (root / 'works' / (result['id'] + '.md')).read_text(encoding='utf-8') == result['body']['data']['content']
    assert result['request']['messages'][0]['content'] == '执行用户请求并交付其要求的作品。无法访问外部工具或文件，不声称已经运行、联网或完成未执行的操作。'


def inspect_keys(value):
    if isinstance(value, dict):
        assert not {'authorization', 'cookie', 'password', 'apikey', 'privatekey', 'reasoning_content'}.intersection(key.lower() for key in value)
        for item in value.values():
            inspect_keys(item)
    elif isinstance(value, list):
        for item in value:
            inspect_keys(item)


for value in traces + results:
    inspect_keys(value)
before = {(row['LocalPort'], row['OwningProcess']) for row in read('environment-before.json')}
after = {(row['LocalPort'], row['OwningProcess']) for row in read('environment-after.json')}
assert before == after
prior = root.parent / 'natural-phase-protocol-2026-10-22'
for phase in ['enhancement', 'planning']:
    assert (root / (phase + '-v24.txt')).read_bytes() == (prior / (phase + '-v21.txt')).read_bytes()
html = read('html-work-checks.json')
assert len(html['rows']) == 1 and html['rows'][0]['passed']
out = dict(actualCalls=len(traces), taskActualCalls=closure['newAuthorizationActual'], taskAllowance=protocol['taskMaximumNewActualCalls'],
    taskUnspent=closure['newAuthorizationUnspent'], logicalJobs=len(results), successfulJobs=sum(result['status'] == 200 for result in results),
    generationSucceeded=len(history), works=len(works), protocolRetries=closure['protocolRetries'], qualityRetries=0,
    htmlBehaviorChecks=len(html['rows'][0]['checks']), allWorksComplete=True, noAuthenticationOrHiddenReasoningKeys=True,
    phaseInstructionsUnchanged=True, environmentPortsAndOwnersPreserved=True, defaultVersion=closure['defaultVersionAfter'],
    artifacts=[dict(id=result['id'], sha256=hashlib.sha256(result['body']['data']['content'].encode()).hexdigest()) for result in works],
    limitation='回归原件与确定性行为检查不证明整体语义改善；人工用户验收未进行。')
print(json.dumps(out, ensure_ascii=False, indent=2))
