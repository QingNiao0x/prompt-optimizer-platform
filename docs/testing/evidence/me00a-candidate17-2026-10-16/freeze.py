"""Freeze this offline candidate and verify its source, fixtures and test provenance. No model calls."""
from pathlib import Path
from datetime import datetime, timezone, timedelta
import hashlib
import json
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

OUTPUT = Path(__file__).resolve().parent
ROOT = OUTPUT.parents[3]


def digest(path):
    """Hash exact bytes; line endings remain part of the frozen identity."""
    return hashlib.sha256(path.read_bytes()).hexdigest()


def save(name, value):
    (OUTPUT / name).write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def main():
    if not (ROOT / 'AGENTS.md').exists():
        raise RuntimeError('Wrong repository root')
    if '--verify' in sys.argv:
        frozen = json.loads((OUTPUT / 'candidate17-source.json').read_text(encoding='utf-8'))
        for group, prefix in [('sourceFiles', ROOT), ('sourceFiles', ROOT / frozen['sourceSnapshot']),
                              ('runtimeFiles', ROOT / frozen['runtimeSnapshot'])]:
            for item in frozen[group]:
                if digest(prefix / item['path']) != item['sha256']:
                    raise RuntimeError('Frozen file drift: ' + item['path'])
        print(json.dumps({'sourceAndRuntimeUnchanged': True, 'sourceFiles': len(frozen['sourceFiles']),
                          'runtimeFiles': len(frozen['runtimeFiles']), 'modelCalls': 0}))
        return

    # Every fixture must equal its unchanged captured response and the actual input payload.
    fixtures = []
    for trace in (240, 241, 254):
        path = ROOT / f'services/api/src/test/resources/enhancement/candidate17-trace{trace}.json'
        fixture = json.loads(path.read_text(encoding='utf-8'))
        origin = ROOT / fixture['source']
        trace_json = json.loads(origin.read_text(encoding='utf-8-sig'))
        if digest(origin) != fixture['sourceSha256'] or fixture['response'] != json.loads(trace_json['response']['choices'][0]['message']['content']):
            raise RuntimeError('Captured response drift')
        message = trace_json['request']['messages'][-1]['content']
        actual = json.loads(message[message.index('{'):])
        if actual['rawPrompt'] != fixture['rawPrompt']:
            raise RuntimeError('Actual raw prompt drift')
        fixtures.append({'trace': trace, 'source': fixture['source'], 'sourceSha256': digest(origin),
                         'fixtureSha256': digest(path), 'responseAndInputExact': True})
    save('fixture-verification.json', fixtures)

    selection = set(('CandidateSeventeenReplayTest CandidateFifteenDelegatedDeliveryTest OptimizationResultAssemblerTest '
        'DynamicPlatformConstraintTest TaskCompletionRegressionTest AmbiguityDetectorTest PlanScopeSafetyRegressionTest '
        'TaskConstraintAssemblyReplayTest MortalityQuestionBoundaryReplayTest PlanQuestionFilterTest '
        'DelegatedResearchQuestionRegressionTest RequirementBoundaryRegressionTest RequirementBoundaryProviderTest '
        'TaskTemplateAdaptationTest TaskContextIntegrityTest ReportDraftGuidanceTest DraftDeliveryBoundaryTest '
        'PendingReminderIdentityRegressionTest FullPendingReminderCoverageTest CompositePendingReminderReplayRegressionTest '
        'PlanPendingSubitemRegressionTest ResearchPlanningBoundaryRegressionTest PlanDelegationAndRecommendationScopeTest '
        'MybatisOptimizationHistoryServiceTest DefaultEnhancementOrchestratorTest RequirementFidelityGuardTest '
        'ExampleBoundaryCombinationTest CandidateEightExampleCasesTest ExampleContractTest PlanProviderValidationBudgetTest '
        'PlanningSessionOwnershipSerializationTest ProtectedContextFilterTest').split())
    reports = []
    report_dir = OUTPUT / 'test-reports'
    report_dir.mkdir(exist_ok=True)
    cutoff = (ROOT / 'tmp/candidate17-affected-preagent.log').stat().st_mtime - 80
    for path in (ROOT / 'services/api/target/surefire-reports').glob('TEST-*.xml'):
        tree = ET.parse(path).getroot()
        if tree.attrib['name'].split('.')[-1] not in selection:
            continue
        if path.stat().st_mtime < cutoff:
            raise RuntimeError('Stale test report: ' + path.name)
        record = {key: int(tree.attrib[key]) for key in ('tests', 'failures', 'errors', 'skipped')}
        record.update(name=tree.attrib['name'], seconds=tree.attrib['time'], rawReportSha256=digest(path))
        if any(record[key] for key in ('failures', 'errors', 'skipped')):
            raise RuntimeError('Required test not fully passed: ' + path.name)
        # Keep case outcomes; exclude properties/classpaths and console text from public evidence.
        projection = ET.Element('testsuite', tree.attrib)
        for case in tree.findall('testcase'):
            child = ET.SubElement(projection, 'testcase', case.attrib)
            for tag in ('failure', 'error', 'skipped'):
                for status in case.findall(tag):
                    child.append(status)
        ET.ElementTree(projection).write(report_dir / path.name, encoding='utf-8', xml_declaration=True)
        reports.append(record)
    if len(reports) != len(selection):
        raise RuntimeError('Missing required test report')
    summary = {key: sum(r[key] for r in reports) for key in ('tests', 'failures', 'errors', 'skipped')}
    script_log = (ROOT / 'tmp/candidate17-scripts-final.log').read_text(encoding='utf-8-sig')
    if '# pass 21' not in script_log or '# fail 0' not in script_log:
        raise RuntimeError('Script test completion missing')
    save('verification.json', {'kind': 'OFFLINE_REPLAY_AND_AFFECTED_TESTS', 'java': {'classes': len(reports), **summary, 'reports': reports},
         'scripts': {'tests': 21, 'passed': 21, 'failed': 0, 'skipped': 0, 'rawLogSha256': digest(ROOT / 'tmp/candidate17-scripts-final.log')},
         'localHistory': 'Actual history projection with mocked persistence; no database writes',
         'modelCalls': 0, 'realBrowserAndDownstreamWorks': 'NOT_RUN', 'fullRepositoryRegression': 'NOT_RUN'})

    source_paths = []
    for folder in ('services/api/src/main', 'apps/web/src'):
        source_paths.extend(p for p in (ROOT / folder).rglob('*') if p.is_file())
    source_paths.extend((ROOT / 'scripts').glob('me00a*.mjs'))
    source_paths.extend((ROOT / 'services/api/src/test').rglob('CandidateSeventeenReplayTest.java'))
    source_paths.extend((ROOT / 'services/api/src/test/resources/enhancement').glob('candidate17-trace*.json'))
    source_paths.extend(ROOT / name for name in ('services/api/pom.xml', 'package.json', 'pnpm-lock.yaml',
        'apps/web/package.json', 'apps/web/vite.config.ts', 'apps/web/tsconfig.json') if (ROOT / name).is_file())
    version = 'candidate17-r2'
    snapshot = ROOT / 'tmp/me00a-candidate17-r2-20261016'
    source_files = []
    for path in sorted(set(source_paths)):
        relative = path.relative_to(ROOT)
        target = snapshot / 'source' / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, target)
        source_files.append({'path': relative.as_posix(), 'sha256': digest(path)})
    runtime_files = []
    for path in sorted((ROOT / 'services/api/target/classes').rglob('*')):
        if not path.is_file():
            continue
        relative = path.relative_to(ROOT / 'services/api/target/classes')
        target = snapshot / 'classes' / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, target)
        runtime_files.append({'path': relative.as_posix(), 'sha256': digest(path)})
    head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    patch = subprocess.check_output(['git', 'diff', '--binary'], cwd=ROOT)
    (snapshot / 'tracked-changes.patch').write_bytes(patch)
    bundle_hash = lambda rows: hashlib.sha256('\n'.join(f"{x['path']} {x['sha256']}" for x in rows).encode()).hexdigest()
    policy_names = ('PromptOptimizationGuidance.java', 'PromptRewriteStrategy.java', 'TaskTemplateProfiles.java', 'RequirementBoundaryContract.java')
    save('candidate17-source.json', {'version': version, 'frozenAt': datetime.now(timezone.utc).astimezone(timezone(timedelta(hours=8))).isoformat(),
         'baseHead': head, 'headAloneIsNotCandidate': True, 'trackedPatchSha256': hashlib.sha256(patch).hexdigest(),
         'sourceSnapshot': (snapshot / 'source').relative_to(ROOT).as_posix(), 'runtimeSnapshot': (snapshot / 'classes').relative_to(ROOT).as_posix(),
         'sourceTreeSha256': bundle_hash(source_files), 'runtimeTreeSha256': bundle_hash(runtime_files),
         'strategyFiles': [x for x in source_files if Path(x['path']).name in policy_names],
         'sourceFiles': source_files, 'runtimeFiles': runtime_files, 'realCalls': 0, 'nextRealBatch': 'NOT_OPENED',
         'budget': {'actual': 262, 'baseline': 70, 'candidate': 192, 'limits': [275, 80, 195], 'closedUnusedNotTransferred': 3}})
    print(json.dumps({'version': version, 'java': summary, 'classes': len(reports), 'scripts': 21,
                      'sourceFiles': len(source_files), 'runtimeFiles': len(runtime_files), 'modelCalls': 0}))


if __name__ == '__main__':
    main()
