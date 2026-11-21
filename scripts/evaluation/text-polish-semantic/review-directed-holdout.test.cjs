const test = require('node:test');
const assert = require('node:assert/strict');
const {template, summarize} = require('./review-directed-holdout.cjs');

const frozen = {sha256: 'frozen-packet', packet: Array.from({length: 8}, (_, i) => ({
    id: `sample-${i}`, role: i < 4 ? `核心待改·用途${i}` : `对照·用途${i}`
}))};
// 映射逐篇变化，检查汇总确实使用每篇的盲评版本映射。
const key = Object.fromEntries(frozen.packet.map(({id}, i) => [id, i % 2
    ? {A: 'revised', B: 'candidate', C: 'baseline'} : {A: 'baseline', B: 'revised', C: 'candidate'}]));

function ballot(reviewer) {
    const review = template(frozen);
    review.reviewer = reviewer;
    for (const [i, item] of review.cases.entries()) {
        const revised = Object.keys(key[item.id]).find(label => key[item.id][label] === 'revised');
        item.resolved = i < 3 ? [revised] : [];
        item.ranking = i < 3 ? [[revised], ['A', 'B', 'C'].filter(label => label !== revised)] : [['A', 'B', 'C']];
        item.readingNote = '合成答卷，仅用于测试汇总';
        for (const finding of Object.values(item.meaning)) finding.status = 'retained';
    }
    return review;
}

test('按当前题目的版本映射汇总两份答卷', () => {
    const summary = summarize(frozen, key, [ballot('一'), ballot('二')]);
    assert.equal(summary.coreResolved, 3);
    assert.equal(summary.wins, 3);
    assert.equal(summary.ties, 5);
    assert.equal(summary.readingThresholdMet, true);
    assert.deepEqual(summary.meaningForReview, []);
});

test('阅读分歧不计为一致胜例，语义疑点保留待核', () => {
    const second = ballot('二');
    second.cases[0].ranking = [['A'], ['B', 'C']];
    second.cases[0].resolved = [];
    second.cases[1].meaning.A = {status: 'disputed', note: '待核角色归属'};
    const summary = summarize(frozen, key, [ballot('一'), second]);
    assert.equal(summary.split, 1);
    assert.equal(summary.readingThresholdMet, false);
    assert.deepEqual(summary.meaningForReview, ['sample-1']);
});

test('拒绝少于两份、重复代号、缺篇或冻结版本不符的答卷', () => {
    assert.throws(() => summarize(frozen, key, [ballot('一')]), /两份/);
    assert.throws(() => summarize(frozen, key, [ballot('一'), ballot('一')]), /代号不能重复/);
    const second = ballot('二');
    second.cases.pop();
    assert.throws(() => summarize(frozen, key, [ballot('一'), second]), /八篇/);
    second.packetSha256 = 'old-packet';
    assert.throws(() => summarize(frozen, key, [ballot('一'), second]), /冻结成稿/);
});
