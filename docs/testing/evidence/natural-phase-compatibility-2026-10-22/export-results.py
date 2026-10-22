"""封存协议兼容复验原件及总账；只读取合成结果，不读取认证信息或调用模型。"""
from pathlib import Path
from collections import Counter, defaultdict
import datetime
import hashlib
import json
import statistics

OUT = Path(__file__).resolve().parent
ROOT = OUT.parents[3]
PRIVATE = ROOT / 'tmp/natural-phase-compatibility-20261022'
PREVIOUS = OUT.parent / 'natural-phase-protocol-2026-10-22'
read = lambda path: json.loads(path.read_text(encoding='utf-8-sig'))


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('x', encoding='utf-8', newline='\n') as handle:
        handle.write(value if isinstance(value, str) else json.dumps(value, ensure_ascii=False, indent=2) + '\n')


protocol = read(OUT / 'protocol.json')
traces = [read(path) for path in sorted((PRIVATE / 'traces').glob('*.json'))]
results = [read(path) for path in sorted((PRIVATE / 'results').glob('*.json'))]
by_id = {result['id']: result for result in results}
previous = {result['id']: result for result in read(PREVIOUS / 'http-results.json')}
counts = Counter(trace['phase'] for trace in traces)
assert all(trace['jobId'] in protocol['allowedJobs'] for trace in traces)
assert len(traces) <= protocol['maximumNewActualCalls']
assert all(count <= protocol['phaseLimits'][phase] for phase, count in counts.items())
assert all('response' in trace or 'transportFailure' in trace for trace in traces), 'Pending attempt'
manifest = read(OUT / 'source-manifest.json')
assert all(hashlib.sha256((ROOT / row['path']).read_bytes()).hexdigest() == row['sha256'] for row in manifest['files'])
history = read(OUT / 'history-checks.json')['checks']
assert all(row['status'] == 200 and all(row[key] for key in ['bodyEqual', 'versionEqual', 'noDefaultRules', 'rulesEqual', 'noInternalReviewOnWire']) for row in history)

same = []
for case in protocol['generation']:
    same.append(dict(caseId=case, stage='GENERATION', equal=by_id[case + '_V24_GEN']['request'] == previous[case + '_V21_GEN']['request']))
for case in protocol['plan']:
    current_request = json.loads(json.dumps(by_id[case + '_V24_PLAN']['request']))
    previous_request = json.loads(json.dumps(previous[case + '_V21_PLAN']['request']))
    # 正常上下文分析每次分配新ID；仅去掉随机ID，内容版本摘要及所有业务字段仍须相同。
    current_request['planningContext'].pop('contextId')
    previous_request['planningContext'].pop('contextId')
    same.append(dict(caseId=case, stage='PLAN', equal=current_request == previous_request,
        excludedTransportFields=['planningContext.contextId'], contextVersionEqual=current_request['planningContext']['version'] == previous_request['planningContext']['version']))
assert all(row['equal'] for row in same)
write(OUT / 'same-information-checks.json', dict(checks=same, historyPassed=len(history),
    disclosure='直接增强请求逐字相同；Plan仅排除新分配上下文ID，内容摘要及业务字段相同。确认后使用本批真实Plan题号绑定同一答复，因此不宣称题目及题号相同。均为已见回归，不是新的独立留出。'))
write(OUT / 'transports.jsonl', ''.join(json.dumps(trace, ensure_ascii=False) + '\n' for trace in traces))
write(OUT / 'http-results.json', results)
groups = defaultdict(list)
for trace in traces:
    groups[trace['phase']].append(trace)
cost = []
for phase, rows in sorted(groups.items()):
    usage = [row.get('response', {}).get('usage') for row in rows]
    cost.append(dict(phase=phase, attempts=len(rows), inputTokens=sum((row or {}).get('prompt_tokens', 0) for row in usage),
        outputTokens=sum((row or {}).get('completion_tokens', 0) for row in usage),
        totalTokens=sum((row or {}).get('total_tokens', 0) for row in usage), missingUsage=sum(row is None for row in usage),
        medianElapsedMs=statistics.median(row['elapsedMs'] for row in rows)))
retries = sum(count - 1 for count in Counter(trace['jobId'] for trace in traces).values())
write(OUT / 'cost-and-delivery.json', dict(actualCalls=len(traces), phaseCounts=counts, logicalJobs=len(results),
    protocolRetries=retries, qualityRetries=0, statuses=Counter(str(result['status']) for result in results), groups=cost,
    monetaryCost='未冻结账单价格，仅报告实际返回tokens，不推算货币成本。'))
for result in results:
    if result['phase'] == 'EXECUTION' and result['status'] == 200:
        write(OUT / 'works' / (result['id'] + '.md'), result['body']['data']['content'])

previous_closure = read(PREVIOUS / 'closure.json')
task_actual = protocol['taskPreviouslyActual'] + len(traces)
assert task_actual <= protocol['taskMaximumNewActualCalls']
closure = dict(status='CLOSED', closedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(), actualCalls=len(traces),
    phaseCounts=counts, protocolRetries=retries, qualityRetries=0,
    maximumNewActualCalls=protocol['maximumNewActualCalls'], taskMaximumNewActualCalls=protocol['taskMaximumNewActualCalls'],
    newAuthorizationActual=task_actual, newAuthorizationUnspent=protocol['taskMaximumNewActualCalls'] - task_actual,
    unusedStageReservationReleased=protocol['maximumNewActualCalls'] - len(traces),
    releasePolicy='仅本轮新规则下预留经总账结算释放；本任务已停止调用，后续仍须对应任务及冻结阶段，旧闭批余额不恢复。',
    cumulativeClosedActual=previous_closure['cumulativeClosedActual'] + len(traces),
    cumulativeBaselineActual=previous_closure['cumulativeBaselineActual'],
    cumulativeCandidateActual=previous_closure['cumulativeCandidateActual'] + len(traces),
    oldClosedBalanceTransferred=0, oldUnallocated8Used=0, sourceManifestVerified=True, defaultVersionAfter='4',
    semanticDecision='形式误拒修复已验证，不证明整体语义质量提升；保持默认4，详见评审。',
    migrationsApplied=False, deploymentPerformed=False)
write(OUT / 'closure.json', closure)
write(PRIVATE / 'closure.json', closure)
print(json.dumps(dict(actualCalls=len(traces), taskActual=task_actual, remaining=closure['newAuthorizationUnspent'],
    phaseCounts=counts, logicalJobs=len(results), protocolRetries=retries, sourceManifestVerified=True), ensure_ascii=False))
