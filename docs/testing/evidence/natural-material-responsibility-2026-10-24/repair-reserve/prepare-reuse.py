"""Reuse first-origin generation and capacity-corrected works without overwriting closed evidence."""
from pathlib import Path
import hashlib, json

ROOT=Path(__file__).resolve().parents[5]
OUT=Path(__file__).resolve().parent
PRIVATE=ROOT/'tmp/natural-material-responsibility-2026-10-24/repair-reserve'
OLD=ROOT/'docs/testing/evidence/natural-execution-boundary-2026-10-23'
read=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
def write(path,value):
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
def pointer(path):
    return dict(path=path.relative_to(ROOT).as_posix(),sha256=hashlib.sha256(path.read_bytes()).hexdigest())

assert not (OUT/'protocol.json').exists() and not (OUT/'source-manifest.json').exists()
assert read(OLD/'development/closure.json')['status']=='CLOSED'
assert read(OLD/'capacity/closure.json')['status']=='CLOSED'
results=[];history=[];files=[];combined=[]
ids=['D02_V31_GEN']+[f'D02_{arm}_EXEC_R{r}' for arm in ['ORIGINAL','V31'] for r in [1,2]]
for id in ids:
    stage='development' if id.endswith('_GEN') else 'capacity'
    source=ROOT/f'tmp/natural-execution-boundary-2026-10-23/{stage}/results/{id}.json'
    original=read(source)
    public=OLD/stage/'http-results.json'
    assert original==next(r for r in read(public) if r['id']==id)
    assert 'reusedFrom' not in original, 'Resolve the first original, never overwrite its provenance'
    reused=dict(stage=stage,**pointer(source));target=PRIVATE/'results'/f'{id}.json'
    write(target,dict(**original,reusedFrom=reused))
    results.append(dict(id=id,**pointer(target),reusedFrom=reused));combined.append(original)
    files.extend([pointer(source),pointer(target),pointer(public),pointer(OLD/stage/'closure.json')])
    if id.endswith('_GEN'):
        record=original['body']['data']['recordId'];source=OLD/stage/'history-checks'/f'{record}.json'
        reused=dict(stage=stage,**pointer(source));target=OUT/'history-checks'/f'{record}.json'
        write(target,dict(**read(source),reusedFrom=reused))
        history.append(dict(recordId=record,**pointer(target),reusedFrom=reused))
        files.extend([pointer(source),pointer(target)])
write(OUT/'reuse-combined-results.json',combined)
files.extend([pointer(OUT/'reuse-combined-results.json'),pointer(OLD/'capacity/cases.json')])
write(OUT/'reuse-manifest.json',dict(sourceStage='development+capacity',targetStage='repair-reserve',
    reusedCaseIds=['D02'],sourceClosure=pointer(OLD/'capacity/closure.json'),sourceCases=pointer(OLD/'capacity/cases.json'),
    sourcePublicResults=pointer(OUT/'reuse-combined-results.json'),sourceManifest=pointer(OLD/'capacity/source-manifest.json'),
    results=results,historyChecks=history,files=files,reusedLogicalResults=5,copiedTraces=0,newActualCallsFromReuse=0,
    note='增强取development首次原件；8192容量作品取capacity首次原件。合并索引只汇集逐条与旧公开导出相等的原件，不改变正文或来源。'))
print(json.dumps(dict(reused=5,modelCalls=0)))
