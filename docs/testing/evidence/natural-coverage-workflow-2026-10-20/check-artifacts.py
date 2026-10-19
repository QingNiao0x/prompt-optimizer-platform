"""冻结原任务的客观作品检查；不修补生成源码，不把格式通过当作语义质量通过。"""
from pathlib import Path
from decimal import Decimal, ROUND_HALF_UP
import json
import os
import subprocess

ROOT = Path(__file__).resolve().parents[3].parent
EVIDENCE = Path(__file__).resolve().parent
RUN = ROOT / 'tmp/natural-coverage-workflow-20261020'
JDK = Path('E:/Delelop/Java/jdk-21/bin')


def load(path):
    return json.loads(path.read_text(encoding='utf-8'))


def unchanged_write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        if path.read_text(encoding='utf-8') != text:
            raise RuntimeError('Existing artifact differs; do not overwrite')
    else:
        path.write_text(text, encoding='utf-8')


def java_check(arm, content):
    folder = RUN / 'artifact-checks' / arm
    unchanged_write(folder / 'PriceCalculator.java', content)
    examples = [
        ('0', False), ('0', True), ('99.99', True), ('99.995', True),
        ('100', True), ('100.00', False), ('100.005', True), ('100.05', True),
        ('12.345', False), ('0.005', False), ('123456.789', True), ('1.234', True),
    ]
    checks = []
    for index, (value, member) in enumerate(examples):
        amount = Decimal(value)
        expected = ((amount * Decimal('0.9')) if member and amount >= 100 else amount).quantize(Decimal('0.01'), rounding=ROUND_HALF_UP)
        checks.append('check("%s", %s, "%s");' % (value, str(member).lower(), expected))
    harness = '''import java.math.BigDecimal;
public class PriceAcceptance {
  static int passed=0;
  static void check(String value, boolean member, String expected) {
    BigDecimal input=new BigDecimal(value);
    BigDecimal result=PriceCalculator.payable(input,member);
    if (!new BigDecimal(expected).equals(result) || !input.equals(new BigDecimal(value))) throw new AssertionError("Value/scale/immutability");
    passed++;
  }
  static void invalid(BigDecimal value, boolean member) {
    try { PriceCalculator.payable(value,member); throw new AssertionError("Missing IllegalArgumentException"); }
    catch (IllegalArgumentException expected) { passed++; }
  }
  public static void main(String[] args) {
    invalid(null,false); invalid(null,true); invalid(new BigDecimal("-1"),false); invalid(new BigDecimal("-0.01"),true);
    %s
    if (passed!=16) throw new AssertionError("Unexpected case count");
    System.out.println("PASS "+passed);
  }
}
''' % '\n    '.join(checks)
    unchanged_write(folder / 'PriceAcceptance.java', harness)
    environment = {key: os.environ[key] for key in ('SystemRoot', 'WINDIR', 'TEMP', 'TMP', 'PATH') if key in os.environ}
    compilation = subprocess.run([str(JDK / 'javac.exe'), '-encoding', 'UTF-8', '--release', '21', '-proc:none',
                                  '-d', str(folder), str(folder / 'PriceCalculator.java'), str(folder / 'PriceAcceptance.java')],
                                 capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=25, env=environment)
    result = {'compilePassed': compilation.returncode == 0, 'compilerOutput': compilation.stdout + compilation.stderr,
              'strictSourceFormatPassed': '```' not in content, 'behaviorTests': 0, 'behaviorPassed': False}
    if compilation.returncode == 0:
        execution = subprocess.run([str(JDK / 'java.exe'), '-Xmx64m', '-cp', str(folder), 'PriceAcceptance'],
                                   capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=6, env=environment)
        result.update(behaviorTests=16, behaviorPassed=execution.returncode == 0 and 'PASS 16' in execution.stdout,
                      executionOutput=execution.stdout + execution.stderr)
    return result


def check_json(content, expected):
    try:
        value = json.loads(content)
        valid = (isinstance(value, dict) and set(value) == {'members', 'pending'}
                 and isinstance(value['members'], list) and isinstance(value['pending'], list)
                 and all(isinstance(member, dict) and set(member) == {'name', 'date'} for member in value['members']))
        canonical = lambda values: sorted(json.dumps(item, ensure_ascii=False, sort_keys=True) for item in values)
        return {'parsePassed': True, 'exactDataAndFieldsPassed': bool(valid
                and canonical(value['members']) == canonical(expected['members'])
                and canonical(value['pending']) == canonical(expected['pending']))}
    except (ValueError, TypeError):
        return {'parsePassed': False, 'exactDataAndFieldsPassed': False}


def main():
    cases = {row['id']: row for row in load(EVIDENCE / 'cases.json')['cases']}
    protocol = load(EVIDENCE / 'protocol.json')
    results = []
    for case_id in protocol['execution']:
        for arm in ('RAW', 'V3', 'V4'):
            path = RUN / 'results' / f'{case_id}_{arm}_EXEC.json'
            if not path.exists():
                results.append({'caseId': case_id, 'arm': arm, 'state': 'NOT_EXECUTED'})
                continue
            response = load(path)
            content = response.get('body', {}).get('data', {}).get('content', '')
            row = {'caseId': case_id, 'arm': arm, 'httpStatus': response['status'],
                   'completeResponse': response.get('body', {}).get('data', {}).get('finishReason') == 'stop',
                   'nonWhitespaceCharacters': sum(not char.isspace() for char in content), 'semanticReviewRequired': True}
            if response['status'] == 200:
                if case_id == 'JAVA_COMPLETE':
                    row.update(java_check(arm, content))
                elif case_id == 'JSON_EXTRACT':
                    row.update(check_json(content, cases[case_id]['expected']))
                elif case_id in ('MESSAGE', 'SOURCE_REPORT'):
                    row['lengthLimit'] = 50 if case_id == 'MESSAGE' else 400
                    row['lengthPassed'] = row['nonWhitespaceCharacters'] <= row['lengthLimit']
            results.append(row)
    output = {'results': results, 'realModelCallsAdded': 0, 'sourceRepaired': False,
              'scope': '仅核对冻结原任务的格式、字数、数据与16项Java行为；语义及实用性另行审阅。'}
    unchanged_write(EVIDENCE / 'artifact-checks.json', json.dumps(output, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'artifacts': len(results), 'realModelCallsAdded': 0}))


if __name__ == '__main__':
    main()
