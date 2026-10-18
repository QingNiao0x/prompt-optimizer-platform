"""Freeze server-owned question responsibility; earlier real failures remain immutable."""
from pathlib import Path
from datetime import datetime, timezone
import hashlib,json,shutil
import xml.etree.ElementTree as ET

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[3]
PARENT=HERE.parent/'me01-candidate41r1-responsibility-projection-2026-10-18'
OUT=HERE.parent/'me01-candidate41r2-responsibility-projection-2026-10-18'
PRIVATE=ROOT/'tmp/me01-candidate41r2-20261018'
sha=lambda data:hashlib.sha256(data).hexdigest()

def save(path,data):
    path.parent.mkdir(parents=True,exist_ok=True)
    with path.open('x',encoding='utf-8',newline='\n') as stream:
        stream.write(data if isinstance(data,str) else json.dumps(data,ensure_ascii=False,indent=2)+'\n')

def main():
    parent_bytes=(PARENT/'candidate41r1-source.json').read_bytes();parent=json.loads(parent_bytes)
    source=PRIVATE/'frozen/source';runtime=PRIVATE/'frozen/classes'
    assert not source.exists() and not runtime.exists()
    paths=sorted({row['path'] for row in parent['sourceFiles']}|{'services/api/src/test/resources/fixtures/candidate41-temperature-replay.json'})
    sr=[];rr=[]
    for name in paths:
        path=ROOT/name;target=source/name;target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(path,target)
        sr.append({'path':name,'sha256':sha(path.read_bytes())})
    classes=ROOT/'services/api/target/classes'
    for path in sorted(classes.rglob('*')):
        if path.is_file():
            name=path.relative_to(classes).as_posix();target=runtime/name;target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(path,target)
            rr.append({'path':name,'sha256':sha(path.read_bytes())})
    before={row['path']:row['sha256'] for row in parent['sourceFiles']}
    changed=[row['path'] for row in sr if before.get(row['path'])!=row['sha256']]
    manifest={**parent,'version':'candidate41r2','parentCandidate':'candidate41r1','parentManifestSha256':sha(parent_bytes),
              'frozenAt':datetime.now(timezone.utc).isoformat(),'sourceSnapshot':source.relative_to(ROOT).as_posix(),
              'runtimeSnapshot':runtime.relative_to(ROOT).as_posix(),'sourceFiles':sr,'runtimeFiles':rr}
    save(OUT/'candidate41r2-source.json',manifest)
    selected=[row['path'].split('TEST-')[-1] for row in json.loads((HERE/'verification.json').read_text())['reports']]
    totals={'classes':len(selected),'tests':0,'failures':0,'errors':0,'skipped':0};reports=[]
    for name in selected:
        path=ROOT/'services/api/target/surefire-reports'/('TEST-'+name);suite=ET.fromstring(path.read_bytes())
        for key in ['tests','failures','errors','skipped']:totals[key]+=int(suite.attrib[key])
        target=PRIVATE/'final-reports'/path.name;target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(path,target)
        reports.append({'path':target.relative_to(ROOT).as_posix(),'sha256':sha(path.read_bytes())})
    assert totals['failures']==totals['errors']==totals['skipped']==0
    save(OUT/'verification.json',{'scope':'Affected Java tests, not full repository or live acceptance','java':totals,'reports':reports,
        'changedSource':changed,'regressionFixtures':[416,418,428],
        'strategy':'Server checks standalone scope and all candidate effects; model responsibility advice no longer controls filtering.',
        'limitations':['Finite code-packaging scope; no universal natural-language equivalence claim.','428 is regression after diagnosis, not held-out evidence.'],
        'realCalls':0})
    for number in range(425,429):
        target=PARENT/'traces'/f'{number}.json';target.parent.mkdir(parents=True,exist_ok=True)
        with target.open('xb') as stream:stream.write((ROOT/f'tmp/me00a-20261012/traces/{number}.json').read_bytes())
    live=ROOT/'tmp/me01-candidate41r1-live-20261018/flow/TS-IMPLICIT'
    for name in ['check.json','native-copy.md','prepared-answers.json']:
        target=PARENT/'chain'/name;target.parent.mkdir(parents=True,exist_ok=True)
        with target.open('xb') as stream:stream.write((live/name).read_bytes())
    print(json.dumps({'candidate':'candidate41r2','sourceFiles':len(sr),'runtimeFiles':len(rr),'java':totals,'changedSource':changed}))

if __name__=='__main__':main()
