// Offline layer correspondence; this creates no model calls and never alters works.
import { readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
const root=resolve('docs/testing/evidence/task-constraint-boundary-2026-10-16');
const load=async p=>JSON.parse(await readFile(resolve(root,p),'utf8'));
const trace=await load('real/traces/254.json');
const model=JSON.parse(trace.response.choices[0].message.content);
const result=(await load('real/mortality-2015-2025/platform.json')).body.data;
const modelSection=type=>model.sections.find(s=>s.type===type).content;
const finalSection=type=>result.sections.find(s=>s.type===type).content;
const rule='明确数据来源、研究对象、指标定义和统计口径，无法核实的数据与引用不得编造。';
const original=rule.replace('，','；');
if(!modelSection('CONSTRAINTS').includes(original)||!finalSection('CONSTRAINTS').includes(original)
 ||finalSection('CONSTRAINTS').includes(rule)||!result.appliedConstraints.includes(rule))
 throw new Error('Specific punctuation dedup/source preservation gate failed');
const userMessage=trace.request.messages.find(m=>m.role==='user').content;
const input=JSON.parse(userMessage.slice(userMessage.indexOf('{')));
const requests=(await load('real/requests-title/recovered-after-harness/platform.json')).body.data;
const directReview=await load('real/review-interpretation.json');
const report={specificPunctuationFix:{passed:true,rawModelContainsSemicolonRule:true,
  copyContainsOnlySemicolonVariant:true,canonicalSourcePreserved:true},
  questionSources:{serverCandidates:input.ambiguities,rawModel:model.ambiguities,final:result.ambiguities,
    note:'First two were supplied as server candidates; all five are in the raw model response. Equivalent punctuation/spacing is not a new question.'},
  remainingIssues:[
    {id:'RESEARCH_STATE_REPETITION',firstSeen:'ENHANCEMENT_MODEL',platformContribution:'Pending list appended while model background/constraint status is already repeated',
      downstream:'114 occurrences of the missing-material phrase, 22 blank annual rows',attribution:'MIXED',closed:false},
    {id:'TABLE_CONDITION_AMBIGUITY',modelEvidence:modelSection('OUTPUT').split('\n').find(line=>line.includes('用户明确要求的表格')),
      templateGuidance:input.template.outputGuidance,platformPreservesModelLine:true,
      downstream:'Treats yearly/sex YLL tables as explicitly requested although the raw prompt did not request tables',attribution:'ENHANCEMENT_AMBIGUITY_AND_DOWNSTREAM_RULE_VIOLATION',closed:false},
    {id:'PROFESSIONAL_QUESTION_BOUNDARY',assessment:model.assessment.gaps.filter(gap=>gap.kind==='PROFESSIONAL_OPTION'),
      status:'Methods may be proposed under conditions; region, actual denominator/reference table and decomposition target must not be fabricated or silently confirmed',
      attribution:'MODEL_CLASSIFICATION_AND_PLATFORM_QUESTION_POLICY_REQUIRE_FURTHER_SCOPED_REPLAY',closed:false},
    {id:'IMPLICIT_SOFTWARE_INTENT',requestsTemplate:requests.templateCode,visibleConstraints:requests.appliedConstraints,
      status:'Raw requests task resolves to GENERAL although generated task explicitly delivers Python code; investigate intent/rule applicability without treating server-side filtering as disabled',
      attribution:'PLATFORM_INTENT_BOUNDARY_OPEN',closed:false},
    {id:'ORIGINAL_502_ROOT_CAUSE',status:'Original failure response unavailable; fresh identical-raw-input request succeeds once, not proof of stable recovery or unique historical cause',closed:false},
    {id:'REVIEWER_OMISSIONS',status:'Both prefer requests enhanced and research raw, but miss wrong journal citation and add an expectation of code/templates absent from raw requirements',
      attribution:'EVALUATION_LIMITATION',closed:false}
  ],
  aiReviews:directReview.reviews,
  localAcceptance:{requests:'CORE_CODE_BEHAVIOUR_PASSED_FOR_THIS_SAMPLE',research:'FAILED_SUBSTANTIVE_DELIVERY_GATE',
    hardGateDecision:'No average-score override; preference does not certify raw research accuracy',overallG1toG8:'OPEN'},
  sourceChecks:[
    {topic:'Arriaga paper journal and target metric',source:'https://pubmed.ncbi.nlm.nih.gov/6714492/',
      verified:'1984 paper is in Demography; abstract explains life expectancy changes through age-group mortality changes',
      limitation:'Abstract does not verify every formula, software function or alternative mortality-rate decomposition'},
    {topic:'WHO YLL loss function',source:'https://www.who.int/docs/default-source/gho-documents/global-health-estimates/ghe2019_daly-methods.pdf',
      verified:'WHO describes age/sex death counts multiplied by a standard loss function. Fixed age-cutoff calculation is a different specification and cannot silently substitute a selected reference life table',
      limitation:'No actual reference table, denominator or numerical result was chosen or calculated for the user'}
  ],
  strategy:{adopted:'Narrow punctuation coverage only',promptChanges:0,extraRuntimeValidationCalls:0,qualityRetries:0,
    next:'Replay known model/state/table and question-scope cases offline before a separate minimal candidate; preserve object/parameter distinctions and normal variants',
    doNotAdopt:['More prohibitions based on one downstream failure','Require unrequested code/examples','Treat unavailable empirical results as a requirement to invent data']}};
await writeFile(resolve(root,'real/layered-attribution.json'),JSON.stringify(report,null,2),{flag:'wx'});
console.log('Specific live rule coverage passed; research quality gate remains open');
