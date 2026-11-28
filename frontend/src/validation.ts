interface Credentials {
  login: string;
  password: string;
  confirmation: string;
  invitationCode: string;
  consent: boolean;
}

export function validateCredentials(values: Credentials, mode: string): Record<string, string> {
  const errors: Record<string, string> = {};
  const registration = mode === 'parent' || mode === 'teacher';
  if (!values.login.trim()) errors.login = 'Введите логин';
  else if (registration && (values.login.trim().length < 5 || values.login.trim().length > 25)) errors.login = 'Логин должен содержать от 5 до 25 символов';
  if (!values.password) errors.password = 'Введите пароль';
  else if (registration && (values.password.length < 8 || values.password.length > 25)) errors.password = 'Пароль должен содержать от 8 до 25 символов';
  if (registration && values.password !== values.confirmation) errors.confirmation = 'Пароли не совпадают';
  if (mode === 'parent' && !/^[A-Z0-9]{12}$/.test(values.invitationCode.trim().toUpperCase())) errors.invitationCode = 'Введите код из 12 латинских букв и цифр';
  if (registration && !values.consent) errors.consent = 'Подтвердите согласие перед регистрацией';
  return errors;
}
