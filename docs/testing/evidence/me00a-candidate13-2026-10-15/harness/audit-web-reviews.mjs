import { readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { parseStrictJson } from '../../../scripts/me00a-work-checks.mjs';
const live = resolve('tmp/me00a-20261012/candidate13-real');
const { mapping, inputMapping, pairs } = JSON.parse(await readFile(resolve(live, 'review-mapping.json'), 'utf8'));
const works = Object.fromEntries(await Promise.all(Object.values(mapping).map(async name => [name, await readFile(resolve(live, 'works', `${name}.md`), 'utf8')])));
const inputs = Object.fromEntries(await Promise.all(['raw', 'direct', 'plan'].map(async arm => [arm, await readFile(resolve(live, arm, 'execution-input.md'), 'utf8')])));
const records = [];
for (const vendor of ['doubao', 'deepseek']) {
  const text = await readFile(resolve(live, 'reviews', `${vendor}-review.md`), 'utf8');
  let review, valid = false, failure;
  try {
    review = parseStrictJson(text);
    const dimensions = ['accuracy', 'completeness', 'clarity', 'executability'];
    if (review.reviewerType !== 'AI_ASSISTED' || Object.keys(review.scores ?? {}).sort().join() !== Object.keys(mapping).sort().join()
      || Object.keys(mapping).some(id => dimensions.some(key => !Number.isInteger(review.scores[id]?.[key]) || review.scores[id][key] < 1 || review.scores[id][key] > 5))
      || !Array.isArray(review.issues) || review.issues.some(issue => !mapping[issue.work] || !issue.evidence || !issue.reason)
      || !Array.isArray(review.pairs) || review.pairs.length !== 6
      || pairs.some(pair => review.pairs.filter(value => value.left === pair.left && value.right === pair.right
        && ['LEFT_WIN', 'RIGHT_WIN', 'TIE', 'BOTH_FAIL'].includes(value.verdict) && value.reason).length !== 1)) throw new Error('Review schema incomplete');
    valid = true;
  } catch (error) { failure = error.message; }
  const evidenceChecks = (review?.issues ?? []).map((issue, index) => {
    const name = mapping[issue.work], arm = name?.split('-')[1];
    return { index, work: issue.work, exactQuoteInWork: !!name && works[name].includes(issue.evidence),
      exactQuoteInAssociatedInput: !!arm && inputs[arm].includes(issue.evidence),
      quoteFoundOnlyInOtherInput: !!arm && !works[name].includes(issue.evidence) && !inputs[arm].includes(issue.evidence)
        && Object.entries(inputs).some(([otherArm, input]) => otherArm !== arm && input.includes(issue.evidence)),
      noAutomaticSemanticVerdict: true };
  });
  const record = { vendor, reviewerType: 'AI_ASSISTED_NOT_HUMAN_EXPERT', strictJsonAndSchemaValid: valid, failure,
    review, evidenceChecks, reviewTextUnchanged: true, noQualityRetry: true };
  await writeFile(resolve(live, 'reviews', `${vendor}-review-audit.json`), JSON.stringify(record, null, 2), { flag: 'wx' });
  records.push(record);
}
const comparison = { mapping, inputMapping, reviewers: records.map(({vendor,strictJsonAndSchemaValid,evidenceChecks}) => ({vendor,strictJsonAndSchemaValid,evidenceChecks})),
  sameAnonymousPack: true, independentNewChatsDifferentVendors: true, originalReviewsUnchanged: true,
  pairs: pairs.map(pair => ({ left: mapping[pair.left], right: mapping[pair.right],
    originalVerdicts: records.map(record => ({ vendor: record.vendor, ...record.review.pairs.find(value => value.left === pair.left && value.right === pair.right) })),
    verdictDisagreement: records[0].review.pairs.find(value => value.left === pair.left && value.right === pair.right).verdict !== records[1].review.pairs.find(value => value.left === pair.left && value.right === pair.right).verdict })),
  offlineReviewLimitations: [
    { vendor: 'doubao', work: 'deepseek-direct versus deepseek-raw', issue: 'Pair X/U says LEFT_WIN but its reason explicitly says U is better; retain both, do not silently flip verdict.' },
    { vendor: 'doubao', work: 'deepseek-plan', issue: 'Claim that the study has no human participants is unsupported; user delegated a proposed design and did not ban participants. Actual within/between-factor contradiction must be checked separately.' },
    { vendor: 'deepseek', work: 'deepseek-raw', issue: 'Two length findings quote the enhanced-input length guidance as if it were raw-work text; independently verified length failure stands, quoted provenance does not.' },
    { vendor: 'deepseek', work: 'multiple pairs', issue: 'Reviews rank relatively better output despite material failure on both sides; their rankings do not close hard or severe gates.' },
    { vendor: 'both', work: 'deepseek-direct', issue: 'Both give high ratings while the scale remains a future construction plan; ratings are not a delivered primary scale.' }
  ] };
comparison.disputedPairs = comparison.pairs.filter(pair => pair.verdictDisagreement).length;
await writeFile(resolve(live, 'review-comparison.json'), JSON.stringify(comparison, null, 2), { flag: 'wx' });
console.log(JSON.stringify({ reviewers: records.map(r => ({ vendor:r.vendor,strictJsonAndSchemaValid:r.strictJsonAndSchemaValid,nonLiteralEvidence:r.evidenceChecks.filter(e=>!e.exactQuoteInWork&&!e.exactQuoteInAssociatedInput).length })), disputedPairs:comparison.disputedPairs }));
