"""按相同业务输入验证单文件作品，不向模型提供用例或答案。只运行主代理已查看的合成CSV程序。"""
import ast
import csv
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile


def main():
    source = Path(sys.argv[1]).resolve()
    ast.parse(source.read_text(encoding='utf-8'), feature_version=(3, 11))
    root = Path(sys.argv[2]).resolve()
    root.mkdir(parents=True, exist_ok=True)
    results = []
    header = 'paid_at,channel,status,amount,refund_amount\n'
    # 不按实现函数名检验：取消过滤顺序、精确金额、整批失败和文件保持均是用户明确要求。
    fixtures = [
        ('aggregate', '\ufeff' + header + '2026-01-02, B ,PAID,0.10,0.00\n2026-01-02,B,PAID,0.20,0.00\n'
         '2026-01-01,"x,y",REFUNDED,9.00,3.01\n2026-01-02,b,PAID,5.00,1.25\n'
         '2026-01-01,A,PAID,2.00,0.30\n2026-01-01,A,REFUNDED,1.00,1.00\n',
         [['date', 'channel', 'net_amount'], ['2026-01-01', 'A', '1.70'],
          ['2026-01-01', 'x,y', '5.99'], ['2026-01-02', 'B', '0.30'], ['2026-01-02', 'b', '3.75']], None, None),
        ('cancelled', header + 'bad,,CANCELLED,NaN,invalid\n2024-02-29,A,PAID,1.10,0.10\n',
         [['date', 'channel', 'net_amount'], ['2024-02-29', 'A', '1.00']], None, None),
        ('empty', header, [['date', 'channel', 'net_amount']], None, None),
        ('missing-header', 'paid_at,channel,status,amount\n', None, 'refund_amount', None),
        ('unknown-status', header + '2026-01-01,A,PENDING,1.00,0.00\n', None, 'status', 1),
        ('impossible-date', header + '2026-02-30,A,PAID,1.00,0.00\n', None, 'paid_at', 1),
        ('noncanonical-date', header + '2026-1-02,A,PAID,1.00,0.00\n', None, 'paid_at', 1),
        ('blank-channel', header + '2026-01-01,  ,PAID,1.00,0.00\n', None, 'channel', 1),
        ('negative', header + '2026-01-01,A,PAID,-1.00,0.00\n', None, 'amount', 1),
        ('nan', header + '2026-01-01,A,PAID,NaN,0.00\n', None, 'amount', 1),
        ('infinity', header + '2026-01-01,A,PAID,Infinity,0.00\n', None, 'amount', 1),
        ('precision', header + '2026-01-01,A,PAID,1.001,0.00\n', None, 'amount', 1),
        ('refund-precision', header + '2026-01-01,A,PAID,2.00,1.001\n', None, 'refund_amount', 1),
        ('refund-exceeds', header + '2026-01-01,A,REFUNDED,1.00,2.00\n', None, 'refund_amount', 1),
        ('late-error', header + '2026-01-01,A,PAID,1.00,0.00\nbad,A,PAID,1.00,0.00\n', None, 'paid_at', 2),
    ]
    for name, data, expected, field, row in fixtures:
        case = Path(tempfile.mkdtemp(prefix=name + '-', dir=root))
        input_file, output_file = case / 'input.csv', case / 'output.csv'
        input_file.write_text(data, encoding='utf-8')
        sentinel = b'EXISTING OUTPUT MUST SURVIVE FAILED INPUT\r\n'
        output_file.write_bytes(sentinel)
        process = subprocess.run([sys.executable, '-I', str(source), str(input_file), str(output_file)],
                                 cwd=case, capture_output=True, text=True, timeout=5,
                                 env={key: value for key, value in os.environ.items()
                                      if key in ('SystemRoot', 'WINDIR', 'PATH', 'TEMP', 'TMP')})
        errors = []
        if expected is not None:
            if process.returncode != 0:
                errors.append('successful input rejected')
            else:
                actual = list(csv.reader(io.StringIO(output_file.read_text(encoding='utf-8-sig'))))
                if actual != expected:
                    errors.append('output differs from required totals/order/format')
        else:
            if process.returncode != 2:
                errors.append('invalid input must exit2')
            if field not in process.stderr:
                errors.append('stderr missing field name')
            if row is not None and str(row) not in process.stderr:
                errors.append('stderr missing data-row number')
            if output_file.read_bytes() != sentinel:
                errors.append('failed batch changed existing output')
        results.append({'case': name, 'passed': not errors, 'errors': errors,
                        'exitCode': process.returncode, 'stderr': process.stderr})
    case = Path(tempfile.mkdtemp(prefix='same-file-', dir=root))
    shared = case / 'input.csv'
    data = (header + '2026-01-01,A,PAID,1.00,0.00\n').encode()
    shared.write_bytes(data)
    process = subprocess.run([sys.executable, '-I', str(source), str(shared), str(case / '.' / 'input.csv')],
                             cwd=case, capture_output=True, text=True, timeout=5,
                             env={key: value for key, value in os.environ.items()
                                  if key in ('SystemRoot', 'WINDIR', 'PATH', 'TEMP', 'TMP')})
    results.append({'case': 'same-file', 'passed': process.returncode == 2 and shared.read_bytes() == data,
                    'exitCode': process.returncode, 'stderr': process.stderr})
    print(json.dumps({'checks': len(results), 'passed': sum(item['passed'] for item in results),
                      'results': results, 'realModelCalls': 0, 'measuredPython': sys.version.split()[0],
                      'python311SyntaxChecked': True, 'actualPython311RuntimeClaimed': sys.version_info[:2] == (3, 11)},
                     ensure_ascii=False))


if __name__ == '__main__':
    main()
