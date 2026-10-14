/** Offline hard-constraint checks for saved downstream works; never edits a prompt, work or AI review. */
export const BODY_HAN_POLICY = Object.freeze({
  id: 'me00a-q05-body-han-v1', unit: 'Unicode Script=Han', lower: 800, upper: 1200,
  exclusions: ['Markdown headings', 'leading bold subheading labels', 'explicit appendix'],
  alternative: 'all text including headings, excluding the same explicit appendix',
});

/** Reuse candidate-eight counting scope and expose its alternative instead of choosing whichever passes. */
export function checkResearchLength(work, policy = BODY_HAN_POLICY) {
  if (policy.id !== BODY_HAN_POLICY.id) throw new Error('Unknown frozen counting policy');
  const bodyAndHeadings = work.split(/^\s*(?:#{1,6}\s*)?(?:\*\*)?附(?:表|录)(?:[（(][^\n）)]{0,20}[）)])?(?:\*\*)?\s*$/m)[0];
  const body = bodyAndHeadings.split(/\r?\n/)
    .filter(line => !/^\s*#{1,6}\s/.test(line))
    .map(line => line.replace(/^\s*(?:\d+[.)、]\s*)?\*\*[^*\n]+\*\*\s*[：:]?\s*/, ''))
    .join('\n');
  const count = text => [...text.matchAll(/\p{Script=Han}/gu)].length;
  const bodyHan = count(body), withHeadingsHan = count(bodyAndHeadings);
  return { policyId: policy.id, bodyHan, withHeadingsHan, pass: bodyHan >= policy.lower && bodyHan <= policy.upper };
}

/** Parse the complete response; fenced extracts, trailing prose and duplicate keys do not satisfy strict JSON. */
export function parseStrictJson(work) {
  const value = JSON.parse(work);
  const scopes = [];
  for (let i = 0; i < work.length; i++) {
    const character = work[i];
    if (character === '{') scopes.push(new Set());
    else if (character === '[') scopes.push(null);
    else if (character === '}' || character === ']') scopes.pop();
    else if (character === '"') {
      const start = i++;
      for (; i < work.length; i++) {
        if (work[i] === '\\') i++;
        else if (work[i] === '"') break;
      }
      let next = i + 1;
      while (/\s/.test(work[next] ?? '') && next < work.length) next++;
      if (work[next] === ':' && scopes.at(-1) instanceof Set) {
        const key = JSON.parse(work.slice(start, i + 1));
        if (scopes.at(-1).has(key)) throw new Error('Duplicate JSON key');
        scopes.at(-1).add(key);
      }
    }
  }
  return value;
}

/** Fixed JSON fixture checks preserve exact given input; this does not prove arbitrary prose is free of hallucinations. */
export function checkGivenTrimJson(work, includeExamples) {
  const value = parseStrictJson(work);
  if (!value || Array.isArray(value) || typeof value !== 'object') return false;
  const keys = Object.keys(value).sort().join(',');
  if (keys !== (includeExamples ? 'examples,result' : 'result') || value.result !== 'Alpha') return false;
  return !includeExamples || Array.isArray(value.examples) && value.examples.length === 1
    && Object.keys(value.examples[0]).sort().join(',') === 'input,output'
    && value.examples[0].input === '  Alpha  ' && value.examples[0].output === 'Alpha';
}
