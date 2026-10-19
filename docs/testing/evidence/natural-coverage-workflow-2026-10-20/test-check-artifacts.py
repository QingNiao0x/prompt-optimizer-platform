"""合成正反例验证评测工具；不调用模型或修补实际作品。"""
import importlib.util
import json
from pathlib import Path
import sys

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('artifact_checker', HERE / 'check-artifacts.py')
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)
expected = {'members': [{'name': '甲', 'date': None}, {'name': '乙', 'date': '2026-10-25'}], 'pending': ['丙']}
reordered = {'members': list(reversed(expected['members'])), 'pending': ['丙']}
correct = '''import java.math.BigDecimal;
import java.math.RoundingMode;
public class PriceCalculator {
  public static BigDecimal payable(BigDecimal amount, boolean member) {
    if(amount==null || amount.signum()<0) throw new IllegalArgumentException();
    BigDecimal result=member && amount.compareTo(new BigDecimal("100"))>=0 ? amount.multiply(new BigDecimal("0.9")) : amount;
    return result.setScale(2,RoundingMode.HALF_UP);
  }
}
'''
positive = checker.java_check('SELFTEST_CORRECT', correct)
missing = checker.java_check('SELFTEST_MISSING_IMPORT', correct.replace('import java.math.BigDecimal;\n', ''))
boundary = checker.java_check('SELFTEST_WRONG_BOUNDARY', correct.replace('>=0 ?', '>0 ?'))
checks = {
    'json_order_not_an_extra_requirement': checker.check_json(json.dumps(reordered), expected)['exactDataAndFieldsPassed'],
    'json_markdown_rejected': not checker.check_json('```json\n' + json.dumps(expected) + '\n```', expected)['parsePassed'],
    'json_unknown_date_not_invented': not checker.check_json(json.dumps({'members': [{'name': '甲', 'date': '明天'}], 'pending': ['丙']}), expected)['exactDataAndFieldsPassed'],
    'correct_java_and_independent_cases_pass': positive['compilePassed'] and positive['behaviorPassed'],
    'missing_import_not_repaired': not missing['compilePassed'] and missing['behaviorTests'] == 0,
    'changed_boundary_is_caught': boundary['compilePassed'] and not boundary['behaviorPassed'],
}
report = {'checks': checks, 'passed': sum(checks.values()), 'failed': sum(not value for value in checks.values()), 'realModelCalls': 0}
checker.unchanged_write(HERE / 'tool-verification.json', json.dumps(report, ensure_ascii=False, indent=2) + '\n')
print(json.dumps(report))
if report['failed']:
    raise SystemExit(1)
