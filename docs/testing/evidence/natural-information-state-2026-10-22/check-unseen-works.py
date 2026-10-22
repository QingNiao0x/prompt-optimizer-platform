"""Deterministic format checks only; content decisions remain separate."""
from pathlib import Path
import json

out=Path(__file__).resolve().parent.parent/'natural-information-state-unseen-2026-10-22'
rows=json.loads((out/'http-results.json').read_text(encoding='utf-8'))
checks=[]
for row in rows:
    if row['phase']!='EXECUTION' or row['status']!=200:continue
    text=row['body']['data']['content'].strip()
    case=row['caseId']
    if 'LOAN_ITEMS' in case:
        try:
            data=json.loads(text)
            passed=isinstance(data,list) and len(data)==3 and all(set(item)=={'name','confirmed','pending'} and all(isinstance(v,str) for v in item.values()) for item in data)
        except (ValueError,TypeError):passed=False
        checks.append(dict(jobId=row['id'],kind='JSON array of three objects with exact string fields',passed=passed))
    if 'READING_VENUE' in case or 'USED_BOOK_REPLY' in case:
        limit=300 if 'READING_VENUE' in case else 120
        checks.append(dict(jobId=row['id'],kind='character limit including punctuation and internal whitespace',characters=len(text),limit=limit,passed=len(text)<=limit))
    if 'LIBRARY_SENTENCE' in case:
        checks.append(dict(jobId=row['id'],kind='one sentence without extra paragraphs',passed=sum(text.count(c) for c in '。！？')==1 and '\n' not in text))
with (out/'mechanical-work-checks.json').open('x',encoding='utf-8') as f:
    json.dump(dict(checks=checks,boundary='格式通过不证明事实、必要性或实际收益；失败和未交付单独报告。'),f,ensure_ascii=False,indent=2)
print(json.dumps(dict(checks=len(checks),passed=sum(c['passed'] for c in checks))))
