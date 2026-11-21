const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');

const [root] = process.argv.slice(2);
const read = (...parts) => JSON.parse(fs.readFileSync(path.join(root, ...parts), 'utf8'));
const save = (file, value) => fs.writeFileSync(path.join(root, file), JSON.stringify(value, null, 2) + '\n', {flag: 'wx'});
assert.equal(read('fresh-initial', 'ledger.json').closed, true);
assert.equal(read('fresh-revision', 'ledger.json').closed, true);
const key = {};
const packet = read('revision-fresh-samples.json').map(sample => {
    const baseline = read('fresh-initial', sample.id + '-baseline.json');
    const candidate = read('fresh-initial', sample.id + '-candidate.json');
    const revised = read('fresh-revision', sample.id + '.json');
    for (const result of [baseline, candidate, revised]) assert.equal(result.status, 'COMPLETED');
    assert.deepEqual(baseline.job.input, candidate.job.input);
    assert.deepEqual(baseline.executionMetadata.parameters, candidate.executionMetadata.parameters);
    const works = {baseline: baseline.polishedText, candidate: candidate.polishedText, revised: revised.revised};
    const arms = Object.keys(works);
    for (let i = arms.length - 1; i > 0; i--) {
        const j = crypto.randomInt(i + 1);
        [arms[i], arms[j]] = [arms[j], arms[i]];
    }
    key[sample.id] = Object.fromEntries(arms.map((arm, i) => [String.fromCharCode(65 + i), arm]));
    return {
        id: sample.id,
        role: sample.role,
        source: sample.source,
        requirements: baseline.job.input.requirements,
        drafts: Object.fromEntries(Object.entries(key[sample.id]).map(([label, arm]) => [label, works[arm]]))
    };
});
save('fresh-blind-packet.json', packet);
save('fresh-blind-key.json', key);
