/**
 * 将已约定的模型名称显示为产品短名；只处理明确的别名，不通用截断数字或推断上游版本。
 * 调用 ID、管理员保存值及历史快照保留原值，便于追溯实际调用。
 */
export const formatModelVersion = (version?: string | null, fallback = '版本未记录'): string => {
  const value = version?.trim();
  if (!value) return fallback;
  return value.toLowerCase() === 'deepseek-v4-pro-0813' ? 'DeepSeek-V4-Pro' : value;
};
