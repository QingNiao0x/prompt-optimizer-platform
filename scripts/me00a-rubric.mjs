/** Frozen platform review rubric. It never selects a scale for a user's research. */
export const REVIEW_RUBRIC = Object.freeze({
  id: 'me00a-four-dimension-rubric-v1',
  scope: 'PLATFORM_EVALUATION_ONLY',
  dimensions: Object.freeze({
    accuracy: Object.freeze(['目标或关键事实错误', '多项实质事实或适用性错误', '存在局部实质错误', '核心正确，仅有非关键瑕疵', '在已核验范围内与证据一致，适用条件明确']),
    completeness: Object.freeze(['核心交付缺失', '多项重要要求缺失', '主要内容存在但缺重要部分', '必需项齐全，少量非关键细节待完善', '全部适用要求落实且无未授权增项']),
    clarity: Object.freeze(['矛盾导致无法理解', '多处对象或条件不明', '实质歧义需要澄清', '可理解，仅少量冗余', '对象、条件、状态和步骤清楚一致']),
    executability: Object.freeze(['无法执行或存在严重安全问题', '需要重新设计', '仍需补关键步骤', '小幅修改即可使用', '在给定输入和边界内可直接使用']),
  }),
});

/** Reuse these anchors in new reviews; historical reviews stay unchanged. */
export function rubricInstruction() {
  return '平台验收量表版本：' + REVIEW_RUBRIC.id + '。此量表不代表用户研究已选择的量表。\n'
    + Object.entries(REVIEW_RUBRIC.dimensions).map(([key, anchors]) =>
      key + '：' + anchors.map((anchor, index) => (index + 1) + '分=' + anchor).join('；')).join('\n')
    + '\n逐维列出所属作品的原句依据及尚需修改的内容；不凭长度、标题或新增交付加分。'
    + '资料不足或专业判断未核实须记录不确定性。篇幅、格式、事实反转和安全失败单独阻断，不被平均分抵消。'
    + '两次评审独立进行，不查看另一位意见；题目才是独立抽样单位，重复生成不等于新增独立题。';
}

/** An empty or incomplete set of hard checks can never pass. */
export function combineHardGates(checks) {
  const states = Object.values(checks);
  if (states.some(state => !['PASS', 'FAIL', 'OPEN'].includes(state))) throw new Error('Explicit hard gate required');
  return states.includes('FAIL') ? 'FAIL' : !states.length || states.includes('OPEN') ? 'OPEN' : 'PASS';
}

/** Flag gaps without averaging away evidence or deciding scientific truth. */
export function scoreDisagreements(first, second) {
  const ids = Object.keys(first).sort();
  if (ids.join() !== Object.keys(second).sort().join()) throw new Error('Same frozen works required');
  return ids.flatMap(id => Object.keys(REVIEW_RUBRIC.dimensions).flatMap(dimension => {
    const a = first[id]?.[dimension], b = second[id]?.[dimension];
    if (![a, b].every(value => Number.isInteger(value) && value >= 1 && value <= 5)) throw new Error('Complete ordinal scores required');
    return Math.abs(a - b) >= 2 ? [{ work: id, dimension, first: a, second: b, state: 'ADJUDICATION_REQUIRED' }] : [];
  }));
}
