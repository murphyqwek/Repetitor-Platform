# Education Service — отчёт этапа 4

21.11.2026, Europe/Minsk. **Этап 4 завершён. Этап 5 не начинался.**

## Результат

Реализованы текущие зачисления и права ученика. Identity Service в этом этапе не менялся;
используется lookup, согласованный и реализованный в этапе 2. Расписание, материалы и чат не добавлены.

| Маршрут | Поведение |
|---|---|
| POST /api/v1/courses/{id}/students | owner/admin; 201 + Location + StudentDto |
| GET /api/v1/courses/{id}/students | owner/admin; keyset enrolledAt DESC/studentId DESC, limit 30/max100 |
| DELETE /api/v1/courses/{id}/students/{studentId} | owner/admin; 204, удаление текущего членства; повтор 204 в доступном ACTIVE курсе |
| PATCH .../students/{studentId}/chat-permissions | owner/admin; обязательный Boolean, существующее членство; 200 studentId/canSendMessages |
| GET /api/v1/courses/{id} | дополнительно доступен текущему STUDENT участнику ACTIVE курса |
| GET /api/v1/courses?scope=ENROLLED | реальные ACTIVE курсы с текущим членством вызывающего STUDENT |

StudentDto: ровно studentId, displayName, blocked, canSendMessages, enrolledAt.
Имя и blocked берутся из Identity при запросе и не сохраняются как копии в Education.
Несуществующий target — 404 USER_NOT_FOUND; не-STUDENT — 422 INVALID_STUDENT;
blocked target — 409 STUDENT_BLOCKED; duplicate — 409 ALREADY_ENROLLED; dependency failure — 503.
Недоступный/исчезнувший профиль существующего участника при GET roster — 503; строка членства сохраняется.

V3 создаёт course_student: PK (course_id,student_id), FK только на course, default can_send_messages=true,
enrolled_at timestamptz, обратный индекс student/course и индекс keyset страницы.
Исключение физически удаляет связь. Повторное зачисление создаёт новый timestamp и flag=true.
Блокировка Identity запрещает доступ ученика, сохраняя членство; запрет отправки не закрывает чтение курса.
Список учеников недоступен STUDENT. Членство TEACHER+STUDENT не разрешает менять чужой курс/roster.

Identity lookup выполняется до SQL write transaction. Mutations сериализуются row lock родительского
course и повторно проверяют ACTIVE. Native INSERT ON CONFLICT и PK защищают конкурентный duplicate.
Roster закрывает SQL чтение до HTTP lookup, затем повторно проверяет доступность курса.
Курсоры непрозрачные и привязаны к actor/course/scope/sort; удалённая опорная строка их не ломает.

**Ограничение этапа:** confirmConflicts принимается как необязательное поле, но checker расписания
подключается только в этапе 7. Успешный enrollment не означает проверки отсутствия конфликтов.
Сохранность существующих сообщений при исключении пока не может проверяться: таблиц чата ещё нет.
DELETE этого этапа изменяет только course_student. Отправка сообщений и ANNOUNCEMENTS относятся к этапам чата.

## Изменённые файлы

Пути ниже относительно services/education-service:

- src/main/resources/db/migration/V3__course_student.sql — новая таблица/constraints/индексы.
- src/main/java/tutor/educationservice/course/CourseStudent.java — entity и составной immutable ID.
- В том же пакете CourseStudentDto.java, CourseStudentRepository.java — DTO, keyset, INSERT/DELETE/UPDATE.
- EnrollmentController.java, EnrollmentService.java — четыре маршрута, Identity checks, transaction boundaries.
- CourseService.java, CourseRepository.java — членство для GET/ENROLLED, отдельные права управления и row lock.
- src/main/java/tutor/educationservice/integration/identity/IdentityUser.java — displayName из актуальных ФИО.
- src/test/java/tutor/educationservice/CourseHttpTest.java — 14 новых HTTP/PostgreSQL тестов, всего 31;
  переиспользован существующий HTTP Identity стенд, добавлен callback для воспроизводимых гонок.
- EducationContextTest.java, EducationInfrastructureTest.java в том же test root — ожидания V1+V2+V3.
- build.gradle.kts — test heap 64–384 MiB и self-attach Mockito, без новой зависимости и изменения runtime heap.
- README.md — реальные команды enrollment/roster/permissions/exclude, права и ограничения.

Артефакты: stage-4-plan.md, этот отчёт, task_plan.md/file_map.md/progress.md/findings.md и логи ниже.
Compose, Dockerfile, env, S3, прежние миграции и пользовательские staged изменения не редактировались в этапе 4.
Изменения Identity, видимые в Git, относятся к ранее завершённому этапу 2.

## Команды и результаты

Gradle запускался из services/education-service; Compose — из корня репозитория.

| Команда / проверка | Фактический результат |
|---|---|
| .\gradlew.bat compileJava --console=plain | BUILD SUCCESSFUL |
| Первые test запуски 20.11 | JVM не стартовала/упала из-за native memory allocation Windows, errno=1455 |
| Первый выполненный CourseHttpTest 21.11 | 20/29 прошли; 9 отказов из-за необязательного primitive confirmConflicts |
| Повтор после исправления DTO | 29/29 прошли |
| **./gradlew test bootJar --console=plain --max-workers=1** | **BUILD SUCCESSFUL in 39s; 95 tests, 0 failures/errors/skipped** |
| docker compose --env-file .env -f Docker/local/compose.yml config --quiet | Exit 0 |
| docker compose --env-file .env -f Docker/local/compose.yml up -d --build | Exit 0; multi-stage JDK17/JRE17 build и bootstrap успешны |
| Flyway в сохранённой local БД | V1+V2 → V1+V2+V3; PostgreSQL container и volume сохранены |
| localhost:8081/actuator/health/liveness | HTTP 200 UP |
| localhost:8081/actuator/health/readiness | HTTP 200 UP; Education healthy |
| GET courses?scope=ENROLLED без JWT | HTTP 401 application/problem+json, UNAUTHORIZED |
| Состав bootJar | Нет тестовых классов и PEM keys |
| git diff --check | Exit 0; прежнее предупреждение CRLF в Identity requirements |
| Скан действующих локальных credentials в исходниках/артефактах | Совпадений нет; значения секретов не выводились |

Linux ./gradlew действительно выполнен через Git Bash, а также внутри Docker build.
Реальная PostgreSQL Testcontainers, production JWT decoder и подписанные тестовые JWT покрывают бизнес-маршруты.
Identity в этих тестах — настоящий локальный HTTP stub; текущая production auth не ослаблялась.
Сервисный S3 health здесь замокан, а отдельные реальные Silo/SDK/privacy/restart тесты прошли в том же полном suite.
Новые business smoke-записи в общей Compose БД не создавались: course=0, course_student=0.
Проверки авторизованных маршрутов выполнены в изолированных HTTP/PostgreSQL тестах, не с настоящим аккаунтом пользователя.

Состав 95 тестов: CourseHttpTest 31, SecurityHttpTest 28, IdentityLookupTest 11,
EducationContextTest 5, EducationLocalProfileTest 5, EducationInfrastructureTest 7,
StorageUnitTest 5, CursorCodecTest 3.

Особые проверки:

- Два POST одновременно проходят Identity и сохраняют ровно одну связь: 201 + 409.
- DELETING во время enrollment Identity lookup успешно коммитится без ожидания SQL lock, поздний POST даёт 404 без вставки.
- DELETE и chat-permissions ожидают реальный SQL lock; после commit DELETING возвращают 404 и не меняют связь.
- Roster повторно проверяет ACTIVE после Identity lookup и не отдаёт DTO удаляемого курса.
- Заблокированный участник остаётся в составе с актуальными именем/blocked; API для него запрещён.
- Equal timestamp keyset, удалённый anchor, cross-course/cross-actor cursor rejection, default/max limits.
- ENROLLED учитывает текущее членство/ACTIVE и поддерживает keyset после исключения anchor.
- Upgrade V2→V3 сохраняет исходный course, повторный migrate безопасен; PK/default/FK/indexes проверены PostgreSQL.

Container ID Education PostgreSQL до и после:
9431f81f3151f7c0811d8f99e1b86c5d1eb3e02dcfbd398c83edfd6725afc843.
Volume: tutor-platform-local_education_postgres_data. Существующие PostgreSQL/Redis/Kafka не пересоздавались.

## Провалы и логи

- [Первый Windows memory failure](stage-4-first-test.log).
- [Первый выполненный suite: девять ошибок optional primitive, XML](stage-4-first-failure.xml).
- [Успешный целевой прогон 29 тестов](stage-4-course-test.log).
- [Полный успешный suite и bootJar](stage-4-test.log), [сводка XML](stage-4-test-summary.log).
- [БД перед upgrade](stage-4-before-upgrade.log), [Compose build/up](stage-4-compose.log).
- [Startup/Flyway V3/bootstrap](stage-4-startup.log), [финальное состояние/probes/SQL](stage-4-final-state.log).
- [Проверка локальных секретов и diff](stage-4-hygiene.log).

Первоначальный primitive boolean confirmConflicts приводил к 400 при отсутствии необязательного поля
в Jackson 3. Исправлен на nullable Boolean; отсутствие поля проверяется успешными POST тестами.
Проверка canSendMessages остаётся обязательной @NotNull Boolean.
20.11 дополнительная диагностика памяти не прошла automatic approval review из-за usage limit сервиса;
команда не выполнялась. 21.11 review снова доступен, диагностика/проверки завершены; текущего блокера нет.
JVM crash-файлы удалены из build context, исходный failure сохранён в безопасном command log.

## DoD

- [x] V3, PK/FK/default/UTC/индексы без копий Identity и истории членства.
- [x] Проверки STUDENT/blocked/exists, 201 Location/DTO, 409 duplicate и конкурентная защита.
- [x] Физическое исключение, повторный 204, повторное зачисление с новым временем/flag=true.
- [x] Обязательный chat-permissions, owner/admin, текущее членство; чтение не закрывается флагом.
- [x] Приватный roster, актуальные профили, keyset/limit/cursor, 503 без удаления членства.
- [x] Student GET/ENROLLED, отзыв новых чтений после исключения, blocked и multi-role regression.
- [x] Короткие SQL mutations, Identity вне write transaction, row lock/recheck и реальные гонки.
- [x] Все 95 тестов, bootJar, Docker build, Compose config/up и probes успешны.
- [x] Local PostgreSQL upgraded без замены container/volume; README/артефакты актуальны.
- [x] Identity не менялся, будущие этапы не начаты; checker этапа 7 явно не заявлен реализованным.

Local stack оставлен запущенным. Этап 5 — только после разрешения пользователя.
