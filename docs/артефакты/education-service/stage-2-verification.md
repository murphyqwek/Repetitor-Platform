# Education Service — отчёт этапа 2

20.11.2026, Europe/Minsk. **Этап 2 завершён полностью. Этап 3 не начат.**
После предложения ADR-002 пользователь поручил «Заверши весь второй этап»: разрешено точечно расширить каталог Identity.
Lookup другого пользователя по UUID реализован, подключён и проверен; незакрытых подзадач нет.

## Поведение

- Production RS256 decoder использует public key Identity и проверяет issuer, exp/nbf/iat, UUID sub, массив известных ролей.
  Тестовые ключи генерируются отдельно; private key Identity не читается и не передаётся в Education.
- Bearer token только в Authorization, stateless, без cookie login. Public GET health probes сохранены.
  API требует TEACHER/STUDENT/ADMIN, internal paths остаются закрыты.
- Свежий профиль через **существующий** GET /api/v1/users/me перед каждым API-запросом.
  В Identity проверенный JWT адаптируется к Cookie access_token: его текущий resolver игнорирует Bearer headers.
  Нет кеша/redirects; явные connect/read timeouts. Аутентификация и текущий профиль Identity не менялись.
- `IdentityClient.user(token, id)` вызывает согласованный GET /api/v1/directory/users/{id}.
  Identity разрешает TEACHER/ADMIN и возвращает только UUID, ФИО, roles, blocked. Заблокированный target доступен
  как данные, заблокированный actor запрещён. Target 404 → USER_NOT_FOUND; timeout/связь/5xx/bad schema → 503.
- Блокировка с тем же токеном запрещает следующий запрос. Свежие роли пересекаются с подписанными:
  отозванные роли не работают, дополнительных неподписанных привилегий нет. Проверяются UUID профиля и полнота ответа.
- PermissionService дополнительно сверяет owner UUID. Caller должен передавать владельца из БД ресурса, не из клиентского тела.
- Security и MVC возвращают безопасный Problem JSON/code. Проверены 400/401/403/404/409/413/415/422/503 и безопасный 500.
- AES-256-GCM курсор: createdAt+UUID, scope binding, 24h expiry, защита от изменения и непрозрачность.
  Общий limit 30/max100, будущий chat default50. Серверный scope должен включать пользователя/ресурс/фильтры/порядок.
- Сохранена отдельная PostgreSQL Education. Flyway re-run не меняет уже применённый V1 и не теряет данные.
  Бизнес-таблицы и маршруты по-прежнему отсутствуют.

## Изменённые файлы этапа

В `services/education-service/`:

- `build.gradle.kts`: официальный RestClient starter.
- `src/main/resources/application.yaml`, `application-local.yaml`: security/Identity/cursor env.
- `src/main/java/tutor/educationservice/security/SecurityConfiguration.java`.
- Новые `security/JwtConfiguration.java`, `IdentityAccessFilter.java`, `CurrentUser.java`, `PermissionService.java`, `Role.java`.
- Новые `integration/identity/IdentityClient.java`, `IdentityProperties.java`, `IdentityProfile.java`, `IdentityUser.java`.
- Новые `common/error/ApiException.java`, `Problems.java`, `ApiExceptionHandler.java`.
- Новые `common/pagination/CursorCodec.java`, `CursorProperties.java`, `common/TimeConfiguration.java`.
- `src/test/java/tutor/educationservice/EducationContextTest.java`, `EducationInfrastructureTest.java`: адаптация к 401,
  тестовые security properties, Flyway re-run и реальная keyset SQL проверка.
- Новые `src/test/java/tutor/educationservice/TestJwt.java`, `SecurityHttpTest.java`, `IdentityLookupTest.java`, `EducationLocalProfileTest.java`,
  `src/test/java/tutor/educationservice/common/CursorCodecTest.java`.
- `README.md`: актуальные настройки, ограничения и команды.

Вне сервиса: `.env.example`, `Docker/local/compose.yml` (только Education env/public-key mount/host gateway),
локальный игнорируемый `.env` (сгенерирован постоянный ключ курсоров), текущие артефакты.
Согласованное расширение Identity: `controller/DirectoryController.java`, `service/DirectoryService.java`,
`IdentityServiceApplicationTests.java`, `controller/IdentityApiTest.java`, `service/DirectoryServiceTest.java`.
Пути относительно src/main/java/tutor/identityservice и src/test/java/tutor/identityservice соответственно.
Документирован новый контракт в `docs/архитектура/identity-service/endpoints.md`.
Прочая логика Identity, контракты Education, PostgreSQL/Redis/Kafka и миграция V1 не изменены.

## Команды и фактические результаты

Gradle выполнялся из каталога соответствующего сервиса, Compose — из корня репозитория.
`./gradlew` запускался через Git Bash, в Docker — через Linux shell.

| Проверка | Результат |
|---|---|
| `.\gradlew.bat compileJava --console=plain` | Успех |
| `.\gradlew.bat test --tests '*CursorCodecTest' --tests '*EducationContextTest'` | 8 тестов прошли |
| `.\gradlew.bat test --tests '*SecurityHttpTest'` | 28 тестов прошли |
| **Education: `./gradlew test bootJar --console=plain`** | **BUILD SUCCESSFUL, 64 теста, 0 failures/errors/skipped** |
| **Identity: `./gradlew test bootJar --console=plain`** | **BUILD SUCCESSFUL, 43 теста, 0 failures/errors/skipped** |
| `docker compose --env-file .env -f Docker/local/compose.yml config --quiet` | Успех без вывода secrets |
| `docker compose --env-file .env -f Docker/local/compose.yml up -d --build` | Успешная JDK17/JRE17 сборка и запуск |
| `curl.exe -i http://localhost:8081/actuator/health/liveness` | 200 UP |
| `curl.exe -i http://localhost:8081/actuator/health/readiness` | 200 UP |
| `curl.exe -i http://localhost:8081/api/v1/courses` | 401, application/problem+json, code UNAUTHORIZED |
| То же с `Authorization: Bearer invalid` | 401, тот же безопасный Problem JSON |
| `jar tf ...education-service.jar` | TestRoutes/TestJwt/SecurityHttpTest отсутствуют |
| `git diff --check`; проверка diff Identity | Успех; только согласованное расширение каталога и его тесты |
| Скан новых локальных паролей и ключа курсоров в исходниках/артефактах | Утечек нет |

Состав 64 тестов Education: SecurityHttpTest 28, IdentityLookupTest 11, context/test 5, context/local 5,
настоящая S3/PostgreSQL инфраструктура 7, S3 unit 5, cursor unit 3.
Identity: IdentityApiTest 18, IdentityServiceApplicationTests 8, DirectoryServiceTest 4, IdentityServicesTest 11, UserServiceTest 2.
Плохие JWT отклоняются **до** вызова Identity. Сбой/таймаут Identity возвращает 503,
плохой/отсутствующий профиль не трактуется как «целевой ученик не найден».
Проверка blocked включает повтор с тем же JWT после смены состояния stub, без очистки кеша или перевыдачи токена.

Identity stub — реальный локальный HTTP сервер с проверкой cookie, production-клиентом и production SecurityFilterChain.
Отдельно полный Identity suite поднимает настоящий HTTP сервер, Flyway и изолированную PostgreSQL;
lookup выполняется реальным HTTP client с JWT, подписанным production JwtService на генерируемых тестовых ключах.
Teacher/admin получают limited DTO заблокированного target; блокировка actor немедленно закрывает тот же JWT.
Прогон с production-пользователем не выполнялся.

Для Identity создан временный контейнер PostgreSQL 17 с динамическим host-портом и отдельной БД identity_test;
TEST_DATABASE_URL передан процессу Gradle. После завершения container ID сверён, временная БД удалена.
Рабочая PostgreSQL не использовалась для записи тестовых данных.

Воспроизведение полного Identity suite в отдельном PowerShell-окне из корня репозитория:

```powershell
docker run --rm -d --name education-stage2-identity-test-db -e POSTGRES_DB=identity_test -e POSTGRES_USER=identity_test -e POSTGRES_PASSWORD=identity_test -p 127.0.0.1::5432 postgres:17-alpine
$testPort = (docker port education-stage2-identity-test-db 5432/tcp).Split(':')[-1]
$env:TEST_DATABASE_URL = "jdbc:postgresql://localhost:$testPort/identity_test"
Set-Location services/identity-service
.\gradlew.bat test bootJar --console=plain
docker stop education-stage2-identity-test-db
```

Это отдельная временная БД с тестовыми credentials, не `.env` рабочей платформы.

## Логи

- [Полный успешный suite и bootJar](stage-2-test.log).
- [Полный Identity suite и bootJar](stage-2-identity-test.log).
- [Сводка JUnit XML обоих сервисов](stage-2-test-summary.log).
- [Docker build и Compose up](stage-2-compose.log).
- [Настоящий контейнер: probes и Problem 401](stage-2-smoke.log).
- [Startup/Flyway/приложение](stage-2-startup.log).

При компиляции появились предупреждения устаревших API Spring/Jackson; использованы актуальные
UNPROCESSABLE_CONTENT и JsonNode.asString. Ошибок тестов этапа 2 не было; отрицательные сценарии проходят
как проверки ожидаемых 401/403/503/400. Первоначальные Quay-провалы относятся к этапу 1 и сохранены отдельно.
В итоговом PowerShell smoke ответ оказался byte[]; первоначальная проверка JSON ошиблась на декодировании.
После явного UTF-8 преобразования проверка прошла без изменения приложения; финальный результат сохранён в сводке.

## DoD

- [x] JWT подпись/issuer/время/UUID/несколько ролей; неподписанный и неправильный JWT дают 401.
- [x] CurrentUser/PermissionService, 403 за чужого owner/нехватку роли, admin policy.
- [x] Реальный существующий Identity current-profile API, blocked, имя, роли, bounded timeouts, 503.
- [x] Любой целевой пользователь по UUID: согласованный lookup Identity, limited DTO, права, blocked/roles, 404 vs 503.
- [x] Problem JSON/code, DTO/UUID/limit/cursor и безопасные ошибки из Security/MVC.
- [x] Scope/expiry/tamper cursors, default/max limits, SQL keyset на реальной PostgreSQL.
- [x] Независимый local/test context и подписанные test JWT без ослабления production.
- [x] Повторная Flyway migration без повреждений, JPA validate и UTC сохранены.
- [x] Все 64 теста Education и 43 Identity, оба bootJar, Education image build, Compose config/up, probes прошли.
- [x] Бизнес-маршрутов Education нет; Identity расширен только по согласованному ADR-002; документация актуальна.

## Границы и риски

Минимальное расширение `GET /api/v1/directory/users/{id}` принято и реализовано, см. [ADR-002](ADR-002-identity-integration.md).
Схемы БД и прежняя auth-политика Identity не менялись. Если Identity уже запущен в IDE/из старого jar,
его нужно перезапустить из обновлённых исходников; пользовательский процесс автоматически не останавливался.

Блокировка актуальна на момент проверки профиля; изменение между проверкой и завершением уже начатого запроса
не отменяет его атомарно. Существующие WebSocket-сессии ещё не поддерживаются (этап 14).
Смена ключа курсоров инвалидирует ранее выданные курсоры. Production TLS/rotation/нагрузка не проверены.

Обнаружено существующее состояние: `dev_secrets/jwt_private.pem` уже tracked в Git до этапа 2.
Этот файл не читался, не менялся и не копировался в Education; в контейнер монтируется только public PEM.
Уборка Git-истории/ротация не выполнялись. Такой dev key нельзя считать production-секретом.
Local stack оставлен запущенным. Этап 3 не начинался.
