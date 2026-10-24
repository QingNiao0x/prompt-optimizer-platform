/** 评测业务输入只由明确白名单构造；执行时新增资料不能倒灌到增强阶段。 */
export function enhancementInput(sample, protocol) {
  return {
    rawPrompt: sample.rawPrompt, modelId: protocol.modelId,
    context: { customDescription: sample.contextDescription ?? '', files: sample.files ?? [] },
    enhancement: { templateCode: 'AUTO', includeConversationHistory: (sample.conversationHistory ?? []).length > 0, includePermissionBoundaries: true,
      promptFormat: 'COMPACT' },
    conversationHistory: sample.conversationHistory ?? [], permissionPolicy: { protectedPaths: [], requireConfirmationFor: [] }, planConfirmation: null,
  };
}

/** 三臂收到相同资料正文；这里只陈述材料来源，不向执行者提供评分卡或期望答案。 */
export function executionMaterialSuffix(sample) {
  const answers = sample.answerBundle ? '\n\n用户已提供的本次答复（未知项保持未知）：\n' + sample.answerBundle : '';
  const material = (sample.files ?? []).length ? '\n\n用户同时提供的材料：\n' + JSON.stringify(sample.files) : '';
  const context = sample.contextDescription ? '\n\n用户提供的背景：\n' + sample.contextDescription : '';
  const executor = (sample.executorMaterials ?? []).length
    ? '\n\n执行本次任务时用户提供的材料（完整正文）：\n'
      + sample.executorMaterials.map(file => '--- ' + file.name + ' ---\n' + file.content).join('\n\n') : '';
  return material + context + answers + executor;
}

/** 原始、基线、候选只替换prompt；模型参数、系统能力和全部执行资料逐字相同。 */
export function executionRequest(sample, prompt, protocol) {
  return { model: protocol.upstreamModel, stream: false, temperature: protocol.parameters.temperature,
    max_tokens: protocol.parameters.executionMaxTokens, thinking: { type: 'disabled' }, messages: [
      { role: 'system', content: '完成用户当前请求，遵守用户明确的输出范围、输出形式与阶段边界。仅使用本次提供的材料；无法访问外部工具或文件，不声称已经运行、联网或完成未执行的操作。' },
      { role: 'user', content: prompt + executionMaterialSuffix(sample) },
    ] };
}

/** 评审独立知道两个阶段的资料边界，评分卡只在评审中使用。 */
export function reviewCaseInputs(sample) {
  return { id: sample.id, rawPrompt: sample.rawPrompt, materials: sample.files ?? [],
    context: sample.contextDescription ?? '', executorMaterials: sample.executorMaterials ?? [],
    answers: sample.answerBundle ?? '', acceptance: sample.acceptance,
    informationBoundary: 'executorMaterials增强时不可见，三臂实际执行时均完整提供。' };
}
