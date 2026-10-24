"""Export/close one frozen stage, checking actual network reservations and source hashes."""
from pathlib import Path
from collections import Counter, defaultdict
import argparse, datetime, hashlib, json, statistics, subprocess

parser = argparse.ArgumentParser()
parser.add_argument('stage')
args = parser.parse_args()
assert args.stage and all(c in 'abcdefghijklmnopqrstuvwxyz0123456789-' for c in args.stage)
BASE = Path(__file__).resolve().parent
REPO = BASE.parents[3]
OUT = BASE / args.stage
TASK_ROOT = REPO / 'tmp/natural-preparation-routing-2026-10-24'
PRIVATE = TASK_ROOT / args.stage
read = lambda p: json.loads(p.read_text(encoding='utf-8-sig'))


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('x', encoding='utf-8', newline='\n') as stream:
        stream.write(value if isinstance(value, str) else json.dumps(value, ensure_ascii=False, indent=2)+'\n')


assert not (OUT/'closure.json').exists(), 'Closed stage cannot be exported again'
assert not (BASE/'closure.json').exists(), 'Task already closed'
protocol = read(OUT/'protocol.json')
# 同一离线校验器核对复用来源与复制件，闭批前不把历史结果当作本阶段的新调用。
subprocess.run(['node', str(BASE/'validate-stage.mjs'), args.stage], cwd=REPO, check=True)
traces = [read(p) for p in sorted((PRIVATE/'traces').glob('*.json'))]
results = [read(p) for p in sorted((PRIVATE/'results').glob('*.json'))]
reused_results = [row for row in results if row.get('reusedFrom')]
new_results = [row for row in results if not row.get('reusedFrom')]
reused_ids = {row['id'] for row in reused_results}
by_id = {row['id']: row for row in results}
counts = Counter(t['phase'] for t in traces)
task_actual = len(list(TASK_ROOT.glob('*/traces/*.json')))
assert len(traces) <= protocol['maximumNewActualCalls']
assert task_actual <= protocol['taskMaximumActualCalls']
assert all(n <= protocol['phaseLimits'][p] for p, n in counts.items())
assert all(t['jobId'] in protocol['allowedJobs'] and ('response' in t or 'transportFailure' in t) for t in traces)
assert not any(t['jobId'] in reused_ids for t in traces), 'Copied results cannot acquire new traces'
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
    sample = next(c for c in read(OUT/'cases.json')['cases'] if c['id'] == case)
    for request in requests:
        assert 'executorMaterials' not in request
        encoded = json.dumps(request, ensure_ascii=False)
        for material in sample.get('executorMaterials', []):
            assert material['content'] not in encoded
            assert json.dumps(material['content'], ensure_ascii=False) not in encoded
    same.append(dict(caseId=case, stage='GENERATION', complete=all(rows), executorMaterialsAbsent=True,
                     equal=not requests or all(r == requests[0] for r in requests),
                     independentActualPlans=bound,availableResults=len(requests),
                     newResults=sum(bool(r) and not r.get('reusedFrom') for r in rows),
                     reusedResults=sum(bool(r) and bool(r.get('reusedFrom')) for r in rows),
                     resultOrigins={r['version']:r.get('reusedFrom', {'stage':args.stage,'newResult':True}) for r in rows if r},
                     answerFactIdsByVersion=facts_by_version,
                     note='Plan各臂原始输入一致；真实提问和绑定事实覆盖可能不同，单独报告，不混称全部增强输入相同。' if bound else '直接增强输入逐字一致。'))
for case in protocol['execution']:
    executions = [r for r in results if r['phase'] == 'EXECUTION' and r['caseId'] == case]
    requests = [r['request'] for r in executions]
    sample = next(c for c in read(OUT/'cases.json')['cases'] if c['id'] == case)
    for request in requests:
        text = request['messages'][1]['content']
        material = '\n\n用户同时提供的材料：\n'+json.dumps(sample['files'], ensure_ascii=False, separators=(',',':')) if sample.get('files') else ''
        context = '\n\n用户提供的背景：\n'+sample['contextDescription'] if sample.get('contextDescription') else ''
        answers = '\n\n用户已提供的本次答复（未知项保持未知）：\n'+sample['answerBundle'] if sample.get('answerBundle') else ''
        executor = '\n\n执行本次任务时用户提供的材料（完整正文）：\n'+'\n\n'.join('--- '+m['name']+' ---\n'+m['content'] for m in sample.get('executorMaterials', [])) if sample.get('executorMaterials') else ''
        suffix = material + context + answers + executor
        row = next(r for r in executions if r['request'] is request)
        arm = row['version']
        if arm == 'ORIGINAL':
            prompt = sample['rawPrompt']
        else:
            gen_suffix = '_PLAN_GEN' if case in protocol['confirmedPlanGeneration'] else '_GEN'
            prompt = by_id[case+'_'+arm+gen_suffix]['body']['data']['optimizedPrompt']
        assert text == prompt + suffix, 'Execution material suffix changed across arms'
        for item in sample.get('executorMaterials', []):
            assert item['content'] in text
        for file in sample.get('files', []):
            assert file['content'] in text or json.dumps(file['content'], ensure_ascii=False)[1:-1] in text
        if sample.get('answerBundle'):
            assert sample['answerBundle'] in text
        if sample.get('contextDescription'):
            assert sample['contextDescription'] in text
    expected=(len(protocol['versions'])+1)*protocol['executionRepeats']
    same.append(dict(caseId=case, stage='EXECUTION', equal=True, complete=len(requests)==expected,
                     availableResults=len(requests), newResults=sum(not r.get('reusedFrom') for r in executions),
                     reusedResults=sum(bool(r.get('reusedFrom')) for r in executions), expected=expected, sameMaterial=True, executorMaterialsIdentical=True,
                     resultOrigins={r['id']:r.get('reusedFrom', {'stage':args.stage,'newResult':True}) for r in executions}))
# 传输原件再次核对阶段隔离；这些检查不使用模型自报的成功标签。
for trace in traces:
    if trace['phase'] in ['GENERATION', 'PLAN']:
        sample = next(c for c in read(OUT/'cases.json')['cases'] if c['id'] == trace['caseId'])
        user = trace['request']['messages'][1]['content']
        payload = json.loads(user[user.index('{'):])
        assert 'executorMaterials' not in payload and 'acceptance' not in payload
        for material in sample.get('executorMaterials', []):
            assert material['content'] not in user
            assert json.dumps(material['content'], ensure_ascii=False, separators=(',', ':')) not in user
assert all(c['equal'] for c in same)
write(OUT/'same-information-checks.json', dict(checks=same, historyPassed=len(history),
    newHistoryPassed=sum(not h.get('reusedFrom') for h in history),
    reusedHistoryPassed=sum(bool(h.get('reusedFrom')) for h in history)))
write(OUT/'transports.jsonl', ''.join(json.dumps(t,ensure_ascii=False)+'\n' for t in traces))
write(OUT/'http-results.json', results)
review_maps={p.stem:read(p) for p in sorted((PRIVATE/'review-map').glob('*.json'))}
if review_maps:
    write(OUT/'anonymous-review-map.json',review_maps)
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
    return 'SCOPE_PREPARATION' if any(marker in system for marker in ['当前只识别用户这一轮实际委托的范围', '当前只识别实际委托，不优化提示词、不执行任务。']) else trace['phase']

# 一个逻辑作业的准备和正文是两个正常阶段；只在同一阶段重复尝试才计为协议修复。
retries=sum(n-1 for n in Counter((t['jobId'],purpose(t)) for t in traces).values())
write(OUT/'cost-and-delivery.json',dict(actualCalls=len(traces),phaseCounts=counts,logicalJobs=len(new_results),
    availableLogicalResults=len(results),reusedLogicalResults=len(reused_results),
    reusedByPhase=Counter(r['phase'] for r in reused_results),
    newStatuses=Counter(str(r['status']) for r in new_results),reusedStatuses=Counter(str(r['status']) for r in reused_results),
    protocolRetries=retries,qualityRetries=0,statuses=Counter(str(r['status']) for r in results),groups=cost,
    emptyDeliveries=[r['id'] for r in results if r.get('deliveryFailure') == 'EMPTY_CONTENT'],
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
    newLogicalResults=len(new_results),reusedLogicalResults=len(reused_results),
    reusedByPhase=Counter(r['phase'] for r in reused_results),copiedTraces=0,
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
