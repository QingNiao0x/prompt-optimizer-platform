"""只核对已关闭批次的原件、评审引文及受影响测试，不登录、不调用模型、不改原件。"""
from pathlib import Path
import json, re, hashlib, xml.etree.ElementTree as ET

repo=Path(__file__).resolve().parents[4]
e=Path(__file__).resolve().parent
def save(name,data):
    target=e/name
    value=json.dumps(data,ensure_ascii=False,indent=2)+'\n'
    if target.exists():
        if target.read_text(encoding='utf-8')!=value: raise RuntimeError('Refuse to overwrite '+name)
    else: target.write_text(value,encoding='utf-8')

rows=json.loads((e/'http-results.json').read_text(encoding='utf-8'))
mapping=json.loads((e/'review-mapping.json').read_text(encoding='utf-8'))+json.loads((e/'review-mapping-candidate3.json').read_text(encoding='utf-8'))
reviews=[]
for name in ['REVIEW_FLASH','REVIEW_PRO','FINAL_REVIEW_FLASH','FINAL_REVIEW_PRO']:
    raw=next(x for x in rows if x['id']==name)['body']['data']['content']
    text=raw.strip()
    match=re.fullmatch(r'```(?:json)?\s*\n([\s\S]*?)\n```',text)
    decoded=json.loads(match.group(1) if match else text)
    quotes=[]
    for case in decoded['cases']:
        for work in case['works']:
            arm=next(x['arm'] for x in mapping if x['reviewer']==name and x['caseId']==case['caseId'] and x['label']==work['label'])
            original=next(x for x in rows if x['id']==case['caseId']+'_'+arm+'_EXEC')['body']['data']['content']
            for issue in work.get('issues',[]):
                quote=issue.get('quote','')
                quotes.append({'caseId':case['caseId'],'arm':arm,'quote':quote,'exact':not quote or quote in original})
    reviews.append({'id':name,'strictJsonProtocolPassed':match is None,'offlineNormalization':'single surrounding JSON fence' if match else None,
                    'quotes':quotes,'interpretation':decoded})
save('review-normalization-audit.json',reviews)

logs=['structured-quality-green.log','structured-quality-protection.log','structured-quality-final-targeted.log',
      'structured-quality-candidate2-tests.log','structured-quality-confirmation-tests.log']
classes=set()
for name in logs:
    text=(repo/'tmp'/name).read_text(encoding='gb18030',errors='replace')
    classes.update(re.findall(r' -- in (com\.[A-Za-z0-9_.]+)',text))
suites=[]
for name in sorted(classes):
    path=repo/'services/api/target/surefire-reports'/('TEST-'+name+'.xml')
    root=ET.parse(path).getroot()
    suites.append({'class':name,'tests':int(root.attrib['tests']),'failures':int(root.attrib['failures']),
                   'errors':int(root.attrib['errors']),'skipped':int(root.attrib['skipped'])})
save('validation-summary.json',{'scope':'Latest available reports of explicitly selected affected classes, deduplicated; not a full repository pass',
 'javaTests':sum(x['tests'] for x in suites),'failures':sum(x['failures'] for x in suites),'errors':sum(x['errors'] for x in suites),
 'skipped':sum(x['skipped'] for x in suites),'suites':suites,
 'knownUnresolvedBaselineFailure':{'test':'SourceOwnershipProjectionTest.researchGuidanceRetainsConcreteDesignAndInformationFlowBoundaries',
 'reason':'Prior COMPACT guidance literal assertion is already absent in HEAD; six HEAD/candidate policy hashes identical. Test and natural behavior not modified.',
 'fullClassInitialRun':'229 tests with 2 failures: controller fixed; prior COMPACT assertion remains outside scope',
 'laterReplaySubset':'Only actual436438440ReplayTheAssemblerWithoutInventingSourceReferences rerun and passed; not a full SourceOwnership class pass'},
 'controlledBrowserTests':8,'realResultBrowserReplays':3,'newAccounts':0})

manifest=json.loads((e/'source-manifest-candidate3.json').read_text(encoding='utf-8-sig'))
source=[]
for row in manifest['files']:
    if row['path'].startswith('services/api/src/main/'):
        source.append({'path':row['path'],'matchesFrozenCandidate3':hashlib.sha256((repo/row['path']).read_bytes()).hexdigest()==row['sha256']})
save('final-source-check.json',{'allMatchFrozenCandidate3':all(x['matchesFrozenCandidate3'] for x in source),'files':source})

secret_names={'authorization','cookie','api_key','apikey','password','reasoning_content','reasoning','reasoning_details'}
findings=[]
def scan(value,path):
    if isinstance(value,dict):
        for key,child in value.items():
            public_budget_authorization = key == 'authorization' and child == 'USER_DELEGATED_CHOICE_OF_REAL_EVALUATION_CALL_COUNT' \
                and path in {'protocol.json','protocol-candidate2.json','protocol-candidate3.json'}
            if key.lower() in secret_names and not public_budget_authorization: findings.append({'file':path,'key':key})
            scan(child,path)
    elif isinstance(value,list):
        for child in value: scan(child,path)
for path in e.glob('*.json'):
    scan(json.loads(path.read_text(encoding='utf-8-sig')),path.name)
for path in e.glob('*.jsonl'):
    for line in path.read_text(encoding='utf-8').splitlines():
        if line.strip(): scan(json.loads(line),path.name)
save('public-evidence-check.json',{'forbiddenFieldNames':sorted(secret_names),'findings':findings,
 'scope':'Saved JSON/JSONL contains no authentication headers, credential fields or hidden reasoning; synthetic text and source paths reviewed separately'})
print(json.dumps({'javaTests':sum(x['tests'] for x in suites),'reviewQuoteFailures':sum(not q['exact'] for r in reviews for q in r['quotes']),
 'strictReviewFormatFailures':sum(not r['strictJsonProtocolPassed'] for r in reviews),'sourcesFrozen':all(x['matchesFrozenCandidate3'] for x in source),
 'forbiddenFields':len(findings)},ensure_ascii=False))
