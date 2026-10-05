import test from 'node:test';
import assert from 'node:assert/strict';
import { unboundMaintenanceTableClaims, hasUnboundMaintenanceFixture } from './verify-source-object-works.mjs';

test('表尾待核提示不取消具体A/B格中的维修归属', () => {
  const text = '| 属性 | 草稿A | 草稿B | 状态 |\n|---|---|---|---|\n| 维修范围 | 日常维护 | 全部设施故障费用 | 版本对应待核实 |\n\n版本对应待核实。';
  assert.equal(unboundMaintenanceTableClaims(text).length, 2);
});
test('具体格保持未知并另列匿名内容时不报错，押金属性独立', () => {
  const text = '| 属性 | 草稿A | 草稿B | 已知内容 |\n|---|---|---|---|\n| 押金 | 两个月 | 三个月 | 已知 |\n| 维修 | 版本对应待核实 | 版本对应待核实 | 一版日常维护；另一版全部设施故障费用 |';
  assert.deepEqual(unboundMaintenanceTableClaims(text), []);
});
test('匿名一版/另一版表和同格明确未绑定不当作版本证据', () => {
  assert.deepEqual(unboundMaintenanceTableClaims('| 属性 | 一版 | 另一版 |\n|---|---|---|\n| 维修 | 日常维护 | 全部设施故障费用 |'), []);
  assert.deepEqual(unboundMaintenanceTableClaims('| 属性 | 草稿A | 草稿B |\n|---|---|---|\n| 维修 | 一版日常维护（版本对应待核实） | 另一版全部设施故障费用（版本对应待核实） |'), []);
});
test('纵向A/B行同样核对属性，押金已知行不触发', () => {
  assert.equal(unboundMaintenanceTableClaims('| 版本 | 内容 |\n|---|---|\n| 草稿A | 日常维护 |\n| 草稿B | 全部设施故障费用 |').length, 2);
  assert.deepEqual(unboundMaintenanceTableClaims('| 版本 | 内容 |\n|---|---|\n| 草稿A | 押金两个月 |\n| 草稿B | 押金三个月 |'), []);
});
test('检查限定到冻结合成题，不扩大为通用行业规则', () => {
  const files = [{path: 'materials/legal_memo/current-brief.md', content: '维修条款一版写承租方承担日常维护，另一版新增全部设施故障费用'}];
  assert.equal(hasUnboundMaintenanceFixture({id: 'CD-10-L', files}), true);
  assert.equal(hasUnboundMaintenanceFixture({id: 'OTHER', files}), false);
  assert.equal(hasUnboundMaintenanceFixture({id: 'CD-10-L', files: [{...files[0], content: '草稿A维修日常维护；草稿B全部设施故障费用'}]}), false);
});

test('同一表内的对应问题、A/B关系列不被判成已绑定版本', () => {
  const text = '| 编号 | 问题 | 状态 |\n|---|---|---|\n| H-02 | 维修条款一版日常维护、另一版全部设施故障费用；两版与草稿A/B的对应关系如何？ | 版本对应待核实 |\n\n| 编号 | 版本内容 | 与草稿A/B的对应关系 |\n|---|---|---|\n| M-01 | 一版日常维护 | 版本对应待核实 |';
  assert.deepEqual(unboundMaintenanceTableClaims(text), []);
});
