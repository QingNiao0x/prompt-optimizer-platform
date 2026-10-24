"""Append the pre-registered D02 diagnostic to the still-DRAFT works batch; no network."""
from pathlib import Path
import json, hashlib

BASE=Path(__file__).resolve().parent
REPO=BASE.parents[3]
OUT=BASE/'works'
PRIVATE=REPO/'tmp/natural-intent-completion-2026-10-24/works'
read=lambda path:json.loads(path.read_text(encoding='utf-8-sig'))
encode=lambda value:json.dumps(value,ensure_ascii=False,indent=2)+'\n'
pointer=lambda path:{'stage':'regression','path':path.relative_to(REPO).as_posix(),'sha256':hashlib.sha256(path.read_bytes()).hexdigest()}
protocol=read(OUT/'protocol-draft.json')
assert protocol['status']=='DRAFT' and not (OUT/'source-manifest.json').exists()
assert len(protocol['execution'])==4 and 'D02' not in protocol['execution']
main=list(protocol['execution'])
diagnostic=read(BASE/'D02-diagnostic-preregistration.json')
assert diagnostic['adoptionEligible'] is False and diagnostic['maxTokens']==8192
assert protocol['parameters']['executionMaxTokens']==8192
source=BASE/'regression'
assert read(source/'closure.json')['status']=='CLOSED'
sample=next(case for case in read(source/'cases.json')['cases'] if case['id']=='D02')
cases=read(OUT/'cases.json');cases['cases'].append(sample)
reuse=read(OUT/'reuse-manifest.json')
entry=next(item for item in reuse['sources'] if item['sourceStage']=='regression')
entry['reusedCaseIds'].append('D02')
exported=read(source/'http-results.json')
for version in ['31','37']:
    identifier='D02_V'+version+'_GEN'
    old=REPO/'tmp/natural-intent-completion-2026-10-24/regression/results'/(identifier+'.json')
    original=read(old);assert original==next(row for row in exported if row['id']==identifier)
    assert original['status']==200
    target=PRIVATE/'results'/(identifier+'.json');assert not target.exists()
    reused=pointer(old);target.write_text(encode({**original,'reusedFrom':reused}),encoding='utf-8')
    record={'id':identifier,**pointer(target),'reusedFrom':reused}
    entry['results'].append(record);entry['files'].extend([reused,{'path':record['path'],'sha256':record['sha256']}])
    record_id=original['body']['data']['recordId']
    old_history=source/'history-checks'/(record_id+'.json')
    target_history=OUT/'history-checks'/(record_id+'.json');assert not target_history.exists()
    history_source=pointer(old_history)
    target_history.write_text(encode({**read(old_history),'reusedFrom':history_source}),encoding='utf-8')
    history={'recordId':record_id,**pointer(target_history),'reusedFrom':history_source}
    entry['historyChecks'].append(history);entry['files'].extend([history_source,{'path':history['path'],'sha256':history['sha256']}])
reuse['files']=[file for item in reuse['sources'] for file in item['files']]
protocol['generation'].append('D02');protocol['generationVersionsByCase']['D02']=[]
protocol['requiredGenerationVersionsByCase']['D02']=['31','37']
protocol['execution'].append('D02')
protocol['adoptionEligibleCaseIds']=main
protocol['diagnosticCaseIds']=['D02']
protocol['plannedPrimaryCalls']['EXECUTION']+=6
protocol['allowedJobs'].extend('D02_'+arm+'_EXEC_R'+str(repeat) for arm in ['ORIGINAL','V31','V37'] for repeat in [1,2])
protocol['criteria'].append('D02仅为预登记方法对象诊断，不能计入主四题的两题收益或替换留出收益要求。')
protocol['draftNotes'].append('主四题同期24作品＋D02同期诊断6作品＋PR-D06缺失31增强2次＝正常32调用；上限48。旧D02实际8192，重新诊断是隔离跨日期/采样差异，不是参数不一致。')
assert sum(protocol['plannedPrimaryCalls'].values())==32
for path,value in [(OUT/'cases.json',cases),(OUT/'reuse-manifest.json',reuse),(OUT/'protocol-draft.json',protocol)]:
    path.write_text(encode(value),encoding='utf-8')
print(json.dumps({'event':'D02_DIAGNOSTIC_APPENDED_BEFORE_WORKS_FREEZE','mainCases':main,'diagnosticCases':['D02'],'plannedCalls':32,'modelCalls':0}))
