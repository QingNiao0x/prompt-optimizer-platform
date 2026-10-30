interface ModelOperationPresentation {
  readonly module: string;
  readonly label: string;
}

// 按服务端有界操作代码区分业务模块；上下文处理是共用能力，不能归入某个工具或反推账号。
const operations: Readonly<Record<string, ModelOperationPresentation>> = {
  'prompt.optimize': { module: '提示词优化', label: '提示词优化' },
  'plan.generate': { module: '提示词优化', label: 'Plan 问题生成' },
  'document.summary.map': { module: '上下文处理', label: '文档分段摘要' },
  'document.summary.reduce': { module: '上下文处理', label: '文档合并摘要' },
  'context.embedding': { module: '上下文处理', label: '上下文向量化' },
  'context.vision': { module: '上下文处理', label: '上下文视觉识别' },
  'image.analyze': { module: '素材转提示词', label: '素材转提示词' },
  'image.plan': { module: '素材转提示词', label: '素材 Plan 问题生成' },
  'image.generate': { module: '素材转提示词', label: '素材提示词生成' },
  'text.polish': { module: '文章祛AI味', label: '文章祛AI味' },
};

/** 仅固定操作代码可归属模块；旧的 unknown 或未识别代码不猜测业务来源。 */
export const modelCallModule = (operation: string): string => operations[operation]?.module ?? '未分类';

/** 展示中文操作，表格同时保留独立的原始代码，避免同名操作或未知旧数据被隐藏。 */
export const modelCallOperationLabel = (operation: string): string => operations[operation]?.label ?? '未知操作';
