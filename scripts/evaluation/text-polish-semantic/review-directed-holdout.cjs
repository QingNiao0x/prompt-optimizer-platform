const fs = require('node:fs');
const path = require('node:path');
const {createHash} = require('node:crypto');
const assert = require('node:assert/strict');

const labels = ['A', 'B', 'C'];
const statuses = ['retained', 'disputed', 'material'];
const read = file => JSON.parse(fs.readFileSync(file, 'utf8'));
const sameSet = (actual, expected) => Array.isArray(actual)
    && actual.length === expected.length && expected.every(value => actual.includes(value));

// 答卷绑定匿名成稿的顺序与正文。生成空表时不读取版本映射。
function loadPacket(root) {
    const content = fs.readFileSync(path.join(root, 'fresh-blind-packet.json'), 'utf8').replace(/\r\n/g, '\n');
    const packet = JSON.parse(content);
    assert.equal(packet.length, 8, '题集应有八篇');
    assert.equal(new Set(packet.map(item => item.id)).size, packet.length, '题集 ID 不能重复');
    return {packet, sha256: createHash('sha256').update(content).digest('hex')};
}

function template({packet, sha256}) {
    return {
        reviewer: '',
        packetSha256: sha256,
        cases: packet.map(({id, role}) => ({id, role, resolved: [], ranking: [], readingNote: '',
            meaning: Object.fromEntries(labels.map(label => [label, {status: '', note: ''}]))}))
    };
}

// 每份答卷须完整。排序按从好到差分组，并列的版本放在同一组。
function validateReview(review, {packet, sha256}) {
    assert.equal(review.packetSha256, sha256, '答卷与冻结成稿不一致');
    assert.ok(typeof review.reviewer === 'string' && review.reviewer.trim(), '缺少评阅者代号');
    assert.ok(Array.isArray(review.cases), '缺少逐篇答卷');
    assert.ok(sameSet(review.cases.map(item => item.id), packet.map(item => item.id)), '答卷须覆盖全部八篇且不得重复');
    for (const item of review.cases) {
        assert.ok(Array.isArray(item.resolved) && item.resolved.every(label => labels.includes(label))
            && new Set(item.resolved).size === item.resolved.length, `${item.id} 的改善版本无效`);
        assert.ok(Array.isArray(item.ranking) && item.ranking.every(group => Array.isArray(group) && group.length)
            && sameSet(item.ranking.flat(), labels), `${item.id} 的排序须覆盖 A/B/C 各一次`);
        assert.ok(typeof item.readingNote === 'string' && item.readingNote.trim(), `${item.id} 缺少阅读依据`);
        assert.ok(item.meaning && sameSet(Object.keys(item.meaning), labels), `${item.id} 缺少逐版本语义标记`);
        for (const label of labels) {
            const finding = item.meaning[label];
            assert.ok(finding && statuses.includes(finding.status), `${item.id}/${label} 的语义标记无效`);
            if (finding.status !== 'retained') {
                assert.ok(typeof finding.note === 'string' && finding.note.trim(), `${item.id}/${label} 的语义疑点须附依据`);
            }
        }
    }
    return new Map(review.cases.map(item => [item.id, item]));
}

/** 只汇总两人均确认的收益，分歧单列。是否采用由业务负责人结合原句复核。 */
function summarize(frozen, key, reviews) {
    assert.equal(reviews.length, 2, '需要两份独立答卷');
    const [first, second] = reviews.map(review => validateReview(review, frozen));
    assert.notEqual(reviews[0].reviewer.trim().toLowerCase(), reviews[1].reviewer.trim().toLowerCase(), '评阅者代号不能重复');
    const rows = frozen.packet.map(({id, role}) => {
        const mapping = key[id];
        assert.ok(mapping && sameSet(Object.keys(mapping), labels)
            && sameSet(Object.values(mapping), ['baseline', 'candidate', 'revised']), `${id} 的版本映射无效`);
        const labelFor = arm => labels.find(label => mapping[label] === arm);
        const revised = labelFor('revised');
        const baseline = labelFor('baseline');
        const results = [first.get(id), second.get(id)].map(item => {
            const rank = label => item.ranking.findIndex(group => group.includes(label));
            return {comparison: Math.sign(rank(baseline) - rank(revised)),
                resolved: item.resolved.includes(revised), meaning: item.meaning[revised].status};
        });
        const [a, b] = results;
        return {id, role, core: role.startsWith('核心待改·'), resolvedByBoth: a.resolved && b.resolved,
            comparison: a.comparison === b.comparison ? ({'1': 'win', '0': 'tie', '-1': 'loss'})[a.comparison] : 'split',
            meaning: results.map(item => item.meaning)};
    });
    const coreResolved = rows.filter(row => row.core && row.resolvedByBoth).length;
    const wins = rows.filter(row => row.comparison === 'win');
    const losses = rows.filter(row => row.comparison === 'loss').length;
    const split = rows.filter(row => row.comparison === 'split').length;
    const winningUses = new Set(wins.map(row => row.role.split('·').at(-1))).size;
    const meaningForReview = rows.filter(row => row.meaning.some(status => status !== 'retained')).map(row => row.id);
    assert.equal(rows.filter(row => row.core).length, 4, '题集应有四篇核心待改稿');
    return {
        coreResolved, wins: wins.length, losses, ties: rows.length - wins.length - losses - split, split, winningUses,
        meaningForReview, readingThresholdMet: coreResolved >= 3 && winningUses >= 2 && wins.length > losses,
        cases: rows
    };
}

if (require.main === module) {
    const [command, root, ...files] = process.argv.slice(2);
    const frozen = loadPacket(root);
    if (command === 'template' && files.length === 0) {
        console.log(JSON.stringify(template(frozen), null, 2));
    } else if (command === 'summarize' && files.length === 2) {
        const key = read(path.join(root, 'fresh-blind-key.json'));
        console.log(JSON.stringify(summarize(frozen, key, files.map(read)), null, 2));
    } else {
        throw new Error('用法：template 证据目录，或 summarize 证据目录 答卷一.json 答卷二.json');
    }
}

module.exports = {loadPacket, template, summarize};
