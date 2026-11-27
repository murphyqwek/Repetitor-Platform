# Education Service — отчёт этапа 5

21.11.2026, Europe/Minsk. **Этап 5 завершён. Этап 6 не начинался.**

## Поведение

Добавлены разовые занятия и календарь одного московского месяца:

| Маршрут | Результат |
|---|---|
| POST /api/v1/courses/{id}/lessons | owner/admin, 201 + SINGLE DTO/Location |
| PATCH /api/v1/courses/{id}/lessons/{lessonId} | owner/admin, перенос только этого урока, 200 |
| POST .../lessons/{lessonId}/cancel | owner/admin, физическое удаление, 204 |
| GET /api/v1/courses/{id}/schedule?month=YYYY-MM | владелец/admin/текущий ученик |
| GET /api/v1/me/schedule?month=YYYY-MM | собственные TEACHER и текущие STUDENT курсы, union без дублей |

Входные startsAt/endsAt — ISO timestamp с offset/Z; длительность строго 5–300 минут включительно,
без округления к минутам. Ошибка длительности — 422 INVALID_LESSON_TIME, формата — 400,
отсутствующего обязательного поля — 422. Ответы UTC с точностью микросекунд PostgreSQL.
DTO: courseId, kind=SINGLE, singleLessonId, seriesId=null, originalDate=null, startsAt, endsAt, rescheduled=false.
Перенос сохраняет singleLessonId/courseId/createdAt. Отдельных статусов/истории не добавлено.

month обязателен и каноничен YYYY-MM (год 0001–9999). Неверный месяц — 400 INVALID_MONTH.
CalendarService вычисляет границы через Europe/Moscow, не timezone JVM; выбирает пересечения
startsAt < monthEnd AND endsAt > monthStart. Возвращает полные времена, сортирует startsAt ASC/id ASC.
Прошедшие уроки доступны в запрошенном месяце; не вводится дополнительный запрет на прошлые даты.
me/schedule не даёт ADMIN глобального календаря: учитываются его роли TEACHER/STUDENT и личные связи.
Блокировка Identity, DELETING и снятие членства закрывают новые чтения; флаг чата чтение не ограничивает.

Все mutations используют существующий row lock course и повторную проверку ACTIVE.
lessonId дополнительно привязан к courseId. После cancel строка исчезает; повтор cancel/PATCH — 404,
восстановления нет. Idempotency-Key необязателен и не дедуплицирует создание.
V4 single_lesson: FK course, timestamptz starts/ends/created, точный SQL CHECK 5–300 минут,
ends>starts и индекс (course_id,starts_at). Прежние миграции и данные не менялись.

**Граница этапа:** пока только SINGLE. Регулярные серии — этап 6. Conflict-checker и transactional outbox
для LessonRescheduled/LessonCancelled — этап 7; reason/confirmConflicts принимаются, но reason ещё не
сохраняется и события не публикуются. Создание/перенос не подтверждают отсутствия конфликтов.
Интеграция уведомлений о переносе/отмене не объявляется завершённой.

## Изменённые файлы

Относительно services/education-service:

- src/main/resources/db/migration/V4__single_lesson.sql.
- src/main/java/tutor/educationservice/schedule/TimeInterval.java — точная длительность и UTC/microsecond нормализация.
- В том же пакете SingleLesson.java, SingleLessonRepository.java — entity и SQL overlap course/me calendars.
- LessonDto.java, SingleLessonService.java — согласованный DTO, create/move/cancel под parent lock.
- CalendarService.java, ScheduleController.java — Moscow month, role-bound personal union, пять маршрутов.
- src/test/java/tutor/educationservice/IdentityHttpStub.java — общий настоящий HTTP fixture, выделенный из CourseHttpTest.
- CourseHttpTest.java в том же test root — общий fixture, cleanup single_lesson и target=3 для прежнего upgrade test.
- ScheduleHttpTest.java — 15 новых HTTP/JWT/PostgreSQL тестов.
- schedule/CalendarTimeTest.java в том же test root — timezone JVM, 28/29 дней и граница года.
- EducationContextTest.java, EducationInfrastructureTest.java — ожидания V1–V4/single_lesson.
- README.md — API, permissions, реальные PowerShell-команды и незавершённые интеграции этапов 6/7.

Артефакты: stage-5-plan.md, этот отчёт, task_plan.md/file_map.md/progress.md/findings.md и журналы ниже.
Новых зависимостей нет. Identity source, Compose topology/env/Dockerfile/S3 и чужие staged изменения не менялись.
Прежние изменения Identity в Git относятся к завершённому этапу 2.

## Фактические команды и результаты

Gradle — из services/education-service, Compose — из корня.

| Команда / проверка | Результат |
|---|---|
| .\gradlew.bat test --tests '*ScheduleHttpTest' --tests '*CalendarTimeTest' --tests '*CourseHttpTest' --console=plain --max-workers=1 | 47 тестов прошли с первого запуска, BUILD SUCCESSFUL in 21s |
| **./gradlew test bootJar --console=plain --max-workers=1** | **111 tests, 0 failures/errors/skipped, BUILD SUCCESSFUL in 37s** |
| docker compose --env-file .env -f Docker/local/compose.yml config --quiet | Exit 0 |
| docker compose --env-file .env -f Docker/local/compose.yml up -d --build | Exit 0, настоящий multi-stage JDK17/JRE17 build |
| Flyway в настоящей local PostgreSQL | V1+V2+V3 → V1+V2+V3+V4, прежние container/volume сохранены |
| liveness/readiness localhost:8081 | HTTP 200 UP, Education healthy |
| GET /api/v1/me/schedule?month=2026-12 без JWT | HTTP 401 Problem JSON, UNAUTHORIZED |
| jar tf build/libs/education-service.jar | Нет test classes/IdentityHttpStub/PEM keys |
| git diff --check | Exit 0; прежнее CRLF warning в Identity requirements |
| Скан действующих локальных credentials в исходниках/артефактах | Совпадений нет, значения секретов не выводились |

./gradlew реально выполнялся через Git Bash, а также внутри Linux Docker build.
Состав 111: прежние 95 + ScheduleHttpTest 15 + CalendarTimeTest 1.
Business tests используют настоящий HTTP, production JWT decoder, подписанные test JWT,
изолированную PostgreSQL 17 и настоящий HTTP Identity stub. Mock S3 health только в этих классах;
настоящие Silo PUT/GET/DELETE/privacy/restart/bootstrap прошли в отдельном suite полного запуска.
Identity suite не перезапускался: Identity не менялся. Действующий аккаунт пользователя не использовался.

Проверены 5/300 минут, 4/301/0/отрицательная длительность, nanosecond за границами диапазона,
Z/offset эквивалентность и точность повторного чтения. Проверены strict JSON/UUID/month, независимость
Moscow month от UTC/Los_Angeles/Tokyo, февраль 28/29 дней, переход года и уроки на обеих границах месяца.
Проверены owner/admin/student/multi-role, cross-course lessonId, текущие membership, blocked,
personal union без дублей и отсутствие глобального ADMIN календаря.

Тест create/move/cancel против DELETING ждёт реальный pg_stat_activity Lock и затем коммитит переход:
все три mutations получают 404, исходная строка/время сохраняются. Нет sleep-based имитации гонки.
Upgrade V3→V4 сохраняет course и membership с flag=false; повтор migrate безопасен;
SQL constraints/FK/index/отсутствие status проверены на PostgreSQL.

Container PostgreSQL до/после:
9431f81f3151f7c0811d8f99e1b86c5d1eb3e02dcfbd398c83edfd6725afc843.
Volume: tutor-platform-local_education_postgres_data. Общие PostgreSQL/Redis/Kafka не пересоздавались.
В shared local БД не создавались тестовые уроки; single_lesson=0. Business сценарии — в Testcontainers.

## Логи

- [Первый целевой прогон, 47/47](stage-5-first-test.log).
- [Полный suite и bootJar](stage-5-test.log), [сводка XML](stage-5-test-summary.log).
- [БД перед upgrade](stage-5-before-upgrade.log), [Docker build/Compose up](stage-5-compose.log).
- [Startup/Flyway/bootstrap](stage-5-startup.log), [финальный SQL/probes/контейнеры](stage-5-final-state.log).
- [Проверка секретов и diff](stage-5-hygiene.log).

Провалов запуска, компиляции, тестов и Compose на этапе 5 не было.
Негативные сценарии 400/403/404/422 проходят как ожидаемые проверки ошибок.

## DoD

- [x] V4, SQL CHECK/FK/index, Hibernate validate, UTC; миграция без потери существующих данных.
- [x] POST/PATCH/cancel, точный DTO, 5–300 минут, offset input/UTC output, физическая отмена без восстановления.
- [x] Parent lock/ACTIVE, owner/admin, привязка lesson к course и реальные гонки с DELETING.
- [x] CalendarService/TimeInterval, обязательный Moscow month, overlap и сортировка, независимость от JVM timezone.
- [x] Course calendar и personal teacher/student union; current membership, blocked, multiple roles/admin scope.
- [x] 111 тестов/bootJar, Docker build/config/up, V4 на прежнем volume и readiness/liveness успешны.
- [x] README, планы, карта, прогресс и логи обновлены; Identity и будущие этапы не расширялись.
- [x] Checker/outbox явно обозначены незавершёнными до этапа 7; серии этапа 6 не начаты.

Официальная документация, проверенная при реализации:
[YearMonth JDK17](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/time/YearMonth.html),
[PostgreSQL 17 date/time types](https://www.postgresql.org/docs/17/datatype-datetime.html).

Local stack оставлен запущенным. Остановка перед этапом 6 до разрешения пользователя.
