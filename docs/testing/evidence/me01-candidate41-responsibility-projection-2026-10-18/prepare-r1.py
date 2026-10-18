"""Freeze the single-provider-guidance correction; retain the initial failed Plan evidence."""
from pathlib import Path
from datetime import datetime, timezone
import hashlib,json,shutil,re
import xml.etree.ElementTree as ET

PUBLIC=Path(__file__).resolve().parent
ROOT=PUBLIC.parents[3]
OUT=PUBLIC.parent/'me01-candidate41r1-responsibility-projection-2026-10-18'
PRIVATE=ROOT/'tmp/me01-candidate41r1-20261018'
sha=lambda data:hashlib.sha256(data).hexdigest()

def save(path,data):
    path.parent.mkdir(parents=True,exist_ok=True)
    with path.open('x',encoding='utf-8',newline='\n') as f:f.write(data if isinstance(data,str) else json.dumps(data,ensure_ascii=False,indent=2)+'\n')

def main():
    parent_bytes=(PUBLIC/'candidate41-source.json').read_bytes(); parent=json.loads(parent_bytes)
    source=PRIVATE/'frozen/source'; runtime=PRIVATE/'frozen/classes'
    assert not source.exists() and not runtime.exists()
    sr=[]; rr=[]
    for row in parent['sourceFiles']:
        path=ROOT/row['path']; target=source/row['path']; target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(path,target)
        sr.append({'path':row['path'],'sha256':sha(path.read_bytes())})
    classes=ROOT/'services/api/target/classes'
    for path in sorted(classes.rglob('*')):
        if path.is_file():
            name=path.relative_to(classes).as_posix();target=runtime/name;target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(path,target)
            rr.append({'path':name,'sha256':sha(path.read_bytes())})
    changed=[row['path'] for row in sr if next(r['sha256'] for r in parent['sourceFiles'] if r['path']==row['path'])!=row['sha256']]
    assert changed==['services/api/src/main/java/com/promptoptimizer/provider/infrastructure/openai/OpenAiCompatiblePromptEnhancementProvider.java']
    manifest={**parent,'version':'candidate41r1','parentCandidate':'candidate41','parentManifestSha256':sha(parent_bytes),
              'frozenAt':datetime.now(timezone.utc).isoformat(),'sourceSnapshot':source.relative_to(ROOT).as_posix(),
              'runtimeSnapshot':runtime.relative_to(ROOT).as_posix(),'sourceFiles':sr,'runtimeFiles':rr}
    save(OUT/'candidate41r1-source.json',manifest)
    names=['QuestionResponsibilityProviderTest','TaskResponsibilityProjectionTest','OpenAiCompatiblePromptEnhancementProviderTest',
           'OptimizationPlanningServiceTest','PlanQuestionFilterTest','PlanRecommendationAlignerTest']
    totals=dict(classes=6,tests=0,failures=0,errors=0,skipped=0)
    for name in names:
        reports=list((ROOT/'services/api/target/surefire-reports').glob('TEST-*.'+name+'.xml'));assert len(reports)==1
        report=reports[0];suite=ET.fromstring(report.read_bytes())
        for key in ['tests','failures','errors','skipped']:totals[key]+=int(suite.attrib[key])
        target=PRIVATE/'final-reports'/report.name;target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(report,target)
    assert totals['tests']==170 and totals['failures']==totals['errors']==totals['skipped']==0
    save(OUT/'verification.json',{'java':totals,'parent1880NotRecounted':True,'changedSource':changed,
        'realCalls':0,'reason':'Initial Plan advice absent and implementation-choice wording overbroad; align responsibility rule and JSON example. No new phrase filters.'})
    for number in [423,424]:
        target=PUBLIC/'traces'/f'{number}.json';target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(ROOT/f'tmp/me00a-20261012/traces/{number}.json',target)
    for name in ['request-cases.json','Candidate41EvaluationServer.java','Candidate41ScopePreflight.java','ReserveEvaluationCall.java',
                 'start-candidate41.ps1','start-live-services.ps1','account-browser.mjs','run-flow.mjs','external-calls.mjs','check-works.py']:
        value=(PUBLIC/name).read_text(encoding='utf-8')
        value=value.replace('candidate41','candidate41r1')
        for before,after in [(438,444),(439,445),(358,364),(359,365),(422,424),(352,354),(428,434)]:
            value=re.sub(r'\b'+str(before)+r'\b',str(after),value)
        if name.endswith('EvaluationServer.java'):
            value=value.replace('maximumNewCalls").asInt() != 6','maximumNewCalls").asInt() != 10')
            value=value.replace('"PLATFORM_GENERATION").asInt() != 1','"PLATFORM_GENERATION").asInt() != 3')
            value=value.replace('"REVIEW").asInt() != 0','"REVIEW").asInt() != 2')
        if name.endswith('ScopePreflight.java'):
            value=value.replace('put("maximumNewCalls", 6)','put("maximumNewCalls", 10)').replace('put("maximumNewCalls", 7)','put("maximumNewCalls", 11)')
            value=value.replace('put("PLATFORM_GENERATION", 1)','put("PLATFORM_GENERATION", 3)').replace('put("REVIEW", 0)','put("REVIEW", 2)')
        if name=='run-flow.mjs':
            value=value.replace('maximumNewCalls !== 6','maximumNewCalls !== 10')
            value=value.replace('{ PLANNING: 3, PLATFORM_GENERATION: 1, EXECUTION: 2, REVIEW: 0 }','{ PLANNING: 3, PLATFORM_GENERATION: 3, EXECUTION: 2, REVIEW: 2 }')
        if name=='external-calls.mjs':
            value=value.replace('maximumNewCalls !== 6','maximumNewCalls !== 10').replace('purposeMaximums?.REVIEW !== 0','purposeMaximums?.REVIEW !== 2')
        save(OUT/name.replace('candidate41','candidate41r1'),value)
    save(OUT/'live-proposal.json',{'candidate':'candidate41r1','actualBefore':424,'formalBefore':[438,80,358],
        'formalAfterProposed':[444,80,364],'closedCallsToReallocate':4,'newNumericCallsRequested':6,'maximumNewCalls':10,
        'purposeMaximums':{'PLANNING':3,'PLATFORM_GENERATION':3,'EXECUTION':2,'REVIEW':2},
        'sameAccount':True,'qualityRetries':False,'maximumActualAfter':434,'inputTokenCapsForReview':{'plan':4500,'enhancement':8500},
        'sourceManifestSha256':sha((OUT/'candidate41r1-source.json').read_bytes()),
        'completion':'Check ordinary vs important choices and source-backed native copy; hard work checks independent of AI review.'})
    print(json.dumps({'candidate':'candidate41r1','sourceFiles':len(sr),'runtimeFiles':len(rr),'java':totals,'realCalls':0}))

if __name__=='__main__':main()
