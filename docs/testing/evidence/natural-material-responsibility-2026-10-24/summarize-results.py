"""Record actual counts and source-based findings, without calling or regrading any model."""
from pathlib import Path
from collections import Counter
import hashlib, json, datetime

BASE=Path(__file__).resolve().parent
REPO=BASE.parents[3]
PRIVATE=REPO/'tmp/natural-material-responsibility-2026-10-24'
read=lambda path:json.loads(path.read_text(encoding='utf-8-sig'))
def write(path,value):
    with path.open('x',encoding='utf-8') as out:
        out.write(json.dumps(value,ensure_ascii=False,indent=2)+'\n')

stages=['development','heldout','repair-reserve']
closures=[read(BASE/stage/'closure.json') for stage in stages]
assert all(c['status']=='CLOSED' for c in closures)
actual=sum(c['actualCalls'] for c in closures)
assert actual==39 and actual<=48
manifest_checks=[]
for stage in stages:
    files=read(BASE/stage/'source-manifest.json')['files']
    assert all(hashlib.sha256((REPO/f['path']).read_bytes()).hexdigest()==f['sha256'] for f in files)
    manifest_checks.append(dict(stage=stage,files=len(files),passed=True))

sample=read(BASE/'repair-reserve/cases.json')['cases'][0]
result=read(PRIVATE/'repair-reserve/results/D02_V35_GEN.json')
traces=[(path,read(path)) for path in (PRIVATE/'repair-reserve/traces').glob('*.json')]
source,trace=next((path,t) for path,t in traces if t['phase']=='GENERATION')
preparation=json.loads(trace['response']['choices'][0]['message']['content'])
assert preparation['taskScope']['response']['basis']=='UNSPECIFIED'
assert result['body']['data']['optimizedPrompt']==sample['rawPrompt']
write(BASE/'preparation-failure.json',dict(caseId='D02',rawPrompt=sample['rawPrompt'],
    preparation=preparation,tracePath=source.relative_to(REPO).as_posix(),
    outputEqualsOriginal=True,bodyGenerationCalls=0,
    finding='原文已要求撰写分析，准备却给UNSPECIFIED；仍列6项用户决定。正文无缺资料断言来自原文保留分支，不能计为责任机制修复或实质增强收益。',
    actualPlanOnThisCaseTested=False))

lengths=[]
for path in (BASE/'heldout/works').glob('MR-H01*.md'):
    text=path.read_text(encoding='utf-8').strip()
    length=len(''.join(text.split()))
    assert length<=300
    lengths.append(dict(jobId=path.stem,nonWhitespaceCharacters=length,withinLimit=True))
write(BASE/'semantic-review.json',dict(
    assessor='本次实现者逐源内部复核，不是外部专业盲评；未调用模型评审。',
    scope='本次材料责任、原要求、Plan路径、同信息实际作品；不是全部自然增强能力或医疗专业验证。',
    development=dict(sufficientPromptPreserved=True,explicitNoVideoPreserved=True,
        delegatedPlanQuestions=0,reservedLocationQuestions=1,confirmedLocationRetained=True,
        codeContractChecks='30/30；三臂各两份作品功能持平',
        issues=[dict(jobId='D01_V35_GEN',finding='仍集中列举多种口径；未证明已改善用户批评的正文重心。'),
                dict(jobId='D03_V35_EXEC_R2',finding='重复旧代码并增加契约外异常参数说明，原契约行为通过，但未带来可核查收益。')]),
    heldout=dict(cases=2,works=12,materialFactsAndCoreDeliveryPreserved=True,
        substantiveWorkBenefitDemonstrated=False,handoverLengthChecks=lengths,
        issues=[dict(jobId='MR-H02_V35_EXEC_R1',quote='（通知落款及日期待补充）',
                     finding='新增不必要的局部待补项；不是整篇空模板，但至少需要删除一处。',severity='MINOR'),
                dict(jobId='MR-H02_V35_EXEC_R2',quote='[学校名称]\n[日期]',
                     finding='新增局部落款占位；原始及31作品未添加这种待填区域。',severity='MINOR')]),
    research=dict(bodyEqualsOriginal=True,enhancementBenefitNotDemonstrated=True,
        downstreamIssues=[
            dict(jobId='D02_V35_EXEC_R1',quote='其他死因贡献在各人群、各年龄组均高于心脑血管贡献',
                 finding='资料中男性65岁及以上两项贡献均为0.55；应为不低于或单列相等组。',severity='MINOR'),
            dict(jobId='D02_V35_EXEC_R2',quote='分年龄别死亡率完整序列',
                 finding='开头将其列为未提供，但四维比较表包含2015至2025全部年龄组序列。',severity='MAJOR'),
            dict(jobId='D02_V35_EXEC_R2',quote='标准化率每年减少约7.05/10万',
                 finding='2015至2025有10个年度间隔，77.5/10=7.75；误除以11。',severity='MAJOR')],
        attribution='该题35正文逐字等于原始提示词，不能把新增作品的好坏归功于新增增强正文；具体数值/缺失断言首次出现在下游作品，因果占比未隔离。',
        reviewNotExhaustive=True),
    decision='NOT_ADOPTED',defaultVersion='31'))

held=read(BASE/'heldout/cost-and-delivery.json')
gen={g['version']:g for g in held['groups'] if g['phase']=='GENERATION'}
phases=Counter()
for closure in closures: phases.update(closure['phaseCounts'])
decision=dict(status='NOT_ADOPTED',engineering='候选实现和受影响验证完成',
    productGoal='材料责任误判仍存在，尚未完成可靠修复；未证明整体提示词质量提升',
    defaultVersion='31',candidateVersion='35',tests=210,testFailures=0,
    actualCalls=actual,authorizedMaximum=48,phaseCounts=phases,protocolRetries=0,qualityRetries=0,
    heldoutGenerationCost=dict(v31=gen['31']['totalTokens'],v35=gen['35']['totalTokens'],
        increasePercent=round((gen['35']['totalTokens']/gen['31']['totalTokens']-1)*100,1)),
    rationale=['未见两题作品核心交付持平，候选有局部无用占位。',
               '科研原题错误进入UNSPECIFIED保留原文分支，责任准备仍将执行资料扩成用户决定。',
               '同题增强总token增加40.5%，没有对应质量收益，不切默认。'],
    sourceManifestChecks=manifest_checks,productionDeployment=False,dailyBackendRestarted=False,
    futureDirection='保留原范围识别，不再以任务所需资料清单建立用户决定；先界定增强可见输入和执行现场，用户决定由原意与重要影响共同支持。不得继续扩大责任枚举、关键词或逐题禁令。')
write(BASE/'decision.json',decision)
closure=dict(status='CLOSED',closedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),
    actualCalls=actual,maximumNewActualCalls=48,unusedReservationReleased=48-actual,
    previousClosedActual=1789,cumulativeActual=1789+actual,oldClosedBalanceTransferred=0,
    phaseCounts=phases,protocolRetries=0,qualityRetries=0,candidateAdopted=False,
    defaultVersion='31',stages=[dict(stage=s,actualCalls=c['actualCalls']) for s,c in zip(stages,closures)],
    reason='目标收益未通过，执行入口关闭；未用额度不用于重试到成功。',
    semanticGoalComplete=False,productionDeployment=False)
write(BASE/'closure.json',closure)
write(PRIVATE/'closure.json',closure)
print(json.dumps(dict(actualCalls=actual,unused=48-actual,cumulativeActual=1789+actual,decision='NOT_ADOPTED')))
