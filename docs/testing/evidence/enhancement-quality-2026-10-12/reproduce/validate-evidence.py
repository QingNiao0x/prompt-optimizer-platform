"""Check archived evidence, frozen source, independent review completeness and report links."""
import hashlib
import json
import pathlib
import re

root = pathlib.Path(__file__).resolve().parent
repo = next(parent for parent in [root, *root.parents]
            if (parent / 'AGENTS.md').is_file() and (parent / 'apps/web').is_dir())
archive = repo / 'docs/testing/evidence/enhancement-quality-2026-10-12'
manifest = json.loads((archive / 'manifest-sha256.json').read_text(encoding='utf-8'))
for name, expected in manifest.items():
    source = archive / name
    assert source.is_file(), name
    assert hashlib.sha256(source.read_bytes()).hexdigest() == expected, name
frozen = json.loads((archive / 'candidate5/source-hashes.json').read_text(encoding='utf-8'))
for name, expected in frozen.items():
    source = repo / name
    assert source.is_file() and hashlib.sha256(source.read_bytes()).hexdigest() == expected, name
audit = json.loads((archive / 'mechanical-audit.json').read_text(encoding='utf-8'))
assert audit['sameAccount'] and audit['accountCount'] == 1
assert audit['actualCalls'] == audit['callsWithUsage'] == 295
review = json.loads((archive / 'candidate5-blind-summary.json').read_text(encoding='utf-8'))
assert review['releaseGate'].startswith('BLOCKED')
assert {row['reviewer'] for row in review['reviewers']} == {'one', 'two'}
for row in review['reviewers']:
    assert len(row['results']) == sum(row['counts'].values()) == 28
report = repo / 'docs/testing/提示词增强质量修复与真实盲评-2026-10-12.md'
text = report.read_text(encoding='utf-8')
assert 'FINAL_BLIND_REVIEW_SUMMARY' not in text
links = re.findall(r'\]\((\./[^)]+)\)', text)
for target in links:
    assert (report.parent / target.split('#')[0]).exists(), target
for source in archive.rglob('*.json'):
    content = source.read_text(encoding='utf-8-sig')
    assert '\ufffd' not in content, source.relative_to(archive)
    json.loads(content)
forbidden_files = [name for name in manifest
                   if re.search(r'(?i)(account-identity|login-fixture|credentials|\.env(?:\.|$))', name)]
assert not forbidden_files, forbidden_files
print(json.dumps({'manifestFilesVerified': len(manifest), 'sourceFilesUnchanged': len(frozen),
                  'sameTestAccount': True, 'reportLinksVerified': len(links),
                  'reviewComparisonsPerReviewer': 28, 'actualProviderCalls': audit['actualCalls'],
                  'releaseGate': 'BLOCKED'}, ensure_ascii=False))
