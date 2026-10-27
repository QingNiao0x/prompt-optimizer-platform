/** 账号设置仅包含当前登录用户可编辑的投影，不携带角色、用户 ID 或供应商配置。 */
export interface SystemSettingsView {
  displayName: string;
  email: string | null;
  username: string | null;
  locale: InterfaceLocale;
  emailBindingAvailable: boolean;
  accountDeletionAvailable: boolean;
}

/** 本轮可用的界面语言；业务正文与正式法律文本不随界面语言自动翻译。 */
export type InterfaceLocale = 'zh-CN' | 'en';

/** 邮箱挑战编号仅在内存中保存，提交时仍由服务端校验归属、用途和有效期。 */
export interface SettingsEmailChallenge {
  challengeId: string;
  expiresInSeconds: number;
  resendAfterSeconds: number;
}
