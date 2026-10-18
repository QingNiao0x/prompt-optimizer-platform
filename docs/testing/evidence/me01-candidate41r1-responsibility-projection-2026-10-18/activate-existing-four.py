"""Use only the explicitly recorded four-call reallocation while extra numeric approval is pending."""
from pathlib import Path
from datetime import datetime,timezone
import json,hashlib

PUB=Path(__file__).resolve().parent
ROOT=PUB.parents[3]
LEDGER=ROOT/'tmp/me00a-20261012'
sha=lambda value:hashlib.sha256(value).hexdigest()

def main():
    target=LEDGER/'protocol.json';old=target.read_bytes();p=json.loads(old);previous=p['activeBatch']
    assert p['maximumActualModelCalls']==438 and p['phaseLimits']=={'baseline':80,'candidateValidation':358}
    assert previous['version']=='candidate41-live' and previous['status']=='CLOSED' and previous['unusedCalls']==4
    traces=sorted((LEDGER/'traces').glob('*.json'),key=lambda file:int(file.stem))
    assert [int(file.stem) for file in traces]==list(range(1,425))
    rows=[json.loads(file.read_text(encoding='utf-8-sig')) for file in traces]
    assert sum(row.get('phase')=='candidateValidation' for row in rows)==354
    pre=json.loads((PUB/'scope-preflight.json').read_text());assert pre['checks']==pre['passed']==34 and pre['modelCalls']==pre['budgetReservations']==0
    mb=(PUB/'candidate41r1-source.json').read_bytes();manifest=json.loads(mb)
    for row in manifest['sourceFiles']:assert sha((ROOT/row['path']).read_bytes())==row['sha256'],row['path']
    cb=(PUB/'request-cases.json').read_bytes()
    basis='按用户既有后续候选、测试和重分配自主决定授权，明确转入四十一初版关闭余4次；新增6次数值尚待答，不扩大正式上限。'
    batch={'version':'candidate41r1-live','sourceCandidate':'candidate41r1','status':'OPEN','authorizationStatus':'APPROVED_BY_USER',
           'authorizationAmendment':35,'authorizationBasis':basis,'callsBefore':424,'candidateCallsBefore':354,'maximumNewCalls':4,
           'maximumActualCallsAfterBatch':428,'purposeMaximums':{'PLANNING':2,'PLATFORM_GENERATION':2,'EXECUTION':0,'REVIEW':0},
           'qualityRetriesAllowed':False,'secondQualityRoundAllowed':False,'sourceManifestSha256':sha(mb),
           'casesFile':'candidate41r1-request-cases.json','casesSha256':sha(cb),'model':'deepseek-flash','accountCreationAllowed':False,
           'dependencyFailurePolicy':'Only actual bound answers; unknown questions stop dependent work. No external works or reviews without numeric expansion.'}
    amendment={'number':35,'candidate':'candidate41r1','authorization':basis,'formalBefore':[438,80,358],'formalAfter':[438,80,358],
               'actualBefore':424,'maximumNewCalls':4,'maximumActualAfter':428,'purposeMaximums':batch['purposeMaximums'],
               'closedAllowanceTransferred':{'from':'candidate41-live','calls':4},'sourceManifestSha256':sha(mb),
               'protocolBeforeSha256':sha(old),'appliedAt':datetime.now(timezone.utc).isoformat()}
    for path,data in [(LEDGER/'protocol-before-amendment-35.json',old),(LEDGER/batch['casesFile'],cb),
                      (PUB/'protocol-amendment-35.json',(json.dumps(amendment,ensure_ascii=False,indent=2)+'\n').encode())]:
        with path.open('xb') as f:f.write(data)
    assert target.read_bytes()==old;p['activeBatch']=batch
    staged=LEDGER/'protocol-candidate41r1-staged.json';staged.write_text(json.dumps(p,ensure_ascii=False,indent=2),encoding='utf-8');staged.replace(target)
    print(json.dumps({'batch':batch['version'],'formal':[438,80,358],'actualBefore':424,'reallocatedCalls':4,'newNumericCalls':0}))

if __name__=='__main__':main()
