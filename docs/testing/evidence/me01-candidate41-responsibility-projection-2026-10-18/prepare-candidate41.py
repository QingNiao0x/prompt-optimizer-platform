"""Freeze the current source and reuse the corrected candidate40 harness without upstream calls."""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json
import shutil
import subprocess
import xml.etree.ElementTree as ET

PUBLIC = Path(__file__).resolve().parent
ROOT = PUBLIC.parents[3]
OLD = ROOT / 'docs/testing/evidence/me01-candidate40-language-intent-2026-10-18'
PRIVATE = ROOT / 'tmp/me01-candidate41-20261018'
sha = lambda data: hashlib.sha256(data).hexdigest()


def save(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('x', encoding='utf-8', newline='\n') as output:
        output.write(data if isinstance(data, str) else json.dumps(data, ensure_ascii=False, indent=2) + '\n')


def main():
    previous_bytes = (OLD / 'candidate40-source.json').read_bytes()
    previous = json.loads(previous_bytes)
    tracked = subprocess.run(['git', '-c', 'core.quotepath=false', 'ls-files', '--others', '--exclude-standard',
                              'apps/web', 'services/api/src', 'scripts'], cwd=ROOT, check=True, capture_output=True).stdout.decode('utf-8').splitlines()
    paths = sorted(set(row['path'] for row in previous['sourceFiles']) | set(tracked))
    source = PRIVATE / 'frozen/source'
    runtime = PRIVATE / 'frozen/classes'
    assert not source.exists() and not runtime.exists(), 'Do not overwrite an existing freeze'
    source_rows = []
    for name in paths:
        path = ROOT / name
        assert path.is_file(), name
        target = source / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, target)
        source_rows.append({'path': name, 'sha256': sha(path.read_bytes())})
    runtime_rows = []
    classes = ROOT / 'services/api/target/classes'
    for path in sorted(classes.rglob('*')):
        if not path.is_file():
            continue
        name = path.relative_to(classes).as_posix()
        target = runtime / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, target)
        runtime_rows.append({'path': name, 'sha256': sha(path.read_bytes())})
    head = subprocess.run(['git', 'rev-parse', 'HEAD'], cwd=ROOT, check=True, capture_output=True).stdout.decode().strip()
    manifest = {'version': 'candidate41', 'baseHead': head, 'frozenAt': datetime.now(timezone.utc).isoformat(),
                'parentCandidate': 'candidate40', 'parentManifestSha256': sha(previous_bytes),
                'sourceSnapshot': source.relative_to(ROOT).as_posix(), 'runtimeSnapshot': runtime.relative_to(ROOT).as_posix(),
                'sourceFiles': source_rows, 'runtimeFiles': runtime_rows}
    save(PUBLIC / 'candidate41-source.json', manifest)
    names = (ROOT / 'tmp/me01-candidate40-20261018/affected-tests.txt').read_text().strip().split(',')
    names += ['TaskResponsibilityProjectionTest', 'QuestionResponsibilityProviderTest']
    totals = dict(classes=len(names), tests=0, failures=0, errors=0, skipped=0)
    reports = []
    for name in names:
        found = list((ROOT / 'services/api/target/surefire-reports').glob('TEST-*.' + name + '.xml'))
        assert len(found) == 1, name
        report = found[0]
        suite = ET.fromstring(report.read_bytes())
        for key in ['tests', 'failures', 'errors', 'skipped']:
            totals[key] += int(suite.attrib[key])
        target = PRIVATE / 'final-reports' / report.name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(report, target)
        reports.append({'path': target.relative_to(ROOT).as_posix(), 'sha256': sha(target.read_bytes())})
    assert totals['failures'] == totals['errors'] == totals['skipped'] == 0
    save(PRIVATE / 'affected-tests.txt', ','.join(names) + '\n')
    save(PUBLIC / 'verification.json', {'scope': 'affected Java tests, not repository-wide or live acceptance',
        'java': totals, 'reports': reports, 'realCalls': 0,
        'old416418Unchanged': True, 'oldResponseFallbackStillConservative': True,
        'failuresPreserved': ['tmp/candidate41-targeted-first.log', 'tmp/candidate41-affected-first.log'],
        'finalLogs': ['tmp/candidate41-affected-host.log', 'tmp/candidate41-provider-targeted.log'],
        'sameAccountRequired': True, 'downstreamOldSafetyAndTruthfulness': 'OPEN_NOT_CLOSED_BY_THIS_NODE'})

    filenames = ['Candidate40EvaluationServer.java', 'ReserveEvaluationCall.java', 'Candidate40ScopePreflight.java',
                 'start-candidate40.ps1', 'start-live-services.ps1', 'account-browser.mjs', 'run-flow.mjs',
                 'external-calls.mjs', 'check-works.py']
    for name in filenames:
        value = (OLD / name).read_text(encoding='utf-8')
        value = value.replace('me01-candidate40-language-intent', 'me01-candidate41-responsibility-projection')
        value = value.replace('Candidate40', 'Candidate41').replace('candidate40', 'candidate41')
        if name.endswith('EvaluationServer.java'):
            replacements = [('asInt() != 415', 'asInt() != 422'), ('asInt() != 345', 'asInt() != 352'),
                ('maximumNewCalls").asInt() != 13', 'maximumNewCalls").asInt() != 6'),
                ('"PLATFORM_GENERATION").asInt() != 3', '"PLATFORM_GENERATION").asInt() != 1'),
                ('"REVIEW").asInt() != 2', '"REVIEW").asInt() != 0'),
                ('"PLANNING").asInt() != 6', '"PLANNING").asInt() != 3')]
            for before, after in replacements:
                assert before in value, before
                value = value.replace(before, after)
            before = 'return payload.path("taskView").equals(JSON.valueToTree(view.modelInput()))'
            assert before in value
            value = value.replace(before, 'return payload.path("requirementReferences").equals(expectedReferences(view))\n                                && payload.path("taskView").equals(JSON.valueToTree(view.modelInput()))')
            at = value.index('    static String inputPurpose')
            value = value[:at] + '''    /** New request references are reconstructed from the exact bound view and current task rules. */
    static JsonNode expectedReferences(com.promptoptimizer.enhancement.domain.EffectiveTaskView view) {
        var context = new com.promptoptimizer.context.domain.ContextSnapshot("", java.util.List.of(), java.util.List.of(),
                java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(), "EMPTY");
        var rules = new com.promptoptimizer.policy.service.impl.ConstraintCompleterImpl().completeForTask(context,
                com.promptoptimizer.enhancement.dto.PermissionPolicyInput.empty(), true, view.intent());
        var template = new com.promptoptimizer.template.service.impl.PromptTemplateRegistryImpl()
                .resolve(view.intent().templateCode(), view.originalPrompt());
        var input = new com.promptoptimizer.provider.domain.EnhancementProviderRequest(view.originalPrompt(), context,
                template, java.util.List.of(), java.util.List.of(), true, rules.visibleConstraints(), java.util.List.of(),
                com.promptoptimizer.enhancement.dto.EnhancementOptions.defaults(), null, java.util.List.of(), view.confirmedDecisions(), view);
        return JSON.valueToTree(com.promptoptimizer.enhancement.service.impl.BoundRequirementProjection.forProvider(input));
    }

''' + value[at:]
        elif name.endswith('ScopePreflight.java'):
            value = value.replace('payload.set("taskView", JSON.valueToTree(view.modelInput()));',
                    'payload.set("taskView", JSON.valueToTree(view.modelInput()));\n        payload.set("requirementReferences", Candidate41EvaluationServer.expectedReferences(view));')
            value = value.replace('batch.put("maximumNewCalls", 14)', 'batch.put("maximumNewCalls", 7)').replace('batch.put("maximumNewCalls", 13)', 'batch.put("maximumNewCalls", 6)')
            value = value.replace('put("PLANNING", 7)', 'put("PLANNING", 4)').replace('put("PLANNING", 6)', 'put("PLANNING", 3)')
            # All mutations retain the same checks, with the new approved allocation.
        elif name == 'run-flow.mjs':
            value = value.replace("['TS-IMPLICIT', 'TS-USER-CHOICE']", "['TS-IMPLICIT', 'TS-USER-CHOICE', 'HOLDOUT-TEMPERATURE']")
            value = value.replace('maximumNewCalls !== 13', 'maximumNewCalls !== 6').replace('callsBefore !== 415', 'callsBefore !== 422')
            value = value.replace('{ PLANNING: 6, PLATFORM_GENERATION: 3, EXECUTION: 2, REVIEW: 2 }', '{ PLANNING: 3, PLATFORM_GENERATION: 1, EXECUTION: 2, REVIEW: 0 }')
        elif name == 'external-calls.mjs':
            value = value.replace('maximumNewCalls!==13', 'maximumNewCalls!==6').replace('maximumNewCalls !== 13', 'maximumNewCalls !== 6')
            value = value.replace('callsBefore!==415', 'callsBefore!==422').replace('callsBefore !== 415', 'callsBefore !== 422')
        save(PUBLIC / name.replace('Candidate40', 'Candidate41').replace('candidate40', 'candidate41'), value)
    save(PUBLIC / 'preparation-summary.json', {'sourceFiles': len(source_rows), 'runtimeFiles': len(runtime_rows),
        'java': totals, 'modelCalls': 0, 'sourceManifestSha256': sha((PUBLIC / 'candidate41-source.json').read_bytes())})
    print(json.dumps({'frozen': 'candidate41', 'sourceFiles': len(source_rows), 'runtimeFiles': len(runtime_rows), 'java': totals}))


if __name__ == '__main__':
    main()
