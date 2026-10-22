"""Create anonymous work IDs; do not claim author or external reviewer independence."""
from pathlib import Path
import hashlib,json,sys

root=Path(__file__).resolve().parents[4]
source=root/sys.argv[1]
target=root/sys.argv[2]
target.mkdir(parents=True,exist_ok=True)
read=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
results=read(source/'http-results.json')
cases=read(source/'cases.json')['cases']
mapping={}
packs=[]
for case in cases:
    works=[]
    for r in results:
        if r['caseId']!=case['id'] or r['phase']!='EXECUTION':continue
        label='W-'+hashlib.sha256(('information-state-review:'+r['id']).encode()).hexdigest()[:12]
        mapping[label]=r['id']
        works.append(dict(id=label,status=r['status'],content=r['body'].get('data',{}).get('content')))
    packs.append(dict(case=case,works=sorted(works,key=lambda w:w['id'])))
instructions='按原始要求、材料、真实答复和acceptance逐项判断；合理措辞与设计变体允许。每件作品记录severeErrors、minorErrors、minimumEdits（修复独立事实/硬要求所需编辑项数，不按字符）、证据引用和理由。只评价实际缺陷，不因为风格不同扣分。不读映射、http结果或transports。内部协作代理评审，不声称外部专业独立。'
for name,value in [('anonymous-works.json',dict(instructions=instructions,cases=packs)),('mapping.private.json',mapping)]:
    with (target/name).open('x',encoding='utf-8') as f:json.dump(value,f,ensure_ascii=False,indent=2);f.write('\n')
print('Anonymous works prepared:',sum(len(p['works']) for p in packs))
