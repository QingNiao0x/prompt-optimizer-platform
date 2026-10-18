"""Apply the user's explicit +6 numeric approval after the previous four calls are closed."""
from pathlib import Path
from datetime import datetime,timezone
import json,hashlib

PUB=Path(__file__).resolve().parent
ROOT=PUB.parents[3]
LEDGER=ROOT/'tmp/me00a-20261012'
sha=lambda value:hashlib.sha256(value).hexdigest()

def main():
    target=LEDGER/'protocol.json';old=target.read_bytes();protocol=json.loads(old);previous=protocol['activeBatch']
    assert protocol['maximumActualModelCalls']==438 and protocol['phaseLimits']=={'baseline':80,'candidateValidation':358}
    assert previous['version']=='candidate41r1-live' and previous['status']=='CLOSED' and previous['unusedCalls']==0
    traces=sorted((LEDGER/'traces').glob('*.json'),key=lambda file:int(file.stem))
    assert [int(file.stem) for file in traces]==list(range(1,429))
    assert sum(json.loads(file.read_text(encoding='utf-8-sig')).get('phase')=='candidateValidation' for file in traces)==358
    pre=json.loads((PUB/'scope-preflight.json').read_text());assert pre['checks']==pre['passed']==32 and pre['modelCalls']==pre['budgetReservations']==0
    source_bytes=(PUB/'candidate41r2-source.json').read_bytes();manifest=json.loads(source_bytes)
    for row in manifest['sourceFiles']:assert sha((ROOT/row['path']).read_bytes())==row['sha256'],row['path']
    cases=(PUB/'request-cases.json').read_bytes()
    batch={'version':'candidate41r2-live','sourceCandidate':'candidate41r2','status':'OPEN','authorizationStatus':'APPROVED_BY_USER',
           'authorizationAmendment':37,'authorizationBasis':'用户明确批准新增6次和重分配4次；4次已在425—428计入，本批仅使用新增6次。按持续授权自行冻结r2及分配，任务未扩大。',
           'callsBefore':428,'candidateCallsBefore':358,'maximumNewCalls':6,'maximumActualCallsAfterBatch':434,
           'purposeMaximums':{'PLANNING':1,'PLATFORM_GENERATION':1,'EXECUTION':2,'REVIEW':2},
           'qualityRetriesAllowed':False,'secondQualityRoundAllowed':False,'sourceManifestSha256':sha(source_bytes),
           'casesFile':'candidate41r2-request-cases.json','casesSha256':sha(cases),'model':'deepseek-flash','accountCreationAllowed':False,
           'dependencyFailurePolicy':'Only fresh actual bound answers; no quality retry; each platform purpose has one upstream call. Unmet prerequisites stop dependent requests.'}
    amendment={'number':37,'candidate':'candidate41r2','authorization':'批准新增6次并重分配4次，完成冻结复验（建议）',
               'formalBefore':[438,80,358],'formalAfter':[444,80,364],'actualBefore':428,'maximumNewCalls':6,'maximumActualAfter':434,
               'alreadySpentReallocatedCalls':[425,426,427,428],'reallocationCountedAgain':False,'purposeMaximums':batch['purposeMaximums'],
               'allocationReason':'Four prior platform calls exhausted; use newly approved six for latest Plan, enhancement, two same-info works and two independent AI reviews.',
               'protocolBeforeSha256':sha(old),'sourceManifestSha256':sha(source_bytes),'appliedAt':datetime.now(timezone.utc).isoformat()}
    for path,data in [(LEDGER/'protocol-before-amendment-37.json',old),(LEDGER/batch['casesFile'],cases),
                      (PUB/'protocol-amendment-37.json',(json.dumps(amendment,ensure_ascii=False,indent=2)+'\n').encode())]:
        with path.open('xb') as stream:stream.write(data)
    assert target.read_bytes()==old
    protocol['maximumActualModelCalls']=444;protocol['phaseLimits']['candidateValidation']=364;protocol['activeBatch']=batch
    staged=LEDGER/'protocol-candidate41r2-staged.json';staged.write_text(json.dumps(protocol,ensure_ascii=False,indent=2)+'\n',encoding='utf-8');staged.replace(target)
    print(json.dumps({'batch':batch['version'],'formal':[444,80,364],'actualBefore':428,'maximumNewCalls':6}))

if __name__=='__main__':main()
