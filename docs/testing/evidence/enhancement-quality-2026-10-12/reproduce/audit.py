"""Independent mechanical checks; semantic verdicts remain in the blind reviews."""
import collections,hashlib,json,pathlib,re,statistics
root=pathlib.Path(__file__).resolve().parent
read=lambda path:json.loads(path.read_text(encoding='utf-8'))
rows=[]
for run in sorted(root.glob('*-r[0-9]*')):
    if not run.is_dir():continue
    for directory in sorted(run.glob('Q*')):
        row={'run':run.name,'case':directory.name}
        if (directory/'failure.json').exists():row['failure']=read(directory/'failure.json')['message']
        if (directory/'platform.json').exists():
            platform=read(directory/'platform.json');row['httpStatus']=platform['status'];row['elapsedMs']=platform['elapsedMs']
            result=platform.get('body',{}).get('data') or {}
            row['format']=result.get('promptFormat','STRUCTURED')
            row['copiedCharacters']=len(result.get('optimizedPrompt',''))
            row['sections']=[s['type'] for s in result.get('sections',[])]
            if (directory/'history.json').exists():
                history=read(directory/'history.json')
                row['historyCopyMatches']=history['status']==200 and history['body']['data']['optimizedPrompt']==result['optimizedPrompt']
                row['historyFormatMatches']=history['body']['data'].get('promptFormat','STRUCTURED')==row['format']
        if (directory/'work.md').exists():
            work=(directory/'work.md').read_text(encoding='utf-8')
            row['workCharacters']=len(work)
            lines=work.splitlines();body='\n'.join(line for line in lines if not line.lstrip().startswith('|'))
            row['bodyChineseWithHeadings']=len(re.findall(r'[\u4e00-\u9fff]',body))
            without='\n'.join(line for line in body.splitlines() if not line.lstrip().startswith('#'))
            row['bodyChineseWithoutHeadings']=len(re.findall(r'[\u4e00-\u9fff]',without))
            if directory.name.startswith('Q05'):
                row['withinUserChineseRange']=800<=row['bodyChineseWithHeadings']<=1200
            if directory.name.startswith('Q06'):
                row['exactlyTwoNonemptyLines']=len([line for line in lines if line.strip()])==2
                row['beforeThursday']=bool(re.search(r'before Thursday',work,re.I))
                row['byThursdayChangesBoundary']=bool(re.search(r'(?:by|no later than) Thursday',work,re.I))
            if directory.name.startswith('Q07'):
                try:value=json.loads(work);row['strictJson']=isinstance(value,dict) and set(value)=={'name','age'} and value['name']=='李明' and type(value['age']) is int and value['age']==28
                except (ValueError,TypeError):row['strictJson']=False
        rows.append(row)
identities=[read(path)['identity']['userId'] for path in root.glob('*-r[0-9]*/identity.json')]
calls=[]
for path in sorted((root/'traces').glob('*.json')):
    trace=read(path);response=trace.get('response',{})
    calls.append({'file':path.name,'version':trace.get('version',trace.get('label','unknown')),'phase':'enhancement' if 'enhancement' in path.name else 'downstream','model':trace.get('request',{}).get('model'), 'status':trace.get('httpStatus'), 'usage':response.get('usage') if isinstance(response,dict) else None,'elapsedMs':trace.get('elapsedMs')})
usage=collections.Counter()
for call in calls:
    for key in ['prompt_tokens','completion_tokens','total_tokens']:
        if call['usage'] and isinstance(call['usage'].get(key),int):usage[key]+=call['usage'][key]
audit={'sameAccount':len(set(identities))==1,'accountCount':len(set(identities)),'accountFingerprint':hashlib.sha256(identities[0].encode()).hexdigest()[:16] if identities else None,'actualCalls':len(calls),'callsWithUsage':sum(c['usage'] is not None for c in calls),'reportedTokenTotals':dict(usage),'moneyCost':'UNKNOWN: no verified price or billing receipt; tokens are not currency','rows':rows,'calls':calls}
(root/'mechanical-audit.json').write_text(json.dumps(audit,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({k:v for k,v in audit.items() if k not in ('rows','calls')},ensure_ascii=False))
