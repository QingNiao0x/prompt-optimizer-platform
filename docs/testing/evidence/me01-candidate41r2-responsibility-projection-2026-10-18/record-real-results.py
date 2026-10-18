"""Archive synthetic originals, attribute each layer, audit quotes and close the exact six-call batch."""
from pathlib import Path
from datetime import datetime,timezone
import json,hashlib

PUB=Path(__file__).resolve().parent
ROOT=PUB.parents[3]
LIVE=ROOT/'tmp/me01-candidate41r2-live-20261018'
LEDGER=ROOT/'tmp/me00a-20261012'
sha=lambda data:hashlib.sha256(data).hexdigest()

def save(name,value):
    target=PUB/name;target.parent.mkdir(parents=True,exist_ok=True)
    with target.open('x',encoding='utf-8') as stream:json.dump(value,stream,ensure_ascii=False,indent=2);stream.write('\n')

def copy(path,name):
    target=PUB/name;target.parent.mkdir(parents=True,exist_ok=True)
    with target.open('xb') as stream:stream.write(path.read_bytes())

def main():
    mapping={'A':'PLAN','B':'RAW'};texts={arm:(LIVE/f'works/TS/{arm}/work.md').read_text(encoding='utf-8') for arm in ['RAW','PLAN']}
    audits={}
    for reviewer in ['R1','R2']:
        data=json.loads((LIVE/f'reviews/{reviewer}/review.md').read_text(encoding='utf-8'));assert {w['id'] for w in data['works']}==set(mapping)
        invalid=[];total=0
        for work in data['works']:
            for dimension in ['accuracy','completeness','clarity','executability']:
                score=work['scores'][dimension];assert isinstance(score,int) and 1<=score<=5
                for quote in work['evidence'][dimension]['quotes']:
                    total+=1
                    if quote not in texts[mapping[work['id']]]:invalid.append({'work':work['id'],'dimension':dimension,'quote':quote})
        audits[reviewer]={'preferredOriginal':data['preferred'],'preferredArm':mapping.get(data['preferred'],data['preferred']),
            'quoteOccurrences':total,'nonVerbatimOccurrences':len(invalid),'nonVerbatimQuotes':invalid,
            'scoresOriginal':{w['id']:w['scores'] for w in data['works']},'notReleaseApproval':True}
        save(f'review-{reviewer.lower()}-original.json',data)
    save('review-audit.json',{'sameSupplierIndependentAiReviews':True,'humanExpertReview':False,'mappingForPostReviewAudit':mapping,
        'reviews':audits,'preferenceDisagreement':audits['R1']['preferredOriginal']!=audits['R2']['preferredOriginal'],
        'adjudication':['R2 prefers PLAN despite a known failed authored test and unsupported execution claims; its severeFailures omission cannot release the work.',
            'Raw was not required to supply authored tests. Extra configuration, tests or lone-CR handling do not by themselves prove higher common-task quality.',
            'Lone CR was not a frozen common hard test. Preserve the competing interpretations; do not add a post-hoc failure to the common 12-case score.',
            'The packet supplied failed counts and full works, but not the exact failing-test output. Reviewers did not independently execute the code. This evidence gap is retained, not repaired by a new review call.'],
        'meanScoreOrWinRateUsedForRelease':False,'overallQualityGate':'FAIL_AUTHORED_TEST_AND_UNSUPPORTED_CLAIMS'})
    rows=[]
    for number in range(429,435):
        source=LEDGER/f'traces/{number}.json';record=json.loads(source.read_text(encoding='utf-8'))
        assert record['version']=='candidate41r2-live' and record['httpStatus']==200
        copy(source,f'traces/{number}.json')
        rows.append({'trace':number,'purpose':record['purpose'],'model':record['request']['model'],'status':record['httpStatus'],
            'elapsedMs':record['elapsedMs'],'usage':record['response']['usage'],'recordSha256':sha(source.read_bytes())})
    check=json.loads((LIVE/'flow/TS-IMPLICIT/check.json').read_text(encoding='utf-8'))
    assert check['success'] and check['nativeCopyMatches'] and check['storeMatches'] and check['historyMatches'] and check['historyTotalDelta']==1
    response=json.loads((LEDGER/'traces/430.json').read_text(encoding='utf-8'))['response']['choices'][0]['message']['content']
    generated=json.loads(response);copy_text=(LIVE/'flow/TS-IMPLICIT/native-copy.md').read_text(encoding='utf-8')
    assert sum(section['content'].count('{{REQ_') for section in generated['sections'])==5
    statements=[a['answer'] for a in check['actualAnswers']]
    task_rules=['校验所有外部输入，并明确处理空值、非法值和边界条件。','处理可预期异常，返回清晰且不含敏感值的错误信息，不吞掉或伪造错误。','为核心逻辑补充正常、异常和边界场景测试。']
    count_rows=[{'statement':text,'completeOccurrenceCount':copy_text.count(text)} for text in statements+task_rules]
    assert all(row['completeOccurrenceCount']==1 for row in count_rows) and '{{REQ_' not in copy_text
    save('assembly-origin-review.json',{'sourceTrace':430,'modelOriginalPreserved':True,'sourceOccurrenceCounts':count_rows,
        'sourceProjectionGate':'PASS_FOR_THE_BOUND_SOURCES','allProvenanceKept':True,'internalReferencesInCopy':0,
        'remainingModelRepetition':['BACKGROUND repeats the line-dedup goal and string input/output.','TASK restates delegated packaging and no runtime dependency after the source reference.','TASK independently restates tests; CONSTRAINTS contains the requested source reference.','ACCEPTANCE repeats operation rules and asks actual execution.'],
        'attribution':'The duplicate paraphrases already exist in the enhancement response. Five source references are expanded once; no extra confirmed statement is appended by PlanDecisionIntegrator.',
        'notClaimed':'No universal semantic deduplication or all-model compactness pass. Legacy no-reference responses still use conservative fallback.'})
    for name in ['check.json','plan-observation.json','prepared-answers.json','native-copy.md']:
        copy(LIVE/'flow/TS-IMPLICIT'/name,'chain/'+name)
    for name in ['hard-checks.json','execution-claims.json','work-artifact-bindings.json']:
        copy(LIVE/name,name)
    for arm in texts:
        copy(LIVE/f'works/TS/{arm}/work.md',f'works/{arm.lower()}.md')
    copy(LIVE/'works/TS/PLAN/independent-artifact-check/authored-tests.log','works/plan-authored-tests.txt')
    for name in ['check.json','compile.log','author-test-command.log']:
        copy(LIVE/'works/TS/PLAN/author-original-profile'/name,'works/author-profile/'+name.replace('.log','.txt'))
    save('real-chain.json',{'candidate':'candidate41r2','sourceCommit':'adaa60f6','sourceManifestSha256':check['sourceManifestSha256'],
        'sameAccount':True,'newAccountsCreated':0,'actualPlanQuestions':2,'ordinaryPackagingQuestionObserved':False,
        'importantChoicesObserved':['Deduplication unit','Case and whitespace comparison'],
        'modelAlsoAskedNoPackaging':True,'serverFilteringCausality':'Not established by 429 alone; regression 416/428 independently tests filtering.',
        'nativeCopyMatches':True,'storeMatches':True,'historyMatches':True,'newHistoryRecords':1,'copySha256':check['copySha256'],
        'sameInformationExecution':True,'copiedBodyPatched':False,'actualAnswers':check['actualAnswers'],
        'ordinaryQuestionGate':'PASS_FOR_THIS_TASK','sourceProjectionGate':'PASS_FOR_THIS_BOUND_COPY','fullGeneralizationGate':'OPEN',
        'commonFunctionComparison':'TIE_12_OF_12_EACH','enhancedAuthoredTests':'FAIL_20_OF_21','unsupportedExecutionClaims':'FAIL',
        'noNewExtraExampleRequirementInCopy':True,'calls':rows,'totalTokens':sum(row['usage']['total_tokens'] for row in rows),
        'upstreamElapsedMsSum':sum(row['elapsedMs'] for row in rows),'notAnEndToEndDuration':True,'qualityRetries':0,
        'overallQualityGate':'FAIL_NOT_RELEASED','oldCredentialScoringProfessionalGates':'OPEN_NOT_RETESTED'})
    path=LEDGER/'protocol.json';before=path.read_bytes();protocol=json.loads(before);batch=protocol['activeBatch']
    assert batch['version']=='candidate41r2-live' and batch['status']=='OPEN' and len(list((LEDGER/'traces').glob('*.json')))==434
    counts={purpose:sum(row['purpose']==purpose for row in rows) for purpose in batch['purposeMaximums']}
    assert counts==batch['purposeMaximums']=={'PLANNING':1,'PLATFORM_GENERATION':1,'EXECUTION':2,'REVIEW':2}
    batch.update(status='CLOSED',actualNewCalls=6,actualCallsAfterBatch=434,actualPurposeCounts=counts,unusedCalls=0,
        qualityRetriesPerformed=0,closedAt=datetime.now(timezone.utc).isoformat(),phases={'baseline':70,'candidateValidation':364},
        localToolFailuresPreserved=True,closureReason='All six allocated calls used. Platform path bounded pass; authored tests and truthfulness fail; no new calls allowed.')
    with (LEDGER/'protocol-before-candidate41r2-close.json').open('xb') as stream:stream.write(before)
    assert path.read_bytes()==before
    staged=LEDGER/'protocol-candidate41r2-close-staged.json';staged.write_text(json.dumps(protocol,ensure_ascii=False,indent=2)+'\n',encoding='utf-8');staged.replace(path)
    save('batch-closed.json',batch)
    save('batch-close-summary.json',{'actualNewCalls':6,'actualTotal':434,'baseline':70,'candidate':364,'formal':[444,80,364],
        'candidateRemaining':0,'unusedClosed':0,'qualityRetries':0,'failedModelCalls':0,'localToolFailuresPreserved':True,'overallQualityPass':False})
    print(json.dumps({'actualTotal':434,'newCalls':6,'reviewBadQuotes':{k:v['nonVerbatimOccurrences'] for k,v in audits.items()},
        'reviewPreferenceDisagreement':audits['R1']['preferredOriginal']!=audits['R2']['preferredOriginal'],'overallQualityPass':False}))

if __name__=='__main__':main()
