import type { PromptFormat, PromptSection } from '@/types/api';

/** 手工编辑后按当前结果的实际模式拼回正文；增强模式切换须重新生成，不能用此函数伪装完成。 */
export const renderPromptSections = (
  sections: readonly PromptSection[],
  format: PromptFormat,
): string => sections
  .filter(section => section.type !== 'CLARIFICATIONS')
  .map(section => format === 'COMPACT'
    ? section.content : `## ${section.title}\n${section.content}`)
  .join('\n\n');
