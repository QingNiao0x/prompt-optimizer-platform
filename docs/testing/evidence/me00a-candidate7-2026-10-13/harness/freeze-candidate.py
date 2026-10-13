"""Freeze compiled local source before any candidate model request; never overwrites a version."""
from pathlib import Path
import sys
import json
import hashlib
import subprocess
import shutil
root = Path(__file__).resolve().parent
repo = root.parent.parent
version = sys.argv[1]
if version not in ['candidate1', 'candidate2', 'candidate3', 'candidate4', 'candidate5', 'candidate6', 'candidate7']:
    raise ValueError('Unknown candidate version')
target = root / version
if target.exists():
    raise FileExistsError('Candidate snapshot already exists')
before = json.loads((root / 'source-before.json').read_text(encoding='utf-8-sig'))
sources = [path for path in before['files'] if path.startswith('services/api/src')]
sources += ['services/api/src/main/java/com/promptoptimizer/provider/domain/TaskMeaningContract.java',
            'services/api/src/test/java/com/promptoptimizer/enhancement/service/impl/TaskMeaningRegressionTest.java',
            'services/api/src/test/java/com/promptoptimizer/enhancement/service/impl/DelegatedResearchQuestionRegressionTest.java',
            'services/api/src/test/java/com/promptoptimizer/provider/infrastructure/openai/MissingPresentationResponseTest.java',
            'scripts/me00a-eval.mjs', 'scripts/me00a-eval.test.mjs']
sources += [r['path'] for r in json.loads((repo / 'docs/testing/evidence/me03a-object-binding-2026-10-13/tested-source.json').read_text(encoding='utf-8-sig'))]
sources += list(json.loads((repo / 'docs/testing/evidence/me00a-candidate6-2026-10-13/local-followup/budget-guard-final/source.json').read_text(encoding='utf-8'))['files'])
sources += subprocess.check_output(['git', 'ls-files', 'apps/web/src', 'apps/web/vite.config.ts', 'apps/web/package.json', 'apps/web/package-lock.json'], cwd=repo).decode('utf-8').splitlines()
manifest = {'version': version, 'commit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=repo).decode().strip(), 'baseCasesSha256': before['casesSha256'],
            'snapshot': 'current compiled local source; not a Docker API image', 'protocolSha256': hashlib.sha256((root / 'protocol.json').read_bytes()).hexdigest(), 'traceServerSha256': hashlib.sha256((root / 'MeEvaluationServer.java').read_bytes()).hexdigest(), 'files': {}}
for name in sorted(set(sources)):
    path = repo / name
    if path.exists():
        manifest['files'][name] = hashlib.sha256(path.read_bytes()).hexdigest()
manifest['changedSinceBefore'] = [name for name, digest in manifest['files'].items() if before['files'].get(name) != digest]
target.mkdir()
shutil.copytree(repo / 'services/api/target/classes', target / 'classes')
manifest['criticalClasses'] = {str(path.relative_to(target / 'classes')).replace('\\', '/'): hashlib.sha256(path.read_bytes()).hexdigest()
    for path in (target / 'classes/com/promptoptimizer/provider/domain').glob('TaskMeaningContract*.class')}
(target / 'source.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
diff = subprocess.run(['git', 'diff', '--', 'services/api/src', 'scripts/me00a-eval.mjs'], cwd=repo, capture_output=True, check=True).stdout
(target / 'tracked-source.diff').write_bytes(diff)
print(json.dumps({'version': version, 'changedSourceFiles': len(manifest['changedSinceBefore']), 'criticalClassFiles': len(manifest['criticalClasses'])}))
