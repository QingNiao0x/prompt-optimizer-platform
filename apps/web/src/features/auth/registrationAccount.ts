export type RegistrationAccountKind = 'empty' | 'email' | 'phone' | 'invalid';

export interface RegistrationAccountValidation {
  kind: RegistrationAccountKind;
  normalized: string;
  message: string;
}

const EMAIL_LOCAL_PART = "[A-Z0-9!#$%&'*+/=?^_`{|}~-]+";
const EMAIL_DOMAIN_LABEL = '[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?';
const EMAIL_PATTERN = new RegExp(
  `^${EMAIL_LOCAL_PART}(?:\\.${EMAIL_LOCAL_PART})*@${EMAIL_DOMAIN_LABEL}(?:\\.${EMAIL_DOMAIN_LABEL})+$`,
  'i',
);
const MAINLAND_PHONE_PATTERN = /^1[3-9]\d{9}$/;
const E164_PHONE_PATTERN = /^\+[1-9]\d{7,14}$/;

/** PNVS 首期只开放大陆号码；通用身份模型仍保留其他 E.164 号码的表示能力。 */
export const normalizeMainlandPhone = (value: string): string | undefined => {
  if (value.length > 32) return undefined;
  const compact = value.trim().replace(/[ -]/g, '');
  const local = compact.startsWith('+86') ? compact.slice(3) : compact;
  return MAINLAND_PHONE_PATTERN.test(local) ? `+86${local}` : undefined;
};

/** 与后端 BCrypt 输入边界一致，避免多字节密码被截断。 */
export const validRegistrationPassword = (value: string): boolean => (
  value.length >= 8 && /\p{L}/u.test(value) && /[0-9]/.test(value) && new TextEncoder().encode(value).length <= 72
);

/** 校验邮箱格式并限制为数据库支持的最大长度。 */
export const isValidEmailAddress = (value: string): boolean => {
  const trimmed = value.trim();
  return trimmed.length <= 320 && EMAIL_PATTERN.test(trimmed);
};

/** 将常见格式的手机号规范化为 E.164，供注册和后续手机号绑定表单复用。 */
export const normalizePhoneNumber = (value: string): string | undefined => {
  const compactPhone = value.trim().replace(/[\s()-]/g, '');
  if (MAINLAND_PHONE_PATTERN.test(compactPhone)) {
    return `+86${compactPhone}`;
  }
  return E164_PHONE_PATTERN.test(compactPhone) ? compactPhone : undefined;
};

/** 注册首期只接收邮箱和大陆手机号；能力是否开放由表单读取后端开关判断。 */
export const validateRegistrationAccount = (value: string): RegistrationAccountValidation => {
  const trimmed = value.trim();
  if (!trimmed) {
    return {
      kind: 'empty',
      normalized: '',
      message: '请输入邮箱地址或中国大陆 11 位手机号。',
    };
  }

  if (isValidEmailAddress(trimmed)) {
    return {
      kind: 'email',
      normalized: trimmed.toLowerCase(),
      message: '邮箱格式正确',
    };
  }

  const normalizedPhone = normalizeMainlandPhone(trimmed);
  if (normalizedPhone) {
    return {
      kind: 'phone',
      message: '手机号格式正确',
      normalized: normalizedPhone,
    };
  }

  return {
    kind: 'invalid',
    normalized: '',
    message: '请输入有效的邮箱地址或中国大陆 11 位手机号。',
  };
};
