"""Synthetic checker tests only; no real model/network requests or business-quality certification."""
from pathlib import Path
import importlib.util
import json
import sys

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
OUT = ROOT / "tmp/me04-requests-checker-20261018"
spec = importlib.util.spec_from_file_location("requests_checker", HERE / "check-requests-work.py")
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)

GOOD = '''from __future__ import annotations
import requests
from html.parser import HTMLParser
class TitleParser(HTMLParser):
    def __init__(self):
        super().__init__(); self.inside=False; self.title=''
    def handle_starttag(self, tag, attrs):
        if tag == 'title': self.inside=True
    def handle_endtag(self, tag):
        if tag == 'title': self.inside=False
    def handle_data(self, data):
        if self.inside: self.title += data
def obtain(url):
    response=requests.get(url, timeout=5)
    response.raise_for_status()
    parser=TitleParser(); parser.feed(response.text)
    return parser.title
'''
TEST = '''import unittest
from solution import obtain
class OwnTests(unittest.TestCase):
    def test_nonempty(self):
        self.assertTrue(obtain('https://example.invalid/title'))
'''


def fixture(name, source, test=None, entry=None):
    folder=OUT/name; folder.mkdir(parents=True, exist_ok=True)
    work=folder/'work.md'
    text='```python\n'+source+'```\n'
    files=[{'block':1,'name':'solution.py','role':'implementation'}]
    if test:
        text+='\n```python\n'+test+'```\n'
        files.append({'block':2,'name':'test_solution.py','role':'tests'})
    work.write_bytes(text.replace('\n','\r\n').encode('utf-8'))
    mapping=folder/'mapping.json'
    mapping.write_text(json.dumps({'files':files,'entries':[entry or {'kind':'function','file':'solution.py','function':'obtain'}]}),encoding='utf-8')
    return checker.evaluate(work.resolve(),mapping.resolve(),(folder/'checks').resolve())


def main():
    results={
        'correct':fixture('correct',GOOD,TEST),
        'wrong-title':fixture('wrong-title',GOOD.replace('return parser.title', "return 'wrong title'")),
        'logging-diagnostic':fixture('logging-diagnostic',GOOD.replace('response=requests.get', 'print(url)\n    response=requests.get')),
        'script':fixture('script',GOOD+"import sys\nprint('网页标题：'+obtain('https://example.invalid/title'))\nsys.exit(0)\n",entry={'kind':'script','file':'solution.py','stdoutTitlePrefix':'网页标题：'}),
        'syntax-failure':fixture('syntax-failure',GOOD+'def broken(:\n')}
    checks=[
        ('actual_function_and_authored_tests_pass',results['correct']['originalTaskMechanicalGate']=='PASS' and results['correct']['authoredTests'][0]['state']=='PASS'),
        ('wrong_title_fails',results['wrong-title']['originalTaskMechanicalGate']=='FAIL'),
        ('privacy_not_silently_added_to_original_task',results['logging-diagnostic']['originalTaskMechanicalGate']=='PASS' and any(row.get('syntheticCredentialDiagnostic')=='ECHO_OBSERVED' for row in results['logging-diagnostic']['diagnostics'])),
        ('original_script_and_labelled_output_supported',results['script']['originalTaskMechanicalGate']=='PASS'),
        ('fixed_script_does_not_fake_canary_url_input',all(row.get('syntheticCredentialDiagnostic')!='NO_ECHO_IN_THIS_CASE' for row in results['script']['diagnostics'] if 'syntheticCredentialDiagnostic' in row)),
        ('syntax_failure_not_repaired',results['syntax-failure']['syntax']['status']=='FAIL' and results['syntax-failure']['originalTaskMechanicalGate']=='FAIL'),
        ('review_report_gate_is_present',results['correct']['mechanicalGate']=='PASS' and results['wrong-title']['mechanicalGate']=='FAIL')]
    report={'syntheticFixtures':len(results),'checks':[{'name':name,'passed':bool(value)} for name,value in checks],
            'passed':sum(bool(value) for _,value in checks),'failed':sum(not value for _,value in checks),
            'externalModelCalls':0,'actualNetworkRequests':0,'businessAcceptance':False}
    (OUT/'verification.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(report))
    if report['failed']:sys.exit(1)


if __name__=='__main__': main()
