"""Unblind completed internal reviews and decide against the frozen adoption criteria."""
from pathlib import Path
from collections import defaultdict
import datetime,hashlib,json

root=Path(__file__).resolve().parents[4]
task=Path(__file__).resolve().parent
out=task.parent/'natural-information-state-unseen-2026-10-22'
private=root/'tmp/information-state-unseen-review-20261022'
read=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
mapping=read(private/'mapping.private.json')
aggregates=[]
for name,key in [('review-a.json','assessments'),('review-b.json','reviews')]:
    review=read(private/name)
    grouped=defaultdict(lambda:dict(works=0,severe=0,minor=0,minimumEdits=0))
    for row in review[key]:
        label=row.get('id',row.get('workId'))
        job=mapping[label]
        row['jobIdAfterUnblinding']=job
        if 'PARCEL_HANDOVER' in job:continue
        arm=job.split('_EXEC_R')[0].rsplit('_',1)[1]
        g=grouped[arm];g['works']+=1;g['severe']+=len(row['severeErrors']);g['minor']+=len(row['minorErrors']);g['minimumEdits']+=row['minimumEdits']
    assert len(review[key])==32 and all(g['works']==10 for g in grouped.values())
    with (out/name).open('x',encoding='utf-8') as f:json.dump(review,f,ensure_ascii=False,indent=2)
    aggregates.append(dict(reviewer=name,comparableDirectWorks=grouped))
for name in ['anonymous-works.json','mapping.private.json']:
    target='anonymous-work-mapping.json' if name.startswith('mapping') else name
    with (out/target).open('xb') as f:f.write((private/name).read_bytes())
decision=dict(candidate='26',baseline='4',decision='NOT_ADOPTED',defaultAfter='4',
    reviewBoundary='内部AI版本匿名评审，A参与出题；不声称外部专业独立。比较汇总只取三臂均完成的五道直接题，各10件作品；Plan漏交付另列，不混入不等分母。',
    perCase=[dict(case='物品JSON',result='TIE'),dict(case='场地推荐',result='IMPROVED_BOTH_REPEATS',basis='避免第四版压缩预约截止条件和不必要投影追问'),
        dict(case='二手书回复',result='TIE_VS4',basis='两版均避免原始作品的未发布照片→未拍照片变化'),
        dict(case='阳台照料',result='REGRESSION',basis='一次当前状态进入其他时点，另一次新增时间不足就延后浇水'),
        dict(case='充分句子润色',result='TIE',basis='两版优化提示词相同，六份作品相同'),
        dict(case='Plan确认后',result='NO_DELIVERY_BOTH',basis='两版最终协议失败，四份增强作品缺失')],
    sustainedImprovedCases=1,requiredSustainedImprovedCases=2,
    newSevereErrors=True,completeDelivery=False,aggregates=aggregates,
    disagreements=[dict(issue='今天托盘状态写入周三栏',reviewA='严重',reviewB='轻微',resolution='保留分歧；两者均要求修改，不影响另一个一致认定严重的延后浇水错误'),
        dict(issue='原始代收作品包装文字',reviewA='可用',reviewB='各需一次轻微删除',resolution='不纳入三臂共同完成的直接题汇总，不能据此判断候选胜出')],
    conclusion='候选有局部改进且可比直接作品的最少编辑总量下降，但未达两题重复收益，出现新增严重错误且Plan未交付，不能以总分抵消。',
    semanticGuarantee=False,productionDeployment=False)
with (out/'adoption-decision.json').open('x',encoding='utf-8') as f:json.dump(decision,f,ensure_ascii=False,indent=2)
closure=dict(status='CLOSED',closedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),maximumActualCalls=160,actualCalls=109,unused=51,
    phases=dict(GENERATION=39,PLAN=2,EXECUTION=68),protocolRetries=3,qualityRetries=0,previousClosedActual=1254,cumulativeActual=1363,
    oldClosedBalanceTransferred=0,defaultVersion='4',adoption='NOT_ADOPTED',qualityGoalAchieved=False,
    reason='未见题未满足完整交付、无新增严重退步及重复收益条件；不继续扩采同一假设凑通过。',
    unusedPolicy='本任务停止模型调用，三阶段已闭批；剩余不自动转用旧任务或新范围。',
    migrationsApplied=False,productionDeployment=False,temporaryServicesStopped=True)
with (task/'closure.json').open('x',encoding='utf-8') as f:json.dump(closure,f,ensure_ascii=False,indent=2)
print(json.dumps(dict(decision='NOT_ADOPTED',actualCalls=109,default='4',sustainedImprovedCases=1,aggregates=aggregates),ensure_ascii=False))
