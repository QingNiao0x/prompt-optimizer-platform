/** Deterministic checks of saved AI reviews; never edits a review, supplies missing execution information or calls a model. */
import { createHash } from 'node:crypto';
import { parseStrictJson } from './me00a-work-checks.mjs';
const hash = text => createHash('sha256').update(text, 'utf8').digest('hex');
const dimensions = ['accuracy', 'completeness', 'clarity', 'executability'];
const verdicts = new Set(['LEFT_WIN', 'RIGHT_WIN', 'TIE', 'BOTH_FAIL']);
const quoteCheckVersion = 'literal-or-one-outer-pair-v2';

/** Remove exactly one balanced presentation wrapper; never clean inner text or accept a splice. */
function quotedText(evidence) {
  const pairs = new Map([['“','”'], ['「','」'], ['『','』'], ['"','"'], ["'","'"]]);
  return evidence.length > 2 && pairs.get(evidence[0]) === evidence.at(-1) ? evidence.slice(1,-1) : evidence;
}

/** Bind evidence to its own work/input, preserve original rankings and keep independent gates separate from relative scores. */
export function auditIndependentReview(text, { works, pairs, gates = {} }) {
  const ids = Object.keys(works ?? {});
  if (!ids.length || !Array.isArray(pairs) || pairs.some(pair => !works[pair.left] || !works[pair.right] || pair.left === pair.right)
    || ids.some(id => typeof works[id].text !== 'string' || typeof works[id].input !== 'string'
      || hash(works[id].input) !== works[id].inputSha256)) throw new Error('Frozen work/input bindings required');
  let review;
  const errors = [];
  try {
    review = parseStrictJson(text);
    if (review.reviewerType !== 'AI_ASSISTED' || Object.keys(review.scores ?? {}).sort().join() !== ids.sort().join()
      || ids.some(id => dimensions.some(key => !Number.isInteger(review.scores[id]?.[key]) || review.scores[id][key] < 1 || review.scores[id][key] > 5))
      || !Array.isArray(review.issues) || review.issues.some(issue => !works[issue.work] || !issue.evidence || !issue.reason
        || !['INPUT', 'WORK', 'MIXED', 'UNDETERMINED'].includes(issue.origin) || !['SEVERE','MAJOR','MINOR'].includes(issue.severity))
      || !Array.isArray(review.pairs) || review.pairs.length !== pairs.length
      || pairs.some(pair => review.pairs.filter(item => item.left === pair.left && item.right === pair.right && verdicts.has(item.verdict) && item.reason).length !== 1))
      throw new Error('Review schema incomplete or inconsistent');
  } catch (error) {
    return { rawReviewSha256: hash(text), strictJsonAndSchemaValid: false, deterministicChecksPassed: false,
      errors: [{ code: 'REVIEW_PROTOCOL_INVALID', reason: error.message }], originalReviewTextUnchanged: true, semanticVerdict: 'UNVERIFIED' };
  }
  const evidenceChecks = review.issues.map((issue, index) => {
    const own = works[issue.work];
    const exactWork = own.text.includes(issue.evidence), exactInput = own.input.includes(issue.evidence);
    // Exact text wins; the derived match only explains a single complete outer quote pair.
    const matchedText = exactWork || exactInput ? issue.evidence : quotedText(issue.evidence);
    const inWork = own.text.includes(matchedText), inInput = own.input.includes(matchedText);
    const onlyElsewhere = !inWork && !inInput && ids.some(id => id !== issue.work
      && (works[id].text.includes(matchedText) || works[id].input.includes(matchedText)));
    if (!inWork && !inInput) errors.push({ code: onlyElsewhere ? 'EVIDENCE_WRONG_SOURCE' : 'EVIDENCE_NOT_LITERAL', issueIndex: index, work: issue.work });
    else if (issue.origin === 'WORK' && !inWork || issue.origin === 'INPUT' && !inInput)
      errors.push({ code: 'EVIDENCE_ORIGIN_MISMATCH', issueIndex: index, work: issue.work });
    return { issueIndex: index, work: issue.work, exactQuoteInWork: exactWork, exactQuoteInAssociatedInput: exactInput,
      matchVersion: quoteCheckVersion, originalEvidence: issue.evidence, matchedText,
      matchMode: matchedText === issue.evidence ? 'EXACT' : 'ONE_OUTER_PAIR',
      positionUnit: 'UTF16_CODE_UNIT',
      workPosition: own.text.indexOf(matchedText), inputPosition: own.input.indexOf(matchedText),
      quoteOnlyInOtherSource: onlyElsewhere };
  });
  const comparison = pairs.map(pair => {
    const original = review.pairs.find(item => item.left === pair.left && item.right === pair.right);
    const winner = explicitWinner(original.reason, pair);
    if (winner && (original.verdict === 'LEFT_WIN' && winner === pair.right || original.verdict === 'RIGHT_WIN' && winner === pair.left))
      errors.push({ code: 'VERDICT_REASON_DIRECTION_CONTRADICTION', left: pair.left, right: pair.right, explicitReasonWinner: winner });
    const leftGate = gates[pair.left] ?? 'OPEN', rightGate = gates[pair.right] ?? 'OPEN';
    if (![leftGate, rightGate].every(state => ['PASS','FAIL','OPEN'].includes(state))) throw new Error('Explicit gate state required');
    return { ...pair, originalVerdict: original.verdict, originalReason: original.reason,
      literalReasonWinner: winner, independentGate: leftGate === 'FAIL' && rightGate === 'FAIL' ? 'BOTH_FAIL'
        : leftGate === 'PASS' && rightGate === 'PASS' ? 'BOTH_PASS_RELATIVE_QUALITY_REQUIRES_REVIEW' : 'NOT_BOTH_PASSED',
      leftGate, rightGate, rankingsDoNotOverrideHardOrSevereFailure: true };
  });
  return { rawReviewSha256: hash(text), strictJsonAndSchemaValid: true, deterministicChecksPassed: errors.length === 0,
    errors, evidenceChecks, comparisons: comparison, originalReview: review, originalReviewTextUnchanged: true,
    semanticVerdict: 'REQUIRES_EVIDENCE_REVIEW', automaticSemanticJudge: false, modelCalls: 0 };
}

/** Only an unnegated complete clause such as “U优于X” provides a literal direction check; ordinary reasoning remains unjudged. */
function explicitWinner(reason, pair) {
  const escape = value => value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const left = escape(pair.left), right = escape(pair.right);
  const patterns = [
    [pair.left, new RegExp(`(?:^|[。；;，,])\\s*(?:因此|所以|故)?\\s*${left}优于${right}(?=[。；;，,]|$)`, 'u')],
    [pair.right, new RegExp(`(?:^|[。；;，,])\\s*(?:因此|所以|故)?\\s*${right}优于${left}(?=[。；;，,]|$)`, 'u')],
  ];
  const matches = patterns.filter(([,pattern]) => pattern.test(reason));
  return matches.length === 1 ? matches[0][0] : null;
}
