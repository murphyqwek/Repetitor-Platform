# Education Service — отчёт этапа 3

20.11.2026, Europe/Minsk. **Этап 3 завершён. Этап 4 не начинался.**

## Реализованное поведение

Добавлена таблица course и API создания, чтения, списка, переименования:

| Маршрут | Результат |
|---|---|
| POST /api/v1/courses | 201 + Location, JWT owner или выбранный администратором реальный TEACHER |
| GET /api/v1/courses/{id} | 200 owner/admin; чужой, отсутствующий и DELETING — 404 |
| PATCH /api/v1/courses/{id} | 200, меняется только title; student — 403, чужой teacher/DELETING — 404 |
| GET /api/v1/courses?scope=OWNED\|ALL | items/nextCursor/hasMore, ACTIVE only, role/filter checks и keyset |

CourseDto ровно `{id,teacherId,title,meetingUrl,chatMode,createdAt}`. Новый курс NORMAL, meetingUrl=null;
название обязательно, может повторяться, хранится в text без нового ограничения 255 символов.
POST не требует Idempotency-Key. createdAt в UTC, с точностью микросекунд PostgreSQL.
Admin target проверяется Identity до открытия SQL write transaction; 404/не-TEACHER/503 не оставляют запись.
Нового запрета администратору создавать для заблокированного teacher контракт не устанавливает, поэтому он не вводился;
заблокированный вызывающий всё равно лишён доступа текущим IdentityAccessFilter.

OWNED использует UUID преподавателя из JWT; другой teacherId выбирается только ADMIN.
ADMIN без TEACHER должен явно выбрать teacherId для POST/OWNED; ADMIN+TEACHER может действовать как свой преподаватель.
ALL доступен ADMIN, teacherId необязателен. Scope обязателен, лимит 30/max100, запрос читает limit+1.
Порядок `(created_at DESC,id DESC)` выполняется PostgreSQL, cursor привязан к actor/scope/owner filter/sort version.
Нет OFFSET, count query или зависимости курсора от наличия опорной строки.

PATCH принимает только title; teacherId/meetingUrl/chatMode/deletionState/id/createdAt отклоняются как неизвестные поля.
Остальные поля сохраняются. ACTIVE проверяется под row lock: переход в DELETING блокирует позднее переименование.
Схема управляется только Flyway V2; Hibernate validate. teacher_id — внешний UUID, без FK в другую БД.

**Реализовано частично, зависит от этапа 4:** ENROLLED → 503 ENROLLMENT_UNAVAILABLE для STUDENT,
а не фиктивный пустой список. Чтение через student membership будет реализовано с course_student в этапе 4.
Удаление курса — этап 16; ни DELETE, ни cleanup, ни зачисления/расписание/материалы/чат не добавлены.

## Изменённые файлы этапа

В `services/education-service/`:

- Новые `src/main/java/tutor/educationservice/course/Course.java`, `CourseDto.java`, `CoursePage.java`.
- Новые `course/CourseRepository.java`, `CourseService.java`, `CourseController.java` в том же Java root.
- Новая `src/main/resources/db/migration/V2__course.sql`.
- `src/main/resources/application.yaml`: fail-on-unknown-properties для строгих input DTO;
  Identity DTO с явным JsonIgnoreProperties(ignoreUnknown=true) продолжают принимать upstream extensions.
- Новая `src/test/java/tutor/educationservice/CourseHttpTest.java`.
- `src/test/java/tutor/educationservice/EducationContextTest.java`, `EducationInfrastructureTest.java`:
  ожидают V1+V2 и course, сохраняют прежние проверки.
- `README.md`: действующие маршруты, permissions, примеры команд, ограничения этапов 4/16 и тесты.

Артефакты: stage-3-plan.md, этот отчёт, file_map.md/task_plan.md/progress.md/findings.md и перечисленные ниже логи.
Identity, контракты API, Compose/env/Dockerfile, V1 и чужие пользовательские изменения в этом этапе не менялись.
Изменения Identity в Git относятся к уже выполненному этапу 2 и сохранены.

## Тесты и команды

Все тесты курсов работают через настоящий HTTP, production JWT decoder с подписанными тестовыми JWT,
настоящую PostgreSQL 17 через Testcontainers и локальный HTTP Identity stub. Только S3 в этом классе
изолирован mock-клиентом; отдельный настоящий S3 suite полностью прошёл в том же полном запуске.

| Проверка | Фактический результат |
|---|---|
| `.\gradlew.bat test --tests '*EducationContextTest' --console=plain` | 5 тестов прошли; V2 + Hibernate mapping |
| Первый `.\gradlew.bat test --tests '*CourseHttpTest'` | 15 прошли, 2 упали из-за слишком строгого test helper |
| **`./gradlew test bootJar --console=plain`** | **BUILD SUCCESSFUL, 81 тест, 0 failures/errors/skipped** |
| `docker compose --env-file .env -f Docker/local/compose.yml config --quiet` | Успех |
| `docker compose --env-file .env -f Docker/local/compose.yml up -d --build` | Успешная multi-stage сборка и запуск |
| Flyway в существующей local БД | Был V1, стал V1+V2; прежний container ID и volume сохранены |
| liveness/readiness на localhost:8081 | Оба HTTP 200 UP; Education healthy |
| GET /api/v1/courses?scope=OWNED без JWT | HTTP 401 Problem JSON/code UNAUTHORIZED |
| Состав bootJar | Нет CourseHttpTest/прочих test controllers/keys |
| `git diff --check` | Успех |
| Скан текущих локальных паролей и ключа курсоров в исходниках/артефактах | Утечек нет |

`./gradlew` действительно выполнялся через Git Bash с JDK17; Docker выполнял Linux wrapper.
Identity suite не перезапускался, так как Identity в этом этапе не менялся.
Состав 81 теста: CourseHttpTest 17, SecurityHttpTest 28, IdentityLookupTest 11, ContextTest 5,
LocalProfileTest 5, InfrastructureTest 7, StorageUnitTest 5, CursorCodecTest 3.

Проверены: teacher vs teacher/student/admin/multiple roles; 201 Location и точный DTO; дубли названий;
пустые названия и text >255; invalid admin teacherId/Identity 503 без SQL-записи; hidden/DELETING 404;
строгий PATCH и сохранность остальных полей; scope/filter/limit; равные timestamps, страницы без дублей,
удалённый anchor и запрет переноса cursor между пользователями/scope/filter; concurrent create.

Гонка PATCH/DELETING проверяется реальной блокировкой строки: тест ждёт `pg_stat_activity.wait_event_type='Lock'`,
коммитит DELETING и получает 404 от ожидавшего PATCH, исходный title остаётся. Это не sleep-based имитация гонки.
Upgrade test применяет V1, создаёт marker с данными, применяет V2 и повторяет migrate; marker не теряется.
Отдельно реальный Compose применил V2 к сохранённому volume V1.

## Логи успехов и провалов

- [Полный успешный suite и bootJar](stage-3-test.log).
- [Первоначальные два падения helper, полный XML](stage-3-first-failure.xml).
- [Сборка образа и полный Compose up](stage-3-compose.log).
- [Состояние Flyway до обновления](stage-3-before-upgrade.log).
- [Окончательные SQL/test/probes/Compose проверки](stage-3-final-state.log).
- [Startup и реальное применение V2](stage-3-startup.log).
- [Первоначальная диагностическая команда с ошибкой quoting](stage-3-smoke-first-attempt.log).

Test helper сначала считал само слово teacherId в безопасном сообщении о требуемом параметре утечкой DTO.
Исправлен на проверку JSON-поля teacherId, сохранив проверки приватного title, upstream details и stack trace.
В диагностике SQL исправлено quoting: запрос теперь передаётся psql через stdin, exit codes проверяются.
Эти исправления не ослабляли проверки приложения и не отключали тесты.

## DoD этапа 3

- [x] Согласованная course schema, defaults/checks/indexes, без Identity FK и unique title.
- [x] POST/GET/PATCH/list, корректные 201 Location/200/400/401/403/404/422/503 и CourseDto.
- [x] JWT owner, реальный admin teacher lookup, несколько ролей и server-side права.
- [x] Чужие/удаляемые курсы не раскрываются; immutable поля не меняются.
- [x] OWNED/ALL, обязательный scope, keyset и scope-bound cursor, default/max limits.
- [x] ENROLLED/student reads честно помечены зависимыми от этапа 4, без ложных успешных ответов.
- [x] Миграция чистой БД и upgrade с V1, Hibernate validate, реальные PostgreSQL/race-тесты.
- [x] Все 81 тест, bootJar, image build, Compose config/up и readiness фактически прошли.
- [x] README, карта файлов, план, логи и ограничения актуальны. Этап 4 не начат.

## Границы

Нет membership, удаления курса, расписания, материалов и чата. Зависимость от доступности Identity сохраняется.
Production нагрузка и live E2E с production-пользователем Identity не проверены. Local stack оставлен запущенным;
тестовые данные находились в изолированных Testcontainers БД, в рабочую course тестовые строки не добавлялись.
Этап 4 — только после разрешения пользователя.
