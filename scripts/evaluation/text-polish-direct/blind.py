import json,random,sys
from pathlib import Path
r=Path(sys.argv[1] if len(sys.argv)>1 else 'docs/testing/evidence/text-polish-direct-2026-11-11');
assert not (r/'blind-pairs.json').exists(), 'Blind packets already frozen'
p=json.loads((r/'protocol.json').read_text(encoding='utf-8'));pairs=[];mapping={};rng=random.SystemRandom()
for sample in p['samples']:
 files=[r/(sample['id']+'-'+v+'.json') for v in ['text-polish-v3','text-polish-direct-v1']]
 if not all(f.exists() for f in files): raise SystemExit('Generation not complete')
 rng.shuffle(files);mapping[sample['id']]={};pair={k:sample[k] for k in ['id','text','style','depth','requirements']}
 for label,f in zip(['A','B'],files):
  d=json.loads(f.read_text(encoding='utf-8'));assert d['status']=='COMPLETED';pair[label]=d['polishedText'];mapping[sample['id']][label]=f.name
 pairs.append(pair)
(r/'blind-pairs.json').write_text(json.dumps(pairs,ensure_ascii=False,indent=2),encoding='utf-8');(r/'blind-key.json').write_text(json.dumps(mapping,indent=2),encoding='utf-8')
