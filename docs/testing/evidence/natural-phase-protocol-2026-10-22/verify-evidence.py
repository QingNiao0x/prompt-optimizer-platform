"""复算公开原件、相同信息与匿名映射，不调用模型，不重新评分。"""
from pathlib import Path
from collections import Counter
import json,hashlib

root=Path(__file__).resolve().parent
read=lambda name:json.loads((root/name).read_text(encoding='utf-8-sig'))
protocol=read('protocol.json');closure=read('closure.json');results=read('http-results.json')
traces=[json.loads(line) for line in (root/'transports.jsonl').read_text(encoding='utf-8').splitlines()]
assert len(traces)==closure['actualCalls']<=protocol['maximumNewActualCalls']
assert dict(Counter(t['phase'] for t in traces))==closure['phaseCounts']
assert all(t['jobId'] in protocol['allowedJobs'] for t in traces)
assert sum(n-1 for n in Counter(t['jobId'] for t in traces).values())==closure['protocolRetries']
assert all('response' in t or 'transportFailure' in t for t in traces)
assert all(x['equal'] for x in read('same-information-checks.json')['generation'])
assert len(read('history-checks.json')['checks'])==28
works=[r for r in results if r['phase']=='EXECUTION']
assert len(works)==31 and all(r['status']==200 and r['body']['data']['finishReason']=='stop' for r in works)
for r in works:
 assert (root/'works'/(r['id']+'.md')).read_text(encoding='utf-8')==r['body']['data']['content']
 assert r['request']['messages'][0]['content']=='执行用户请求并交付其要求的作品。无法访问外部工具或文件，不声称已经运行、联网或完成未执行的操作。'
office=[dict(id=r['id'],characters=len(r['body']['data']['content'].strip()),within220=len(r['body']['data']['content'].strip())<=220) for r in works if r['caseId']=='HOLDOUT_OFFICE_20261022']
assert all(r['within220'] for r in office)
for entry in read('anonymous-mapping-after-review.json')['mapping']:
 r=next(r for r in works if r['id']==entry['jobId'])
 assert hashlib.sha256(r['body']['data']['content'].encode()).hexdigest()==entry['sha256']
def protected_key(value):
 if isinstance(value,dict):
  assert not {'authorization','cookie','password','apiKey','privateKey'}.intersection(value)
  for item in value.values():protected_key(item)
 elif isinstance(value,list):
  for item in value:protected_key(item)
for t in traces:protected_key(t)
out=dict(actualCalls=len(traces),logicalJobs=len(results),generationSucceeded=sum(r['phase']=='GENERATION' and r['status']==200 for r in results),
 plans=sum(r['phase']=='PLAN' for r in results),works=len(works),protocolRetries=closure['protocolRetries'],
 qualityRetries=0,allWorksComplete=True,officeCharacters=office,anonymousHashesVerified=True,
 noAuthenticationKeysInCapturedPayload=True,defaultVersion=closure['defaultVersionAfter'],
 note='确定性检查不证明语义等价。原始与各臂使用同一材料和答案；人工复核未进行。')
print(json.dumps(out,ensure_ascii=False,indent=2))
