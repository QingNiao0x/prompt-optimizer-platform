export type RegistrationAccountKind = 'empty' | 'email' | 'phone' | 'invalid';

export interface RegistrationAccountValidation {
  kind: RegistrationAccountKind;
  normalized: string;
  message: string;
}

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/;
const MAINLAND_PHONE_PATTERN = /^1[3-9]\d{9}$/;
const E164_PHONE_PATTERN = /^\+[1-9]\d{7,14}$/;

/** 校验注册账号；手机号统一转换为身份模型使用的 E.164 格式。 */
export const validateRegistrationAccount = (value: string): RegistrationAccountValidation => {
  const trimmed = value.trim();
  if (!trimmed) {
    return {
      kind: 'empty',
      normalized: '',
      message: '请输入邮箱地址或手机号。',
    };
  }

  if (trimmed.length <= 320 && EMAIL_PATTERN.test(trimmed)) {
    return {
      kind: 'email',
      normalized: trimmed.toLowerCase(),
      message: '邮箱格式正确',
    };
  }

  const compactPhone = trimmed.replace(/[\s()-]/g, '');
  if (MAINLAND_PHONE_PATTERN.test(compactPhone)) {
    return {
      kind: 'phone',
      normalized: `+86${compactPhone}`,
      message: '手机号格式正确，但短信验证码注册尚未开通，请暂时使用邮箱。',
    };
  }
  if (E164_PHONE_PATTERN.test(compactPhone)) {
    return {
      kind: 'phone',
      normalized: compactPhone,
      message: '手机号格式正确，但短信验证码注册尚未开通，请暂时使用邮箱。',
    };
  }

  return {
    kind: 'invalid',
    normalized: '',
    message: '请输入有效的邮箱地址或手机号。',
  };
};
