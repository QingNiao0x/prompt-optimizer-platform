import { describe, expect, it } from 'vitest';

import { validateRegistrationAccount } from './registrationAccount';

describe('registration account validation', () => {
  it('should normalize a valid email address', () => {
    expect(validateRegistrationAccount(' New@Example.com ')).toEqual({
      kind: 'email',
      normalized: 'new@example.com',
      message: '邮箱格式正确',
    });
  });

  it('should accept mainland and E.164 phone numbers', () => {
    expect(validateRegistrationAccount('13800138000')).toEqual({
      kind: 'phone',
      normalized: '+8613800138000',
      message: '手机号格式正确，但短信验证码注册尚未开通，请暂时使用邮箱。',
    });
    expect(validateRegistrationAccount('+14155552671').kind).toBe('phone');
  });

  it('should reject malformed email and phone values', () => {
    expect(validateRegistrationAccount('not-an-account')).toEqual({
      kind: 'invalid',
      normalized: '',
      message: '请输入有效的邮箱地址或手机号。',
    });
    expect(validateRegistrationAccount('1380013800').kind).toBe('invalid');
  });
});
