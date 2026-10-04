import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';

/** Check the synthetic corpus structure and provenance without calling any model or application API. */
const input = process.argv[2] ?? 'docs/testing/fixtures/plan-mode-cross-domain-2026-10-04.json';
const corpus = JSON.parse(fs.readFileSync(path.resolve(input), 'utf8'));
const identifiers = new Set();
const supportedExtensions = new Set(['.txt', '.md', '.java', '.ts', '.vue', '.json']);
const assertRelativeMaterialPath = (value) => {
  assert.equal(typeof value, 'string');
  assert(!value.includes('\\'), 'Material paths must use a portable relative path');
  assert(!path.posix.isAbsolute(value) && !/^[A-Za-z]:/.test(value), 'Absolute material path');
  assert(value.split('/').every((part) => part && part !== '.' && part !== '..'), 'Unsafe material path');
  assert(!/(^|\/)(\.env(?:\..*)?|id_rsa|id_ed25519|\.git)(\/|$)/i.test(value), 'Protected material path');
  assert(!/\.(pem|key)$/i.test(value), 'Protected key material');
  assert(!/(^|\/)(application[-.]prod(?:uction)?|production)([./-]|$)/i.test(value), 'Production configuration');
  assert(supportedExtensions.has(path.posix.extname(value)), 'Unsupported corpus material type');
};

for (const item of corpus.cases) {
  assert(!identifiers.has(item.id), 'Duplicate case identifier');
  identifiers.add(item.id);
  assert.equal(typeof item.rawPrompt, 'string');
  assert(['medium', 'long'].includes(item.lengthClass), 'Unknown length class');
  assert.equal(item.rawPrompt.length, item.rawPromptLengthUtf16, 'Stale UTF-16 length');
  const bounds = item.lengthClass === 'medium' ? [600, 1600] : [3500, 6500];
  assert(item.rawPrompt.length >= bounds[0] && item.rawPrompt.length <= bounds[1], 'Length class mismatch');
  assert(item.rawPrompt.length <= 8000, 'Application prompt limit exceeded');
  assert.equal(item.evaluationState, 'NOT_RUN');
  assert.equal(item.status, 'DRAFT_SYNTHETIC_PENDING_PROFESSIONAL_REVIEW');
  const materials = new Map();
  for (const material of item.contextFiles) {
    assertRelativeMaterialPath(material.path);
    assert(!materials.has(material.path), 'Duplicate material path');
    assert.equal(typeof material.content, 'string');
    assert(material.content.trim(), 'Empty material');
    materials.set(material.path, material.content);
  }
  const body = [...materials.values()].join('\n');
  for (const fact of item.knownFacts) {
    assert(item.rawPrompt.includes(fact.claim), 'Known claim missing from prompt');
    assert(body.includes(fact.claim), 'Known claim missing from material');
    for (const source of fact.source) {
      assert(source.startsWith('rawPrompt/') || materials.has(source.split('#')[0]), 'Unknown fact source');
    }
  }
  for (const rule of item.criticalRules) {
    assert(item.rawPrompt.includes(rule.statement), 'Critical rule missing from prompt');
    assert(rule.source.startsWith('rawPrompt/'), 'Unsupported rule source');
  }
  const decisions = new Set();
  for (const decision of item.unknownDecisions) {
    assert(!decisions.has(decision.decisionKey), 'Duplicate unknown decision');
    decisions.add(decision.decisionKey);
    assert(item.rawPrompt.includes(decision.question), 'Unknown question missing from prompt');
    assert.equal(typeof decision.answerCard.answer, 'string');
    assert(decision.answerCard.answer.trim(), 'Missing fixed answer card');
  }
  for (const conflict of item.expectedConflict) {
    assert(conflict.sources.every((source) => source.startsWith('rawPrompt/') || materials.has(source.split('#')[0])));
    assert(conflict.values.length >= 2, 'Conflict requires two sides');
    if (conflict.requiresUserDecision) decisions.add(conflict.decisionKey);
  }
  for (const decision of item.expectedUnresolved) {
    assert(decisions.has(decision.decisionKey), 'Unresolved item has no declared decision');
  }
  if (item.expectedPlanQuestions.maximum === 0) {
    assert.equal(item.unknownDecisions.length, 0, 'Zero-question case includes a required unknown');
    assert(!item.expectedConflict.some((conflict) => conflict.requiresUserDecision));
  }
  for (const key of item.expectedPlanQuestions.requiredDecisionCoverage ?? []) {
    assert(decisions.has(key), 'Required coverage has no decision');
  }
  assert(item.reviewerRoles.length >= 2, 'Missing independent reviewer roles');
}

assert.equal(corpus.cases.length, 24);
assert.equal(new Set(corpus.cases.map((item) => item.domain)).size, 12);
for (const item of corpus.cases) {
  const paired = corpus.cases.find((candidate) => candidate.id === item.pairedCaseId);
  assert(paired && paired.domain === item.domain && paired.lengthClass !== item.lengthClass, 'Invalid medium/long pair');
}
for (const control of corpus.extraControls) {
  assert(!identifiers.has(control.id));
  identifiers.add(control.id);
  assert.equal(control.evaluationState, 'NOT_RUN');
  assert(control.rawPrompt.length > 0 && control.rawPrompt.length <= 8000);
  assert.equal(control.expectedPlanQuestions.maximum, 0);
}
const lengths = Object.fromEntries(['medium', 'long'].map((kind) => {
  const values = corpus.cases.filter((item) => item.lengthClass === kind).map((item) => item.rawPrompt.length);
  return [kind, { count: values.length, minUtf16: Math.min(...values), maxUtf16: Math.max(...values) }];
}));
console.log(JSON.stringify({
  scope: 'STATIC_CORPUS_VALIDATION_ONLY',
  valid: true,
  coreCases: corpus.cases.length,
  domains: 12,
  extraControls: corpus.extraControls.length,
  lengths,
  zeroQuestionCoreCases: corpus.cases.filter((item) => item.expectedPlanQuestions.maximum === 0).length,
  apiCalls: 0,
  modelCalls: 0,
  professionalReview: 'PENDING',
  businessAcceptance: 'NOT_EXECUTED',
}, null, 2));
