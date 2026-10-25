/** 访客额度与正式账号权限分离；计数只以服务端响应为准，不在浏览器本地扣减。 */
export type TrialFeature = 'IMAGE_TO_PROMPT' | 'PROMPT_ENHANCEMENT' | 'TEXT_POLISH';

export interface TrialQuota { limit: number; used: number; remaining: number }
export interface TrialStatus {
  authenticated: boolean;
  available: boolean;
  /** 服务端已识别访客 Cookie；明确为 false 时不得把展示的初始上限当作可用额度。 */
  initialized?: boolean;
  quotas: { imagePrompt: TrialQuota; enhancement: TrialQuota; textPolish: TrialQuota };
}

export const trialFeatureInfo = {
  IMAGE_TO_PROMPT: { key: 'imagePrompt', label: '图像转提示词', limit: 1 },
  PROMPT_ENHANCEMENT: { key: 'enhancement', label: '提示词优化', limit: 3 },
  TEXT_POLISH: { key: 'textPolish', label: '文章祛AI味', limit: 3 },
} as const;
