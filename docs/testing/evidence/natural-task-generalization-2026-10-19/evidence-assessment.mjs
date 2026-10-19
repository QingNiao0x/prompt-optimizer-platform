/** 单独测量人工选定的成品与完整响应；选择必须逐字存在，不能靠标题关键词猜测正文。 */
export function assessTextArtifact(content, bodyQuote, requirement) {
  if (typeof content !== 'string' || !bodyQuote || typeof bodyQuote !== 'string') {
    throw Error('完整响应和正文引文不能为空');
  }
  const start = content.indexOf(bodyQuote);
  if (start < 0 || content.indexOf(bodyQuote, start + 1) >= 0) throw Error('正文引文必须唯一且逐字存在');
  if (!Number.isInteger(requirement.min) || !Number.isInteger(requirement.max)
      || requirement.min < 0 || requirement.max < requirement.min) throw Error('无效的原始篇幅要求');
  if (requirement.metric != null && !['nonWhitespace', 'han'].includes(requirement.metric)) {
    throw Error('未知的计数口径');
  }
  const count = value => ({
    nonWhitespace: [...value.replace(/\s/gu, '')].length,
    han: (value.match(/\p{Script=Han}/gu) ?? []).length
  });
  const selectedBody = count(bodyQuote);
  const completeResponse = count(content);
  const end = start + bodyQuote.length;
  const outsideSelectedBody = count(content.slice(0, start) + content.slice(end));
  const inRange = counts => Object.fromEntries(Object.entries(counts)
    .map(([key, size]) => [key, size >= requirement.min && size <= requirement.max]));
  const bodyWithinRange = inRange(selectedBody);
  return {
    selectedBody: { quote: bodyQuote, start, end, ...selectedBody },
    completeResponse, outsideSelectedBody,
    lengthAssessment: {
      requirement,
      status: requirement.metric == null ? 'COUNTING_CONVENTION_NOT_FROZEN'
        : bodyWithinRange[requirement.metric] ? 'PASSED' : 'FAILED',
      bodyWithinRange, completeResponseWithinRange: inRange(completeResponse)
    },
    content
  };
}

/** 按已有检查证据汇总状态；未评审及空集合保持未知，样例失败不扩展为所有能力失败。 */
export function summarizeEvidence(checks) {
  if (!Array.isArray(checks)) throw Error('检查记录必须为数组');
  const ids = new Set();
  const totals = { passed: 0, failed: 0, unverified: 0 };
  for (const check of checks) {
    if (!check.id || ids.has(check.id) || !['PASSED', 'FAILED', 'UNVERIFIED'].includes(check.status)) {
      throw Error('检查记录必须具有唯一标识和明确状态');
    }
    ids.add(check.id);
    totals[{ PASSED: 'passed', FAILED: 'failed', UNVERIFIED: 'unverified' }[check.status]]++;
  }
  const status = totals.failed ? 'FAILURES_RECORDED'
    : !checks.length || totals.unverified ? 'UNVERIFIED' : 'VERIFIED_WITHIN_RECORDED_SCOPE';
  return { status, ...totals, scope: '仅适用于所列检查、版本、模型和样例', checks };
}
