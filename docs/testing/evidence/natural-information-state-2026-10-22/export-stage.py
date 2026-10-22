"""Close a frozen stage and export synthetic evidence; no credentials or model calls."""
from pathlib import Path
from collections import Counter, defaultdict
import datetime, hashlib, json, statistics, sys

ROOT = Path(__file__).resolve().parents[4]
OUT = ROOT / sys.argv[1]
PRIVATE = ROOT / sys.argv[2]
read = lambda p: json.loads(p.read_text(encoding='utf-8-sig'))


def write(p, value):
    p.parent.mkdir(parents=True, exist_ok=True)
    with p.open('x', encoding='utf-8', newline='\n') as f:
        f.write(value if isinstance(value, str) else json.dumps(value, ensure_ascii=False, indent=2)+'\n')


protocol = read(OUT/'protocol.json')
traces = [read(p) for p in sorted((PRIVATE/'traces').glob('*.json'))]
results = [read(p) for p in sorted((PRIVATE/'results').glob('*.json'))]
by_id = {r['id']:r for r in results}
counts = Counter(t['phase'] for t in traces)
assert len(traces) <= protocol['maximumNewActualCalls']
assert protocol['taskPreviouslyActual']+len(traces) <= protocol['taskMaximumActualCalls']
assert all(n <= protocol['phaseLimits'][p] for p,n in counts.items())
assert all(t['jobId'] in protocol['allowedJobs'] and ('response' in t or 'transportFailure' in t) for t in traces)
manifest=read(OUT/'source-manifest.json')
assert all(hashlib.sha256((ROOT/f['path']).read_bytes()).hexdigest()==f['sha256'] for f in manifest['files'])
history=read(OUT/'history-checks.json')['checks']
assert all(h['status']==200 and all(h[k] for k in ['bodyEqual','versionEqual','noDefaultRules','rulesEqual','noInternalReviewOnWire']) for h in history)
same=[]
for case in protocol['generation']+protocol['confirmedPlanGeneration']:
    suffix='_PLAN_GEN' if case in protocol['confirmedPlanGeneration'] else '_GEN'
    requests=[by_id[case+'_V'+v+suffix]['request'] for v in protocol['versions']]
    same.append(dict(caseId=case,stage='GENERATION',equal=all(r==requests[0] for r in requests)))
for case in protocol['execution']:
    requests=[r['request'] for r in results if r['phase']=='EXECUTION' and r['caseId']==case]
    # The user message changes only its prompt; every arm gets the same frozen material and answer bundle.
    sample=next(c for c in read(OUT/'cases.json')['cases'] if c['id']==case)
    for r in requests:
        text=r['messages'][1]['content']
        for file in sample['files']:
            assert file['content'] in text or json.dumps(file['content'],ensure_ascii=False)[1:-1] in text
        if sample.get('answerBundle'): assert sample['answerBundle'] in text
    same.append(dict(caseId=case,stage='EXECUTION',equal=True,actual=len(requests),
        expected=3*protocol['executionRepeats'],sameMaterial=True,sameAnswerBundle=True))
assert all(c['equal'] for c in same)
write(OUT/'same-information-checks.json',dict(checks=same,historyPassed=len(history)))
write(OUT/'transports.jsonl',''.join(json.dumps(t,ensure_ascii=False)+'\n' for t in traces))
write(OUT/'http-results.json',results)
groups=defaultdict(list)
for t in traces:
    r=by_id.get(t['jobId'],{})
    groups[(t['phase'],r.get('version','unknown'))].append(t)
cost=[]
for (phase,version),rows in sorted(groups.items()):
    usage=[r.get('response',{}).get('usage') for r in rows]
    cost.append(dict(phase=phase,version=version,attempts=len(rows),
        inputTokens=sum((u or {}).get('prompt_tokens',0) for u in usage),
        outputTokens=sum((u or {}).get('completion_tokens',0) for u in usage),
        totalTokens=sum((u or {}).get('total_tokens',0) for u in usage),missingUsage=sum(u is None for u in usage),
        medianElapsedMs=statistics.median(r['elapsedMs'] for r in rows)))
retries=sum(n-1 for n in Counter(t['jobId'] for t in traces).values())
write(OUT/'cost-and-delivery.json',dict(actualCalls=len(traces),phaseCounts=counts,logicalJobs=len(results),
    protocolRetries=retries,qualityRetries=0,statuses=Counter(str(r['status']) for r in results),groups=cost,
    monetaryCost='仅报告真实tokens和耗时，未冻结账单价格，不推算金额。'))
for r in results:
    if r['phase']=='EXECUTION' and r['status']==200: write(OUT/'works'/(r['id']+'.md'),r['body']['data']['content'])
write(OUT/'works/.gitattributes','*.md -text whitespace=cr-at-eol,-blank-at-eol\n')
actual=protocol['taskPreviouslyActual']+len(traces)
closure=dict(status='CLOSED',closedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),actualCalls=len(traces),
    phaseCounts=counts,protocolRetries=retries,qualityRetries=0,maximumNewActualCalls=protocol['maximumNewActualCalls'],
    taskMaximumActualCalls=protocol['taskMaximumActualCalls'],taskActual=actual,taskRemaining=160-actual,
    unusedStageReservationReleased=protocol['maximumNewActualCalls']-len(traces),cumulativeClosedActual=1254+actual,
    oldClosedBalanceTransferred=0,sourceManifestVerified=True,defaultVersionAfter='4',
    semanticDecision='另行按冻结质量判据评审；闭批和HTTP成功不是质量通过。',migrationsApplied=False,deploymentPerformed=False)
write(OUT/'closure.json',closure)
write(PRIVATE/'closure.json',closure)
print(json.dumps(dict(actualCalls=len(traces),taskActual=actual,remaining=160-actual,phases=counts,protocolRetries=retries)))
