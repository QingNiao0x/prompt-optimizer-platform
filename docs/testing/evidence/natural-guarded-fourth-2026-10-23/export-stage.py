"""Export/close one frozen stage, checking actual network reservations and source hashes."""
from pathlib import Path
from collections import Counter, defaultdict
import argparse, datetime, hashlib, json, statistics

parser = argparse.ArgumentParser()
parser.add_argument('stage')
args = parser.parse_args()
assert args.stage and all(c in 'abcdefghijklmnopqrstuvwxyz0123456789-' for c in args.stage)
BASE = Path(__file__).resolve().parent
REPO = BASE.parents[3]
OUT = BASE / args.stage
TASK_ROOT = REPO / 'tmp/natural-guarded-fourth-2026-10-23'
PRIVATE = TASK_ROOT / args.stage
read = lambda p: json.loads(p.read_text(encoding='utf-8-sig'))


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('x', encoding='utf-8', newline='\n') as stream:
        stream.write(value if isinstance(value, str) else json.dumps(value, ensure_ascii=False, indent=2)+'\n')


protocol = read(OUT/'protocol.json')
traces = [read(p) for p in sorted((PRIVATE/'traces').glob('*.json'))]
results = [read(p) for p in sorted((PRIVATE/'results').glob('*.json'))]
by_id = {row['id']: row for row in results}
counts = Counter(t['phase'] for t in traces)
task_actual = len(list(TASK_ROOT.glob('*/traces/*.json')))
assert len(traces) <= protocol['maximumNewActualCalls']
assert task_actual <= protocol['taskMaximumActualCalls']
assert all(n <= protocol['phaseLimits'][p] for p, n in counts.items())
assert all(t['jobId'] in protocol['allowedJobs'] and ('response' in t or 'transportFailure' in t) for t in traces)
manifest = read(OUT/'source-manifest.json')
assert all(hashlib.sha256((REPO/f['path']).read_bytes()).hexdigest() == f['sha256'] for f in manifest['files'])
history = [read(p) for p in sorted((OUT/'history-checks').glob('*.json'))]
assert all(h['status'] == 200 and all(h[k] for k in ['bodyEqual','versionEqual','noDefaultRules','rulesEqual','noInternalReviewOnWire']) for h in history)
same=[]
for case in protocol['generation'] + protocol['confirmedPlanGeneration']:
    suffix = '_PLAN_GEN' if case in protocol['confirmedPlanGeneration'] else '_GEN'
    rows = [by_id.get(case+'_V'+v+suffix) for v in protocol['versions']]
    requests = [r['request'] for r in rows if r]
    bound = case in protocol['confirmedPlanGeneration'] and protocol.get('confirmationMode') == 'PER_VERSION'
    facts_by_version = {}
    if bound:
        sample = next(c for c in read(OUT/'cases.json')['cases'] if c['id'] == case)
        for row in rows:
            if row is None:
                continue
            request = row['request']
            confirmation = request['planConfirmation']
            path = read(OUT/'plan-paths'/(case+'_V'+row['version']+'.json'))
            facts_by_version[row['version']] = sorted({fid for b in path['factBindings'] for fid in b['factIds']})
            for answer in confirmation['answers']:
                binding = next(b for b in path['factBindings'] if b['questionId'] == answer['questionId'])
                expected = '\n'.join(next(f['answer'] for f in sample['answerFacts'] if f['id'] == fid) for fid in binding['factIds'])
                assert answer['answer'] == expected
            if not confirmation['answers']:
                assert sample['planExpectation'] == 'ZERO_QUESTIONS'
        requests = [{k:v for k,v in r.items() if k != 'planConfirmation'} for r in requests]
    same.append(dict(caseId=case, stage='GENERATION', complete=all(rows),
                     equal=not requests or all(r == requests[0] for r in requests),
                     independentActualPlans=bound,actual=len(requests),answerFactIdsByVersion=facts_by_version,
                     note='Plan各臂原始输入一致；真实提问和绑定事实覆盖可能不同，单独报告，不混称全部增强输入相同。' if bound else '直接增强输入逐字一致。'))
for case in protocol['execution']:
    requests = [r['request'] for r in results if r['phase'] == 'EXECUTION' and r['caseId'] == case]
    sample = next(c for c in read(OUT/'cases.json')['cases'] if c['id'] == case)
    for request in requests:
        text = request['messages'][1]['content']
        for file in sample.get('files', []):
            assert file['content'] in text or json.dumps(file['content'], ensure_ascii=False)[1:-1] in text
        if sample.get('answerBundle'):
            assert sample['answerBundle'] in text
        if sample.get('contextDescription'):
            assert sample['contextDescription'] in text
    same.append(dict(caseId=case, stage='EXECUTION', equal=True, actual=len(requests),
                     expected=(len(protocol['versions'])+(0 if protocol.get('executionIncludeOriginal') is False else 1))*protocol['executionRepeats'], sameMaterial=True))
assert all(c['equal'] for c in same)
write(OUT/'same-information-checks.json', dict(checks=same, historyPassed=len(history)))
write(OUT/'transports.jsonl', ''.join(json.dumps(t,ensure_ascii=False)+'\n' for t in traces))
write(OUT/'http-results.json', results)
groups=defaultdict(list)
for trace in traces:
    result=by_id.get(trace['jobId'], {})
    groups[(trace['phase'],result.get('version','unknown'))].append(trace)
cost=[]
for (phase,version),rows in sorted(groups.items()):
    usage=[row.get('response',{}).get('usage') for row in rows]
    times=[row['elapsedMs'] for row in rows if 'elapsedMs' in row]
    cost.append(dict(phase=phase,version=version,attempts=len(rows),
                     inputTokens=sum((u or {}).get('prompt_tokens',0) for u in usage),
                     outputTokens=sum((u or {}).get('completion_tokens',0) for u in usage),
                     totalTokens=sum((u or {}).get('total_tokens',0) for u in usage),
                     missingUsage=sum(u is None for u in usage),medianElapsedMs=statistics.median(times) if times else None))
def purpose(trace):
    system = trace['request']['messages'][0]['content']
    return 'SCOPE_PREPARATION' if '当前只识别用户这一轮实际委托的范围' in system else trace['phase']

# 一个逻辑作业的准备和正文是两个正常阶段；只在同一阶段重复尝试才计为协议修复。
retries=sum(n-1 for n in Counter((t['jobId'],purpose(t)) for t in traces).values())
write(OUT/'cost-and-delivery.json',dict(actualCalls=len(traces),phaseCounts=counts,logicalJobs=len(results),
    protocolRetries=retries,qualityRetries=0,statuses=Counter(str(r['status']) for r in results),groups=cost,
    monetaryCost='仅报告实际tokens和耗时，未冻结账单价格，不推算金额。'))
for result in results:
    if result['status'] != 200:
        continue
    if result['phase'] == 'EXECUTION':
        write(OUT/'works'/(result['id']+'.md'), result['body']['data']['content'])
    elif result['phase'] == 'GENERATION':
        write(OUT/'prompts'/(result['id']+'.md'), result['body']['data']['optimizedPrompt'])
write(OUT/'.gitattributes','*.md -text whitespace=cr-at-eol,-blank-at-eol\n')
closure=dict(status='CLOSED',closedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),actualCalls=len(traces),
    phaseCounts=counts,protocolRetries=retries,qualityRetries=0,maximumNewActualCalls=protocol['maximumNewActualCalls'],
    taskMaximumActualCalls=protocol['taskMaximumActualCalls'],taskActual=task_actual,
    taskRemaining=protocol['taskMaximumActualCalls']-task_actual,
    unusedStageReservationReleased=protocol['maximumNewActualCalls']-len(traces),
    cumulativeActual=read(BASE/'task-protocol.json')['previousClosedActual']+task_actual,
    oldClosedBalanceTransferred=0,sourceManifestVerified=True,
    semanticDecision='另行按冻结质量判据评审；闭批和HTTP成功不是质量通过。',migrationsApplied=False,deploymentPerformed=False)
write(OUT/'closure.json',closure)
write(PRIVATE/'closure.json',closure)
print(json.dumps(dict(actualCalls=len(traces),taskActual=task_actual,remaining=closure['taskRemaining'],phases=counts,protocolRetries=retries)))
