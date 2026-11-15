"""生成一次匿名作品包；评阅落盘后才读取对应key，禁止覆盖已存在的包。"""
import json
import random
import sys
from pathlib import Path

root = Path(sys.argv[1])
phase = sys.argv[2]
target = root / (phase + '-blind-pairs.json')
if target.exists():
    raise SystemExit('匿名作品包已冻结')
samples = json.loads((root/'samples.json').read_text(encoding='utf-8'))
allowed = ['ZH', 'EN', 'MIXED']
if phase == 'expand':
    allowed = json.loads((root/'pilot-review.json').read_text(encoding='utf-8'))['allowedLanguages']
pairs, key = [], {}
for sample in samples:
    if sample['group'] != phase or sample['language'] not in allowed:
        continue
    versions = ['text-polish-v3', 'text-polish-auto-v1']
    random.SystemRandom().shuffle(versions)
    pair = {k: sample[k] for k in ['id', 'language', 'text', 'meanings', 'requirements', 'style', 'depth']}
    key[sample['id']] = {}
    for label, version in zip(['A', 'B'], versions):
        result = json.loads((root/(sample['id']+'-'+version+'.json')).read_text(encoding='utf-8'))
        pair[label] = result.get('polishedText')
        pair[label+'Status'] = result['status']
        key[sample['id']][label] = version
    pairs.append(pair)
target.write_text(json.dumps(pairs, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
(root/(phase+'-blind-key.json')).write_text(json.dumps(key, indent=2)+'\n', encoding='utf-8')
print('Anonymous pairs', len(pairs))
