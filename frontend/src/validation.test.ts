import { describe, expect, it } from 'vitest';
import { validateCredentials } from './validation';

const valid = { login: 'parent1', password: 'password123', confirmation: 'password123', invitationCode: 'ABCD1234EF56', consent: true };

describe('registration constraints', () => {
  it('accepts parent invite case-insensitively and teacher without parent code', () => {
    expect(validateCredentials({ ...valid, invitationCode: 'abcd1234ef56' }, 'parent')).toEqual({});
    expect(validateCredentials({ ...valid, invitationCode: '' }, 'teacher')).toEqual({});
  });
  it('requires confirmation, valid invitation and explicit consent', () => {
    const errors = validateCredentials({ ...valid, confirmation: 'different', invitationCode: 'wrong', consent: false }, 'parent');
    expect(Object.keys(errors).sort()).toEqual(['confirmation', 'consent', 'invitationCode']);
  });
  it('enforces backend lengths without applying signup rules to existing login', () => {
    expect(validateCredentials({ ...valid, login: 'a', password: 'short' }, 'parent')).toHaveProperty('login');
    expect(validateCredentials({ ...valid, password: 'a'.repeat(26) }, 'teacher')).toHaveProperty('password');
    expect(validateCredentials({ ...valid, login: 'st-ABCDEFGH', password: 'legacy', consent: false }, 'login')).toEqual({});
  });
});
