"""Reuse the bounded harness for six remaining explicitly approved calls; never activate it here."""
from pathlib import Path
import re,json

HERE=Path(__file__).resolve().parent
PARENT=HERE.parent/'me01-candidate41r1-responsibility-projection-2026-10-18'
OUT=HERE.parent/'me01-candidate41r2-responsibility-projection-2026-10-18'

def save(name,text):
    with (OUT/name).open('x',encoding='utf-8',newline='\n') as stream:stream.write(text)

def main():
    names=['request-cases.json','Candidate41EvaluationServer.java','Candidate41ScopePreflight.java','ReserveEvaluationCall.java',
           'start-candidate41r1.ps1','start-live-services.ps1','account-browser.mjs','run-flow.mjs','external-calls.mjs',
           'semantic-json.mjs','check-work-artifacts.py','work-check-spec.json','prepare-reviews.mjs']
    for name in names:
        text=(PARENT/name).read_text(encoding='utf-8').replace('candidate41r1','candidate41r2')
        text=re.sub(r'\b424\b','428',text);text=re.sub(r'\b354\b','358',text)
        if name=='Candidate41EvaluationServer.java':
            start=text.index('        boolean small =');end=text.index('        if (!version.equals',start)
            text=text[:start]+'''        boolean authorized = batch.path("maximumNewCalls").asInt() == 6
                && caps.path("PLANNING").asInt() == 1 && caps.path("PLATFORM_GENERATION").asInt() == 1
                && caps.path("EXECUTION").asInt() == 2 && caps.path("REVIEW").asInt() == 2
                && protocol.path("maximumActualModelCalls").asInt() == 444
                && protocol.path("phaseLimits").path("candidateValidation").asInt() == 364;
'''+text[end:]
            text=text.replace('!(small || full)','!authorized')
        elif name=='Candidate41ScopePreflight.java':
            text=text.replace('.put("maximumNewCalls", 10)','.put("maximumNewCalls", 6)')
            text=text.replace('.put("PLANNING", 3).put("PLATFORM_GENERATION", 3)','.put("PLANNING", 1).put("PLATFORM_GENERATION", 1)')
            start=text.index('        var small =');end=text.index('        batch.put("maximumNewCalls", 11)',start)
            text=text[:start]+text[end:]
            text=text.replace('batch.put("maximumNewCalls", 10)','batch.put("maximumNewCalls", 6)')
            text=text.replace('.put("PLANNING", 3);','.put("PLANNING", 1);')
        elif name=='run-flow.mjs':
            start=text.index('  const small =');end=text.index('  if (manifest.version',start)
            text=text[:start]+'''  const authorized = batch.maximumNewCalls === 6 && protocol.maximumActualModelCalls === 444
    && protocol.phaseLimits.candidateValidation === 364
    && equal(batch.purposeMaximums, { PLANNING: 1, PLATFORM_GENERATION: 1, EXECUTION: 2, REVIEW: 2 });
'''+text[end:]
            text=text.replace('!(small || full)','!authorized')
        elif name=='external-calls.mjs':
            text=text.replace('batch.maximumNewCalls !== 10','batch.maximumNewCalls !== 6')
        save(name.replace('candidate41r1','candidate41r2'),text)
    proposal={'candidate':'candidate41r2','actualBefore':428,'formalBefore':[438,80,358],'formalAfterApproved':[444,80,364],
        'maximumNewCalls':6,'maximumActualAfter':434,'purposeMaximums':{'PLANNING':1,'PLATFORM_GENERATION':1,'EXECUTION':2,'REVIEW':2},
        'authorization':'User approved new 6 plus previously transferred 4. The 4 were already used by 425-428; only the new 6 remain.',
        'case':'TS-IMPLICIT unchanged; actual questions and answers must be bound anew, never reuse a prior Plan ID.',
        'allocationChange':'Earlier four calls closed; latest frozen server responsibility is validated with one Plan and one enhancement. Spare repair calls cannot be assumed.',
        'failurePolicy':'Failed prerequisite stops dependent calls; no quality retries. Existing protocol repair loop unchanged but batch allows only one upstream per platform purpose.',
        'uncovered':['No fresh held-out real task in these six calls.','No fresh user-reserved counterexample Plan in r2; offline counterexamples and 426 remain distinct evidence.']}
    save('live-allocation.json',json.dumps(proposal,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'toolsPrepared':len(names),'maximumNewCalls':6,'upstreamCalls':0}))

if __name__=='__main__':main()
