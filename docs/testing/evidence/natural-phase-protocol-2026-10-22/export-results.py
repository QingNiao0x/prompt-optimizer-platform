"""封存本批合成原件并创建匿名作品包；不调用模型、不读取认证信息、不覆盖原件。"""
from pathlib import Path
from collections import Counter, defaultdict
import json, hashlib, random, statistics

OUT=Path(__file__).resolve().parent
ROOT=OUT.parents[3]
PRIVATE=ROOT/'tmp/natural-phase-protocol-20261022'
read=lambda path:json.loads(path.read_text(encoding='utf-8-sig'))
def write(path, value):
    path.parent.mkdir(parents=True,exist_ok=True)
    with path.open('x',encoding='utf-8',newline='\n') as f:
        f.write(value if isinstance(value,str) else json.dumps(value,ensure_ascii=False,indent=2)+'\n')

protocol=read(OUT/'protocol.json')
traces=[read(p) for p in sorted((PRIVATE/'traces').glob('*.json'))]
results=[read(p) for p in sorted((PRIVATE/'results').glob('*.json'))]
by_id={r['id']:r for r in results}
assert all(t['jobId'] in protocol['allowedJobs'] for t in traces)
assert len(traces)<=protocol['maximumNewActualCalls']
counts=Counter(t['phase'] for t in traces)
assert all(n<=protocol['phaseLimits'][phase] for phase,n in counts.items())
assert all('response' in t or 'transportFailure' in t for t in traces), 'Call still pending'
# 输出只取传输拦截器已过滤的JSON，不读取账号、Cookie或认证头。
write(OUT/'transports.jsonl',''.join(json.dumps(t,ensure_ascii=False)+'\n' for t in traces))
write(OUT/'http-results.json',results)
same=[]
for case in protocol['generation']:
    base=by_id[case+'_V4_GEN']['request']
    for v in ['21','22','23']:
        same.append(dict(caseId=case,version=v,equal=base==by_id[case+'_V'+v+'_GEN']['request']))
for case in protocol['confirmedPlanGeneration']:
    same.append(dict(caseId=case,version='21',equal=by_id[case+'_V4_PLAN_GEN']['request']==by_id[case+'_V21_PLAN_GEN']['request']))
assert all(x['equal'] for x in same)
history=read(OUT/'history-checks.json')['checks']
assert all(x['status']==200 and all(x[k] for k in ['bodyEqual','versionEqual','noDefaultRules','rulesEqual','noInternalReviewOnWire']) for x in history)
usage=defaultdict(list)
for t in traces:
    result=by_id[t['jobId']]
    usage[t['phase']+'/'+result['version']].append(t)
cost=[]
for key,rows in sorted(usage.items()):
    tokens=[t.get('response',{}).get('usage') for t in rows]
    cost.append(dict(group=key,attempts=len(rows),inputTokens=sum((t or {}).get('prompt_tokens',0) for t in tokens),
        outputTokens=sum((t or {}).get('completion_tokens',0) for t in tokens),
        totalTokens=sum((t or {}).get('total_tokens',0) for t in tokens),
        missingUsage=sum(t is None for t in tokens),medianElapsedMs=statistics.median(t['elapsedMs'] for t in rows)))
write(OUT/'same-information-checks.json',dict(generation=same,historyPassed=len(history),disclosure='各作品请求逐字保留同一files及answerBundle；正文不同是唯一预期差异。'))
write(OUT/'cost-and-delivery.json',dict(actualCalls=len(traces),phaseCounts=counts,
    protocolRetries=sum(n-1 for n in Counter(t['jobId'] for t in traces).values()),qualityRetries=0,
    results=Counter(str(r['status']) for r in results),groups=cost,
    monetaryCost='未冻结账单价格，不推算货币成本；报告实际返回tokens，包括缓存用量原件。'))

cases={c['id']:c for c in read(OUT/'cases.json')['cases']}
packets=[];mapping=[]
for case_id in protocol['execution']:
    case=cases[case_id]
    works=[r for r in results if r['caseId']==case_id and r['phase']=='EXECUTION' and r['status']==200]
    random.Random('phase-protocol-anonymous-'+case_id).shuffle(works)
    packet=dict(caseId=case_id,rawPrompt=case['rawPrompt'],files=case['files'],
        answerBundle=case.get('answerBundle'),acceptance=case['acceptance'],artifacts=[])
    for i,r in enumerate(works):
        label=chr(65+i); content=r['body']['data']['content']
        packet['artifacts'].append(dict(label=label,content=content))
        mapping.append(dict(caseId=case_id,label=label,jobId=r['id'],version=r['version'],
            sha256=hashlib.sha256(content.encode()).hexdigest()))
        write(OUT/'works'/(r['id']+'.md'),content)
    packets.append(packet)
write(PRIVATE/'anonymous-work-packet.json',dict(disclosure='内部AI匿名辅助复核，非人工专家验收；不透露版本或优化提示词。',cases=packets))
write(PRIVATE/'anonymous-mapping.json',mapping)
print(json.dumps(dict(actualCalls=len(traces),logicalResults=len(results),works=sum(len(p['artifacts']) for p in packets),phaseCounts=counts),ensure_ascii=False))
