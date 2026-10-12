import json,pathlib,random,shutil,sys
root=pathlib.Path(__file__).resolve().parent
stage=sys.argv[1]
first_only=len(sys.argv)>2 and sys.argv[2]=='first'
second_only=len(sys.argv)>2 and sys.argv[2]=='second'
packet=stage+'-first' if first_only else stage+'-second' if second_only else stage
target=root/'review'/packet
target.mkdir(parents=True,exist_ok=False)
cases=json.loads((root/'cases.json').read_text(encoding='utf-8'))
pairs=[]
if stage=='stage1':
    pairs=[('Q01',m,1,'stage1') for m in ['flash','pro']]
else:
    pairs=[(c['id'],m,r,stage) for c in cases for m in ['flash','pro'] for r in ([1] if first_only else [2] if second_only else [1,2])]
key=[]
for n,(case_id,model,round_no,version) in enumerate(pairs,1):
    sample=next(c for c in cases if c['id']==case_id)
    directory=target/f'pair-{n:02d}';directory.mkdir()
    (directory/'requirements.json').write_text(json.dumps(sample,ensure_ascii=False,indent=2),encoding='utf-8')
    baseline=root/f'baseline-r{2 if model=="flash" else 1}'/f'{case_id}-{model}'
    candidate=root/f'{version}-r{round_no}'/f'{case_id}-{model}'
    versions=[('old',baseline),('candidate',candidate)];random.SystemRandom().shuffle(versions)
    for letter,(kind,source) in zip(['A','B'],versions):
        if (source/'failure.json').exists():
            (directory/f'{letter}-failure.txt').write_text('该版本未成功交付可执行提示词，因此没有下游作品。',encoding='utf-8')
        else:
            shutil.copy2(source/'copied.md',directory/f'{letter}-prompt.md')
            shutil.copy2(source/'work.md',directory/f'{letter}-work.md')
        key.append({'pair':directory.name,'letter':letter,'kind':kind,'case':case_id,'model':model,'round':round_no})
(root/f'{packet}-review-key.json').write_text(json.dumps(key,ensure_ascii=False,indent=2),encoding='utf-8')
(target/'rubric.md').write_text('''独立盲评；仅可读取本目录提供的资料，不读取源码、其他评审、版本映射或上级证据。A/B为随机标签，不代表优劣；模型与版本隐藏。逐个pair审阅需求、资料、开关及两份提示词与下游作品。分别给准确性、完整性、清晰度、可执行性1至5分；任何未执行、目标改变、编造事实、硬约束违反单列严重错误，不能被总分掩盖。专业候选明确条件且不声称已确认时合法；尾段免责声明不能撤销前句事实性错误；允许直接可复算的数值。审查提示词和下游作品，归因平台引入、下游违约或无法区分。短任务不要因章节多、篇幅长给高分，充分需求不必要扩写应扣分；模糊任务可补方法与适用条件，但不得冒充事实。判断A胜/B胜/平/双方失败，给直接证据和失败原因。不得预设增强版本更优。输出JSON数组，每项{pair,A:{accuracy,completeness,clarity,executability,severeErrors:[],notes:[]},B:{...},verdict,reason}。不得调用外部模型或网络。此为AI辅助评审，不代表人工或领域专家验收。''',encoding='utf-8')
print(f'Created {len(pairs)} blinded pairs in {target}')
