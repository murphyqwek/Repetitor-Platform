# Education Service — отчёт этапа 6

21.11.2026, Europe/Minsk. **Этап 6 завершён. Этап 7 не начинался.**

## Поведение

Добавлены бессрочные московские правила и исключения отдельных вхождений:

| Маршрут относительно /api/v1/courses/{courseId}/lesson-series | Результат |
|---|---|
| POST корень | owner/admin; startDate/weekdays/startTime/durationMinutes, 201 SeriesDto/Location |
| GET ?limit=30&cursor=... | owner/admin; Page items/nextCursor/hasMore, включая остановленные |
| POST /{seriesId}/stop | stopFromDate inclusive по исходной дате, 200 SeriesDto |
| POST /{seriesId}/occurrences/{originalDate}/reschedule | перенос только одного вхождения, 200 RECURRING DTO |
| POST /{seriesId}/occurrences/{originalDate}/cancel | постоянная CANCELLED exception, 204 |

weekdays — непустые уникальные ISO-дни 1..7, durationMinutes 5–300; возвращаются отсортированные дни.
startDate/startTime хранятся локально для Europe/Moscow, у новой серии stopFromDate=null.
Дата окончания и конечный горизонт не вводятся. При чтении вычисляется только запрошенный месяц
и предыдущий день для интервалов, пересекающих начало месяца; строки на каждый будущий урок не создаются.
Правило с началом десятилетия назад не требует обхода всех прошедших дат.

CalendarService объединяет SINGLE и виртуальные RECURRING в course/me календарях.
RECURRING: singleLessonId=null, стабильные seriesId+originalDate, UTC startsAt/endsAt;
rescheduled=true только для перенесённого вхождения. Сортировка startsAt, UUID single/series и originalDate.
Personal calendar учитывает текущие роли/владение/членство и не дублирует own+enrolled курс.
Ученик видит календарь, но не список правил или команды. ADMIN не получает глобальный me/schedule.

Переносы upsert одной exception под parent course lock и UNIQUE(series_id,original_date).
Исключения читаются по исходному диапазону **или** фактическому новому интервалу. Поэтому переносы между
месяцами, из будущего правила в более ранний месяц и из остановленной серии не теряются.
Первоначальное вхождение при переносе подавляется; причина сохраняется в lesson_exception.
Повторный cancel — 204; перенос отменённого — 409 LESSON_CANCELLED; восстановление не добавлено.
Неверная исходная дата/weekday — 404; чужой courseId для seriesId — 404.

Stop сравнивается с originalDate, а не новым временем: originalDate >= stopFromDate скрывается и
не изменяется (409 SERIES_STOPPED); более ранний перенос сохраняется после даты остановки.
**Проверена регрессия 15→25 декабря и stop20:** перенесённое 15-е показано 25-го,
а собственное исходное вхождение 25-го не показано. Перенос исходного 22-го на 19-е после stop20 тоже скрыт.
Техническое поведение повторного stop: та же дата — 200, другая — 409 SERIES_ALREADY_STOPPED;
API возобновления/массового редактирования нет. Stop до startDate допустим и оставляет пустое правило.

V5 создаёт lesson_series/lesson_exception, FK в пределах Education, array/interval checks, unique и индексы.
Immutable SQL predicate valid_lesson_weekdays проверяет размерность, диапазон, отсутствие null/дубликатов.
Список правил следует общему REST cursor-контракту: createdAt DESC/id DESC, limit 30/max100,
actor/course/sort-bound CursorCodec; удаление anchor не ломает продолжение.

**Не реализовано:** conflict-checker, outbox и Kafka-события LessonRescheduled/LessonCancelled/LessonSeriesStopped
остаются этапу 7. confirmConflicts принимается, но проверка пересечений/публикация не заявлены готовыми.
Identity Service, S3 и бизнес-маршруты будущих этапов не расширялись.

## Изменённые файлы

Относительно services/education-service:

- src/main/resources/db/migration/V5__lesson_series_and_exception.sql.
- src/main/java/tutor/educationservice/schedule/LessonSeries.java, SeriesDto.java, LessonException.java — entities/DTO.
- В том же пакете LessonSeriesRepository.java/LessonExceptionRepository.java — scope/page SQL и batch exceptions.
- LessonSeriesService.java/LessonSeriesController.java — пять маршрутов, parent lock, stop/cancel/upsert.
- RecurringCalendar.java — ограниченная месячная генерация и overlay исключений.
- CalendarService.java/TimeInterval.java — смешанные календари, actual overlap и стабильная сортировка.
- src/test/java/tutor/educationservice/SeriesHttpTest.java — 16 новых реальных HTTP/JWT/PostgreSQL тестов.
- src/test/java/tutor/educationservice/schedule/RecurringCalendarTest.java — timezone regression генератора.
- CourseHttpTest.java/ScheduleHttpTest.java в том же test root — cleanup exceptions/series; старый V3→V4 upgrade закреплён target=4.
- EducationContextTest.java/EducationInfrastructureTest.java — ожидания V1–V5 и обеих новых таблиц.
- README.md — series API, реальные команды, stop/переносы, ограничения этапа 7.

Артефакты: stage-6-plan.md, этот отчёт, task_plan/file_map/progress/findings и журналы ниже.
Временный timezone init script находится только в ignored build/stage6-timezone.init.gradle.
Новых библиотек/изменений runtime configuration нет. V1–V4/Compose/env/Dockerfile/S3/Identity source не менялись.
Прежние изменения Identity в Git относятся к завершённому этапу 2.

## Команды и результаты

Gradle — из services/education-service, Compose — из корня.

| Команда / проверка | Фактический результат |
|---|---|
| .\gradlew.bat test --tests '*EducationContextTest' --tests '*ScheduleHttpTest' --console=plain --max-workers=1 | 20 tests, BUILD SUCCESSFUL; V5/Hibernate smallint[] и SINGLE regression |
| .\gradlew.bat test --tests '*SeriesHttpTest' --tests '*RecurringCalendarTest' --console=plain --max-workers=1 | Первые 15 HTTP + 1 unit прошли с первого запуска |
| .\gradlew.bat test --tests '*SeriesHttpTest' --init-script build/stage6-timezone.init.gradle --console=plain --max-workers=1 | 15 реальных HTTP/PG тестов прошли с JVM America/Los_Angeles |
| **./gradlew test bootJar --console=plain --max-workers=1** | **128 tests, 0 failures/errors/skipped, BUILD SUCCESSFUL in 46s** |
| docker compose --env-file .env -f Docker/local/compose.yml config --quiet | Exit 0 |
| docker compose --env-file .env -f Docker/local/compose.yml up -d --build | Exit 0, реальный multi-stage JDK17/JRE17 build |
| Flyway в local PostgreSQL | V4 → V5, прежний container и volume сохранены |
| localhost:8081 liveness/readiness | HTTP 200 UP, Education healthy |
| GET lesson-series без JWT | HTTP 401 Problem JSON, UNAUTHORIZED |
| bootJar содержимое | Нет test classes/IdentityHttpStub/PEM keys |
| git diff --check / скан локальных credentials | Успех / совпадений нет, значения секретов не выводились |

Linux ./gradlew реально запускался через Git Bash и внутри Docker build.
Вначале полный suite прошёл; затем регрессия stop20 усилена наличием в правиле и 15-го, и 25-го
и полный suite повторён успешно. Production-код после полного прогона не менялся.
Отдельный LA прогон выполнен до добавления page-test; итоговый полный suite включает 16-й HTTP test пагинации.

128 tests = предыдущие 111 + SeriesHttpTest 16 + RecurringCalendarTest 1.
Используются production JWT decoder, подписанные test JWT, настоящие HTTP/Identity stub/PostgreSQL.
S3 health замокан только в business test-классах; полный запуск также прошёл настоящий Silo SDK
PUT/GET/DELETE/privacy/restart/bootstrap suite. Не было ослабления auth или успешных skip вместо Docker.
Identity suite не повторялся: Identity не менялся. В реальную local БД business smoke-данные не записывались;
lesson_series=0 и lesson_exception=0. Авторизованные сценарии проверены в Testcontainers, не с личным аккаунтом.

Проверены multiple weekdays, invalid 0/8/empty/duplicate/null, 5/300 и 4/301, строгий JSON;
границы года/февраля/месяца, crossing previous day, долгоживущее правило, обычные/перенесённые/отменённые;
повторный перенос с одним ID exception/новой reason, future original moved backwards,
stop inclusive/перенос после остановки/остановка до старта, owner/admin/student/multi-role/current membership.
Проверены общий keyset (равные timestamps/удалённый anchor/actor/course/limits), смешанный me без дублей.

Реальные concurrency tests: два переноса дают одну exception; cancel против переноса оставляет CANCELLED.
Для create/stop/reschedule/cancel ожидается pg_stat_activity Lock, затем коммитится DELETING;
все mutations возвращают 404 без изменения rule/exception. Нет sleep-based имитации.
Upgrade V4→V5 сохраняет course/member flag=false/single lesson; repeat migrate безопасен.
SQL проверяет массивы, FK, unique и согласованность kind/null/интервалов исключений.

PostgreSQL container до/после:
9431f81f3151f7c0811d8f99e1b86c5d1eb3e02dcfbd398c83edfd6725afc843.
Volume: tutor-platform-local_education_postgres_data. Общие PostgreSQL/Redis/Kafka не пересоздавались.

## Логи

- [V5/schema/SINGLE regression](stage-6-schema-test.log).
- [Первый series suite](stage-6-first-test.log), [реальный LA timezone suite](stage-6-timezone-test.log).
- [Полный suite/bootJar](stage-6-test.log), [сводка XML](stage-6-test-summary.log).
- [БД перед upgrade](stage-6-before-upgrade.log), [Compose build/up](stage-6-compose.log).
- [Startup/Flyway/bootstrap](stage-6-startup.log), [финальный SQL/контейнеры/probes](stage-6-final-state.log).
- [Секреты/diff](stage-6-hygiene.log).

Провалов компиляции/тестов/Compose на этапе 6 не было. Негативные статусы проверялись как ожидаемые результаты.

## DoD

- [x] V5 rules/exceptions: PostgreSQL arrays/constraints/FK/unique/indexes, UTC/Moscow, безопасный upgrade.
- [x] Create/list/stop и перенос/cancel одного вхождения; stable seriesId+originalDate и одна exception.
- [x] Permanent CANCELLED, повтор 204, запрет восстановления; inclusive stop и точная регрессия 15→25/stop20.
- [x] Бессрочная генерация за месяц, переносы между месяцами и из stopped/future series, mixed course/me calendars.
- [x] Права/current membership/blocked/multi-role, keyset списка, parent lock и реальные конкурентные тесты.
- [x] Moscow timezone независимо от JVM; HTTP/PG roundtrip в LA и pure tests UTC/LA/Tokyo.
- [x] 128 tests/bootJar, Docker/config/Compose, V5 на прежнем volume и probes успешны.
- [x] README/планы/карта/прогресс/логи актуальны; будущий этап 7 и Identity не расширялись.

Проверенная документация:
[Hibernate ORM arrays](https://docs.hibernate.org/orm/7.4/userguide/html_single/#basic-array),
[PostgreSQL array functions](https://www.postgresql.org/docs/17/functions-array.html).

Local stack оставлен запущенным. Остановка перед этапом 7 до разрешения пользователя.
