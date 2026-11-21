# ADR-002 — JWT, текущий профиль и lookup Identity

Дата: 20.11.2026. Статус: принято и реализовано полностью.
После предложения точечного расширения Identity пользователь поручил: «Заверши весь второй этап».
Это разрешение применено только к описанному ниже lookup; прочая логика Identity не менялась.

## Проверенный текущий контракт

- `Identity JwtService` выдаёт RS256, issuer `rp-identity`, subject UUID, iat/exp и массив roles.
- `JwtConfig` использует X.509 RSA public PEM. JWKS endpoint отсутствует.
- `SecurityConfig.bearerTokenResolver` принимает только cookie `access_token`; Authorization header не используется.
- `GET /api/v1/users/me` возвращает id, firstName, surname, patronymic, roles, blocked, nameRequested и дополнительные поля.
- `ProfileAccessConfig` немедленно отклоняет заблокированного вызывающего с 403, включая `/users/me`.
- `GET /api/v1/students/{id}` ограничен родителем и его ребёнком; teacher/admin lookup им не заменить.
- `GET /api/v1/directory/users` требует type+fio; до этого изменения произвольного UUID lookup не было.
- События блокировки/revocation endpoint для Education сейчас отсутствуют.

Исходники: `services/identity-service/src/main/java/tutor/identityservice/`:
`service/JwtService.java`, `config/JwtConfig.java`, `config/SecurityConfig.java`,
`config/ProfileAccessConfig.java`, `controller/ProfileController.java`, `controller/DirectoryController.java`.

## Проверка текущего профиля без изменения прежней auth-логики Identity

Education принимает только Authorization: Bearer, а не cookie. JWT локально проверяется production decoder
по public key, issuer, времени, canonical UUID и списку известных ролей. Неподписанные JWT не принимаются.
Для каждого защищённого `/api/v1/**` запроса после проверки JWT Education вызывает существующий `/users/me`,
передавая только проверенный JWT как `Cookie: access_token=...`. Это адаптация к фактическому Identity API;
сессия/остальные cookies браузера не пересылаются. HTTP redirects запрещены, timeouts ограничены.

Кеш профиля отсутствует: блокировка закрывает следующий запрос с тем же JWT. HTTP 403 Identity также закрывает доступ.
HTTP 401 сохраняется как 401; 404 собственного удалённого аккаунта — 403, не «целевой ученик не найден».
Timeout, 5xx, 429, redirect, malformed/неполный профиль или несовпадающий UUID — 503 IDENTITY_UNAVAILABLE.
Детали upstream и credentials не попадают в Problem JSON.

Эффективные роли — пересечение подписанного JWT и свежего профиля: профиль не добавляет неподписанные
полномочия, отозванные роли перестают работать. Права владельца всегда дополнительно сверяются по UUID;
TEACHER сам по себе не означает владение. ADMIN может управлять чужим ресурсом согласно контракту.
Незаполненный профиль учителя/родителя закрывается аналогично правилам Identity.

Health probes не обращаются к Identity. Это готовность PostgreSQL/S3 и процесса, а не обещание работоспособности
Identity: при его недоступности API отвечает 503. WebSocket-проверки и прекращение существующих WS сессий
относятся к этапу 14; текущий результат не заявляет их реализацию.

## Реализованная проверка другого пользователя по UUID

Первоначально подзадача была остановлена из-за отсутствия маршрута и запрета менять Identity.
После согласования реализован точечный lookup в существующих DirectoryController/DirectoryService.
Нет перебора каталога, фиктивных ответов или новых механизмов аутентификации.

Согласованный и реализованный маршрут:

```http
GET /api/v1/directory/users/{id}
Cookie: access_token=<JWT действующего TEACHER или ADMIN>
```

Ответ 200: `{id, firstName, surname, patronymic, roles, blocked}`. Никаких логинов, паролей и иных полей профиля.
404 только при отсутствии целевого UUID; 401 при неправильной аутентификации; 403 при отсутствии роли,
блокировке вызывающего или незаполненном профиле согласно текущей политике Identity.
Заблокированный целевой пользователь возвращается с `blocked=true`, чтобы Education мог корректно
отказать в зачислении и отображать существующие членства. Поиск по UUID не меняет владельцев и роли.

Используется существующая модель caller authorization; отдельный service credential не добавлен.
Education `IdentityClient.user(accessToken, id)` вызывает этот маршрут и возвращает ограниченный IdentityUser.
Целевой 404 преобразуется в 404 USER_NOT_FOUND, а timeout/ошибка связи/5xx/redirect/невалидный ответ — в 503.
Блокировка и отсутствие STUDENT возвращаются как данные; правила зачисления относятся к этапу 4.
Текущий `/users/me` по-прежнему отличает исчезнувшего вызывающего (403) от отсутствующего целевого пользователя (404).

Проверки: 43 теста Identity (включая настоящий HTTP с подписанным cookie JWT и PostgreSQL) и
64 теста Education (включая HTTP lookup, timeouts, blocked/roles, 404 vs 503 и регрессию S3) проходят.
