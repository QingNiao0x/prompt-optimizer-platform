import hashlib
import json
import shutil
import subprocess
from datetime import datetime, timezone
from pathlib import Path

repo = Path.cwd()
runtime = repo / 'tmp/text-polish-history-acceptance'
evidence = repo / 'docs/testing/evidence/text-polish-history-2026-10-25'
for name, marker in [('unit-final.log', '8 passed (8)'), ('build-final.log', 'built in'),
                     ('browser-final.log', '33 passed'), ('browser-history-recheck.log', '3 passed'),
                     ('browser-detail-proof.log', '2 passed')]:
    assert marker in (runtime / name).read_text(encoding='utf-8', errors='replace'), name
    shutil.copyfile(runtime / name, evidence / name)
screenshots = {}
for path in (runtime / 'browser-results-final').rglob('text-polish-history-*.png'):
    target = ('mobile-' if path.parent.name.endswith('mobile-chrome') else 'desktop-') + ('detail.png' if 'detail' in path.name else 'list.png')
    assert target not in screenshots, target
    shutil.copyfile(path, evidence / target)
    screenshots[target] = str(path.relative_to(repo)).replace('\\', '/')
assert len(screenshots) == 4
for path in (runtime / 'browser-results-detail-proof').rglob('text-polish-history-detail.png'):
    target = ('mobile-' if path.parent.name.endswith('mobile-chrome') else 'desktop-') + 'detail.png'
    manual = evidence / target.replace('detail.png', 'manual-copy.png')
    if not manual.exists():
        shutil.copyfile(evidence / target, manual)
    shutil.copyfile(path, evidence / target)
    screenshots[target] = str(path.relative_to(repo)).replace('\\', '/')
for prefix, name in [('browser-results', 'initial-image-label-context.md'), ('browser-results-final', 'initial-shared-history-context.md')]:
    matches = sorted((runtime / prefix).rglob('error-context.md'))
    if matches:
        shutil.copyfile(matches[0], evidence / name)
shutil.copyfile(Path(__file__), evidence / 'archive.py')
sources = ['apps/web/src/pages/HistoryPage.vue', 'apps/web/src/pages/TextPolishPage.vue',
           'apps/web/src/components/textpolish/TextPolishHistoryPanel.vue', 'apps/web/src/services/textPolishApi.ts',
           'apps/web/src/stores/textPolish.ts', 'apps/web/src/stores/textPolish.test.ts',
           'apps/web/e2e/text-polish-history.spec.ts', 'apps/web/e2e/text-polish.spec.ts',
           'docs/development/文章祛AI味实现与使用说明.md', 'docs/testing/文章祛AI味历史页接入与验证-2026-10-25.md']
def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()
manifest = {'archivedAt': datetime.now(timezone.utc).isoformat(), 'gitHead': subprocess.check_output(['git', 'rev-parse', 'HEAD']).decode().strip(),
            'scope': 'authenticated text-polish history page; shared files contain preserved concurrent changes',
            'unitTests': 8, 'uniqueBrowserChecks': 36, 'articleHistoryChecks': 12,
            'browserFullRunPassed': 33, 'browserFullRunFailed': 3, 'targetedRecheckPassed': 3,
            'detailScreenshotRecheckPassed': 2,
            'realModelCalls': 0, 'databaseMigrationsAdded': 0, 'deployed': False,
            'screenshots': screenshots,
            'sources': [{'path': name, 'sha256': sha(repo / name)} for name in sources],
            'evidence': [{'path': path.name, 'sha256': sha(path)} for path in sorted(evidence.iterdir()) if path.is_file() and path.name != 'manifest.json']}
(evidence / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({'archived': True, 'unitTests': 8, 'uniqueBrowserChecks': 36, 'realModelCalls': 0, 'screenshots': len(screenshots)}))
