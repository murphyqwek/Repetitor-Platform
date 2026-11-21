# План этапа 2

Разрешение пользователя: «Давай следующий этап». Выполнить только этап 2, затем отчёт и остановка.
После предложения ADR-002 пользователь поручил «Заверши весь второй этап»: разрешён точечный lookup Identity.
Бизнес-маршруты и таблицы этапа 3 не добавлять, Identity попутно не рефакторить.
Активны ponytail/caveman; формат Java соответствует AuthController Identity.

## Подзадачи и проверки

- [x] RS256 JWT: реальный public key, issuer, exp/nbf/iat, UUID subject, несколько ролей.
  Тесты: подписанные test JWT, bad signature/issuer/expired/nbf/malformed claims → 401.
- [x] CurrentUser/PermissionService: роли не заменяют владение. Teacher/student/admin/parent, чужой owner → 403.
- [x] Identity HTTP client для текущего пользователя: существующий GET /api/v1/users/me с cookie access_token, timeouts и fail closed.
  Проверять актуальный blocked на каждом API-запросе, профиль/UUID/roles. Ошибки/timeout → 503, blocked → 403.
- [x] ProblemDetail/code в Security и MVC; 400/401/403/404/409/413/415/422/503 без утечек.
  Проверять DTO, UUID, limit и cursor только тестовыми контроллерами, без production test endpoints.
- [x] Keyset cursor: opaque authenticated/encrypted token, createdAt+UUID, привязка scope, default/max limits.
  Тесты malformed/tampered/wrong scope/expired, равные timestamps и реальная PostgreSQL keyset query.
- [x] Env/local/test, public-key mount в Compose, README. Тестовые RSA ключи генерировать отдельно, production decoder не ослаблять.
- [x] Реальная PostgreSQL/Flyway повторная migrate без потери данных; полные test/bootJar/image/Compose/probes.
- [x] Карта файлов, ADR/блокеры, logs и DoD; остановиться до этапа 3.
- [x] Проверка произвольного целевого пользователя по UUID: согласована, реализована в каталоге Identity и подключена к клиенту Education (ADR-002).

## Разрешённое ограничение Identity

Изначально общего GET по UUID не было, подзадача была остановлена до согласования.
Теперь GET /api/v1/directory/users/{id} реализован в существующем DirectoryController с TEACHER/ADMIN и limited DTO.
Education различает target 404, blocked/roles и dependency 503; собственный профиль по-прежнему читает /users/me.
Полные suites: Education 64, Identity 43; все тесты прошли. Временная БД Identity удалена, рабочие volumes сохранены.

## После сжатия контекста

Перечитать file_map.md, stage-2-plan.md, progress.md, ADR-002 и прочие актуальные артефакты.
Проверить исходники и фоновые процессы, затем продолжать с текущего состояния.
