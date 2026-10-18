"""Extract unchanged authored source, compile implementation/tests together, and run fixed independent cases."""
from pathlib import Path
import json
import re
import hashlib
import subprocess
import os
import shutil

PUBLIC = Path(__file__).resolve().parent
ROOT = PUBLIC.parents[3]
LIVE = ROOT / 'tmp/me01-candidate40-live-20261018'
sha = lambda data: hashlib.sha256(data).hexdigest()


def run(args, cwd, log):
    env = {k: v for k, v in os.environ.items() if k.upper() in {'SYSTEMROOT', 'WINDIR', 'PATH', 'TEMP', 'TMP', 'PATHEXT', 'COMSPEC'}}
    result = subprocess.run(args, cwd=cwd, env=env, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=60)
    log.write_text(result.stdout + result.stderr, encoding='utf-8')
    return result


def main():
    spec_bytes = (PUBLIC / 'work-check-spec.json').read_bytes()
    spec = json.loads(spec_bytes)
    reports = {}
    for arm in ['RAW', 'PLAN']:
        folder = LIVE / 'works/TS' / arm
        work_bytes = (folder / 'work.md').read_bytes()
        text = work_bytes.decode('utf-8')
        blocks = list(re.finditer(r'^```(?:ts|typescript)\s*\n(.*?)^```', text, re.M | re.S))
        assert len(blocks) == (2 if arm == 'RAW' else 3), 'Unexpected authored file layout; stop without repairing source'
        code_dir = folder / 'compiled-check-v2'
        code_dir.mkdir(exist_ok=False)
        implementation = blocks[0].group(1)
        assert 'export function dedupeLines' in implementation
        assert not re.search(r'child_process|process\.env|\.env|readFile|fetch\(|https?://', implementation)
        (code_dir / 'dedupe.ts').write_text(implementation, encoding='utf-8', newline='')
        files = ['dedupe.ts']
        authored = None
        if arm == 'PLAN':
            authored = blocks[2].group(1)
            (code_dir / 'dedupe.test.ts').write_text(authored, encoding='utf-8', newline='')
            files.append('dedupe.test.ts')
        (code_dir / 'package.json').write_text('{"type":"commonjs"}\n', encoding='utf-8')
        node_executable = shutil.which('node')
        assert node_executable, 'Use the installed Node executable; do not assume an installation directory'
        compiler = [node_executable,
                    str(ROOT / 'apps/web/node_modules/typescript/bin/tsc'), *files, '--target', 'es2019', '--module', 'commonjs',
                    '--types', 'node', '--typeRoots', str(ROOT / 'apps/web/node_modules/@types'), '--noEmitOnError', '--outDir', 'out']
        node = compiler[0]
        default_compile = run(compiler, code_dir, code_dir / 'compile-provided-flags.log')
        compile_result = default_compile
        if default_compile.returncode:
            # Only an explicit compiler interoperability profile is tried; generated source remains byte-identical.
            compile_result = run(compiler + ['--esModuleInterop', 'true'], code_dir, code_dir / 'compile-interop-profile.log')
        row = {'workSha256': sha(work_bytes), 'sourceRepaired': False, 'specSha256': sha(spec_bytes),
               'implementationSha256': sha(implementation.encode()), 'extractedTypeScriptBlocks': len(blocks),
               'usageFragmentCompiledAsTest': False, 'providedCompilerFlagsExitCode': default_compile.returncode,
               'controlledInteropProfileUsed': bool(default_compile.returncode), 'jointCompileExitCode': compile_result.returncode}
        if authored is not None:
            row['authoredTestSha256'] = sha(authored.encode())
        if compile_result.returncode == 0:
            (code_dir / 'cases.json').write_text(json.dumps(spec['commonInputsAndExpectedOutputs'], ensure_ascii=False), encoding='utf-8')
            harness = '''const fs=require('node:fs'); const {dedupeLines}=require('./out/dedupe.js');
const cases=JSON.parse(fs.readFileSync('cases.json','utf8'));
const rows=cases.map(([input,expected],index)=>{try {const actual=dedupeLines(input);return {index:index+1,state:actual===expected?'PASS':'FAIL',expected,actual};}catch(e){return {index:index+1,state:'FAIL',failure:e.name};}});
console.log(JSON.stringify({tests:rows.length,passed:rows.filter(r=>r.state==='PASS').length,rows}));
process.exitCode=rows.some(r=>r.state==='FAIL')?1:0;
'''
            (code_dir / 'independent.cjs').write_text(harness, encoding='utf-8')
            independent = run([node, 'independent.cjs'], code_dir, code_dir / 'independent.log')
            row['independent'] = json.loads(independent.stdout)
            if authored is not None:
                own = run([node, '--test', 'out/dedupe.test.js'], code_dir, code_dir / 'authored-tests.log')
                row['authoredTests'] = {'exitCode': own.returncode}
                for field in ['tests', 'pass', 'fail', 'skipped']:
                    match = re.search(r'^# ' + field + r' (\d+)\s*$', own.stdout, re.M)
                    row['authoredTests'][field] = int(match.group(1)) if match else None
                row['authoredTests']['state'] = 'PASS' if own.returncode == 0 else 'FAIL'
            else:
                row['authoredTests'] = {'state': 'NOT_REQUIRED_BY_RAW_INPUT'}
        row['commonContractState'] = 'PASS' if compile_result.returncode == 0 and row['independent']['passed'] == 12 else 'FAIL'
        row['declaredTestCommitmentState'] = row['authoredTests']['state'] if arm == 'PLAN' else 'NOT_REQUIRED_BY_RAW_INPUT'
        reports[arm] = row
    result = {'case': 'TS-IMPLICIT', 'node': subprocess.check_output([node, '--version'], text=True).strip(),
              'criteriaFrozenBeforeReadingWorks': True, 'works': reports, 'sourceRepaired': False,
              'oldCredentialAndProfessionalGates': 'OPEN_NOT_COVERED', 'externalModelCalls': 0}
    with (LIVE / 'hard-checks.json').open('x', encoding='utf-8') as output:
        json.dump(result, output, ensure_ascii=False, indent=2)
    print(json.dumps({arm: {'common': value['commonContractState'], 'authored': value['authoredTests']} for arm, value in reports.items()}))


if __name__ == '__main__':
    main()
