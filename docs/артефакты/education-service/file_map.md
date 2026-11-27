# Карта файлов

## Источники истины

Все документы: `docs/архитектура/education-service/`:
`education_service_requirements_latest.md`, `education_service_db_design.md`,
`education_service_rest_api_contract.md`, `chat_history_api_contract.md`,
`chat_websocket_contract.md`, `education_service_codex_implementation_plan.md`.

## Существующие файлы

- `Docker/local/compose.yml`: проект tutor-platform-local; nginx, PostgreSQL 17, Redis 7.4, Kafka 4.1.1; стандартная сеть Compose.
- `services/identity-service/`: Java 17, Boot 4.1.2, Groovy DSL, wrapper Gradle 9.8.2; этап 2 добавляет только согласованный Directory lookup.
- `services/identity-service/src/main/java/tutor/identityservice/controller/AuthController.java`: образец форматирования.
- `.env`: локальные секреты, не печатать значения.
- `.env.exmaple`: существующий пример с опечаткой имени; добавить стандартный `.env.example`, сохранить старый файл.
- Корневые README, Dockerfile, .dockerignore и Compose уже имеют пользовательские staged изменения; не перезаписывать.

## Созданные файлы

- `services/education-service/`: Gradle wrapper/Kotlin DSL, Dockerfile, .dockerignore, README.
- `src/main/java/tutor/educationservice/`: приложение, инфраструктура хранения, минимальная защита health.
- `src/main/resources/`: application.yaml, application-local.yaml, application-test.yaml.
- `src/test/java/tutor/educationservice/`: unit и реальные Testcontainers integration tests.
- `Docker/local/`: bootstrap приватного MinIO и изменения существующего Compose.
- `docs/артефакты/education-service/`: план, карта, findings, progress и отчёт проверок.

## Важные точки реализации

- `integration/storage/StorageProperties.java`: проверка endpoint/bucket/credentials, redacted toString.
- `integration/storage/ObjectStorage.java`: владение потоками и контракт size=-1.
- `integration/storage/MinioObjectStorage.java`: SDK, потоковая передача, missing object и dependency errors.
- `integration/storage/StorageConfiguration.java`: клиент с timeouts, адаптер, readiness.
- `integration/storage/ObjectKeys.java`, `ObjectMetadata.java`, `StorageException.java`: ключи, метаданные, безопасные ошибки.
- `security/SecurityConfiguration.java`: public probes, Bearer JWT API, stateless, internal denyAll; этап 2.
- `EducationInfrastructureTest.java`: настоящий Silo/mc/PostgreSQL, включая restart на volume и privacy.
- `EducationContextTest.java`: реальная PostgreSQL, S3 mock только для независимого теста контекста.
- `StorageUnitTest.java`: конфигурация, ключи, метаданные, закрытие потока.
- `Docker/local/minio/bootstrap.sh`: приватный bucket и отдельный ограниченный S3 user.
- `ADR-001-local-object-storage.md`: явный выбор Silo после Quay 401, digests и границы решения.
- `verification.md`: полный список изменений, выполненные команды и DoD.
- `test-full.log`, `compose-up.log`, `compose-storage-smoke.log`, `startup.log`: успешные финальные проверки.
- `test-quay-failure.log`, `compose-quay-failure.log`, `s3-test-failure.xml`: сохранённые первоначальные провалы Quay.

## Этап 2 — актуальные точки входа

Пакеты ниже относительно `services/education-service/src/main/java/tutor/educationservice/`.

- `security/JwtConfiguration.java`: production RS256 decoder, public key, issuer/time/subject/role validators.
- `security/IdentityAccessFilter.java`: свежий профиль на каждом API-запросе, blocked, intersection ролей.
- `security/CurrentUser.java`, `PermissionService.java`, `Role.java`: контекст и права, включая owner/admin.
- `integration/identity/IdentityClient.java`, `IdentityProperties.java`, `IdentityProfile.java`: реальный /users/me,
  cookie адаптация, таймауты, fail closed; `IdentityUser.java` и user(token,id) — согласованный UUID lookup.
- `common/error/ApiException.java`, `Problems.java`, `ApiExceptionHandler.java`: безопасные Problem JSON в Security/MVC.
- `common/pagination/CursorCodec.java`, `CursorProperties.java`: AES-GCM, scope/expiry, keyset position, limits.
- `common/TimeConfiguration.java`: UTC Clock.
- `src/test/.../TestJwt.java`: RSA keys в памяти, временный public PEM, подписанные test JWT.
- `src/test/.../SecurityHttpTest.java`: реальный HTTP + PostgreSQL + Identity HTTP stub, test-only controllers.
- `src/test/.../EducationLocalProfileTest.java`, `EducationContextTest.java`: local/test + Flyway/keyset на PostgreSQL.
- `src/test/.../common/CursorCodecTest.java`: криптографические/негативные проверки курсора.
- `IdentityLookupTest.java`: настоящий HTTP target lookup, blocked/roles, 404 vs 503, таймауты/redirects/malformed.
- `services/identity-service/.../DirectoryController.java`, `service/DirectoryService.java`: lookup и limited DTO.
- `services/identity-service/.../IdentityServiceApplicationTests.java`: real HTTP/signed cookie JWT/PostgreSQL.
- `services/identity-service/.../controller/IdentityApiTest.java`, `service/DirectoryServiceTest.java`: права/валидация/404/поля.
- `docs/архитектура/identity-service/endpoints.md`: принятый контракт lookup.
- `ADR-002-identity-integration.md`: принятая адаптация и реализованный lookup контракт.
- `stage-2-plan.md`, `stage-2-verification.md`: завершённый этап 2 и DoD.
- `stage-2-test.log`, `stage-2-compose.log`, `stage-2-smoke.log`, `stage-2-startup.log`: фактические проверки.

Итог: 64 теста Education и 43 Identity проходят, jar обоих сервисов, image/Compose/probes успешны.
Identity изменён только для согласованного lookup. Остатков этапа 2 нет; этап 3 не начинался.
Дополнительные логи: stage-2-identity-test.log и stage-2-test-summary.log.

## Этап 3 — актуальные точки входа

Пакет относительно `services/education-service/src/main/java/tutor/educationservice/`:

- `course/Course.java`: JPA entity, immutable owner/createdAt, NORMAL/ACTIVE defaults, title rename.
- `course/CourseDto.java`, `CoursePage.java`: только согласованные публичные поля.
- `course/CourseController.java`: POST/GET/PATCH и GET list; строгие request DTO, 201 Location/no-store.
- `course/CourseService.java`: owner/admin, admin Identity lookup до SQL transaction, scope/filter/курсоры.
- `course/CourseRepository.java`: active reads, PATCH PESSIMISTIC_WRITE, native PostgreSQL tuple keyset.
- `src/main/resources/db/migration/V2__course.sql`: course, checks/индексы, без Identity FK и unique title.
- `application.yaml`: fail-on-unknown-properties, чтобы PATCH не принимал immutable/неизвестные поля.
- `src/test/.../CourseHttpTest.java`: 17 реальных HTTP/PostgreSQL тестов, JWT и Identity HTTP stub,
  включая гонку с DELETING, удалённый anchor, equal timestamp pagination и upgrade с V1.
- `EducationContextTest.java`, `EducationInfrastructureTest.java`: теперь проверяют V1+V2 и таблицу course.
- `stage-3-plan.md`, `stage-3-verification.md`: закрытый DoD этапа 3 и границы этапа 4.
- `stage-3-test.log`, `stage-3-compose.log`, `stage-3-before-upgrade.log`, `stage-3-final-state.log`, `stage-3-startup.log`: фактические проверки.
- `stage-3-first-failure.xml`, `stage-3-smoke-first-attempt.log`: первоначальные ошибки тестового helper/quoting, затем исправленные.

Итог: 81 тест Education прошёл, jar/image/Compose/probes успешны. В существующей local PostgreSQL V1 → V2,
container ID и volume сохранены. Identity source и его тесты не менялись в этапе 3.
ENROLLED явно 503 ENROLLMENT_UNAVAILABLE до этапа 4; student reads — только после membership этапа 4.
DELETE курса — этап 16. Этап 4 не начат.

## Этап 4 — завершён 21.11.2026

- `course/CourseStudent.java`, `CourseStudentRepository.java`, `CourseStudentDto.java`: членство, SQL mutations/keyset и DTO.
- `course/EnrollmentService.java`, `EnrollmentController.java`: roster/enroll/exclude/chat-permissions;
  Identity lookup до write transaction, mutations под lock родительского course.
- `course/CourseService.java`, `CourseRepository.java`: текущее членство для чтения/ENROLLED;
  управление отделено от чтения, чтобы TEACHER+STUDENT не менял чужой курс через membership.
- `integration/identity/IdentityUser.java`: displayName из актуального профиля.
- `src/main/resources/db/migration/V3__course_student.sql`: PK course/student, FK только course, defaults и индексы.
- `stage-4-plan.md`: текущий DoD и решения.
- `CourseHttpTest.java`: 31 HTTP/PG тест, из них 14 новых для этапа 4, общий существующий Identity HTTP стенд.
- `EducationContextTest.java`, `EducationInfrastructureTest.java`: ожидают V1+V2+V3 и course_student.
- `build.gradle.kts`: test heap 64–384 MiB, Mockito self-attach; production JVM не менялся.
- `README.md`: API/permissions/enrollment примеры и явная граница checker этапа 7.
- `stage-4-test.log`, `stage-4-test-summary.log`: 95 tests, 0 failures/errors/skipped, bootJar успешен.
- `stage-4-first-test.log`: первый Windows OOM; `stage-4-first-failure.xml`: Jackson optional primitive ошибка.
- `stage-4-course-test.log`: успешный целевой прогон 29 тестов до двух дополнительных гонок полного suite.
- `stage-4-before-upgrade.log`, `stage-4-compose.log`: PostgreSQL V2 до upgrade и текущая сборка/Compose.

Тесты/bootJar текущего кода успешны (95). Compose/config/image успешны, PostgreSQL V3 на том же container/volume,
probes 200 UP. `stage-4-verification.md` — итог и закрытый DoD; `stage-4-final-state.log`, `stage-4-startup.log`
подтверждают запуск и upgrade. `stage-4-hygiene.log` — проверка локальных секретов и diff.
Identity в этапе 4 не менялся. Работа остановлена перед этапом 5; требуется новое разрешение.

## Этап 5 — завершён 21.11.2026

Разрешение получено после отчёта этапа 4. Текущий план stage-5-plan.md.
Пакет относительно services/education-service/src/main/java/tutor/educationservice/:

- schedule/TimeInterval.java: точные границы длительности, offset input → UTC с микросекундами.
- schedule/SingleLesson.java, SingleLessonRepository.java: entity, FK course, overlap SQL course/me calendars.
- schedule/LessonDto.java: согласованный календарный SINGLE DTO без status.
- schedule/SingleLessonService.java: create/move/cancel под existing parent course lock.
- schedule/CalendarService.java: обязательный YYYY-MM, Europe/Moscow, interval overlap, owner/member personal union.
- schedule/ScheduleController.java: пять маршрутов этапа 5, строгие request records/no-store.
- src/main/resources/db/migration/V4__single_lesson.sql: constraints/index/UTC, прежние миграции не менялись.
- src/test/.../IdentityHttpStub.java: вынесен существующий HTTP fixture для CourseHttpTest и ScheduleHttpTest;
  оба класса используют отдельные экземпляры, отдельные PG containers и Spring contexts.
- src/test/.../ScheduleHttpTest.java: HTTP/JWT/PG сценарии этапа 5; schedule/CalendarTimeTest.java — JVM timezones/leap month.
- CourseHttpTest: теперь использует общий fixture, cleanup single_lesson; старый upgrade V2→V3 закреплён target=3.
- Context/Infrastructure tests: ожидания четырёх миграций и single_lesson.

Целевой suite 47/47 прошёл с первого запуска; полный test/bootJar — 111, без ошибок/пропусков.
Compose/config/image/probes успешны, V4 применена к прежнему PG container/volume.
Итог и DoD: stage-5-verification.md. Логи stage-5-first-test/test/test-summary/compose/before-upgrade/startup/final-state/hygiene.
README обновлён. Identity не менялся. Остановка перед этапом 6 до разрешения пользователя.
После сжатия перечитать этот раздел, stage-5-plan, progress/findings и соответствующие контракты.

## Этап 6 — завершён 21.11.2026

Текущий план stage-6-plan.md, разрешение «Дальше» после закрытого этапа 5.
Пакет относительно services/education-service/src/main/java/tutor/educationservice/schedule:

- LessonSeries.java/SeriesDto.java, LessonException.java: JPA entities, smallint[]/Moscow rule, stable originalDate, permanent CANCELLED.
- LessonSeriesRepository.java/LessonExceptionRepository.java: scope SQL, batch exception query по original date OR actual interval.
- LessonSeriesService.java/Controller.java: create/list/stop/reschedule/cancel под parent course lock.
- RecurringCalendar.java: месячная генерация (+previous day), overlay exceptions, сохранение old moved после stop.
- CalendarService.java/TimeInterval.java: смешанный SINGLE/RECURRING, overlap, stable startsAt/key sorting, current personal scopes.
- src/main/resources/db/migration/V5__lesson_series_and_exception.sql: rules/exceptions/constraints/indexes,
  immutable valid_lesson_weekdays SQL predicate для проверки массива, без новых библиотек.
- CourseHttpTest/ScheduleHttpTest cleanup: сначала exceptions/series. Старый upgrade V3→V4 закреплён target=4.
- EducationContextTest/InfrastructureTest: ожидают V1–V5 и обе новые таблицы.

Schema/SINGLE regression suite 20/20 успешен. SeriesHttpTest (16 tests), RecurringCalendarTest (1);
первичный suite 15 HTTP + 1 unit прошёл, затем 15 HTTP прошли с JVM America/Los_Angeles.
Общая cursor pagination списка серий: SeriesDto.Page, CursorCodec и SQL page; README актуален.
Логи: stage-6-schema-test.log, stage-6-first-test.log, stage-6-timezone-test.log; временный Gradle init только в ignored build/.

Итог: 128 tests, 0 failures/errors/skipped, bootJar/Compose/config/image успешны. V5 на прежнем PG container/volume,
liveness/readiness 200 UP, Education healthy. Точная регрессия включает в правиле и 15-е, и исходное 25-е.
SeriesDto.Page + CursorCodec обслуживают GET rules; keyset/limits/scope/anchor проверены.
stage-6-verification.md — финальный отчёт/DoD. stage-6-test-summary/compose/before-upgrade/startup/final-state/hygiene.log — доказательства.
Identity не менялся. Остановка перед этапом 7, нужны новое разрешение и отдельный план checker/outbox.

## Этап 7 — в работе

Разрешён только этап 7; пользователь отозвал промежуточное поручение продолжать этап 8.
Текущий план stage-7-plan.md. Пакеты относительно services/education-service/src/main/java/tutor/educationservice:

- schedule/ScheduleConflictService.java: общие teacher/student scopes и snapshot ACTIVE schedules,
  single/series/self-exclusion; new series 30 Moscow days; enrollment конечные boundary windows для будущих weekly patterns.
- schedule/ScheduleConflictException.java и common/error/ApiExceptionHandler.java: safe 409 conflicts без foreign course/lesson IDs.
- CourseRepository.participantCourses/CourseStudentRepository.studentIds и schedule repos findBy...In: внутреннее чтение.
- schedule/RecurringCalendar.toDate: ceiling last date для произвольных интервалов, не только month windows.
- SingleLessonService/Controller, LessonSeriesService/Controller, course/EnrollmentService/Controller:
  confirmConflicts действительно передаётся и проверяется, mutations сохраняют outbox в той же transaction.
- outbox/ScheduleEvent.java, OutboxWriter.java: внутренний v1 JSONB event, old/new/reason, mandatory transaction.
- outbox/OutboxProperties.java, OutboxConfiguration.java, OutboxPublisher.java:
  raw Kafka producer, enabled config, scheduled worker, pending row SKIP LOCKED/ACK/published; bounded timeouts.
- src/main/resources/db/migration/V6__outbox_event.sql: outbox без FK, partial pending index.
- application.yaml/test.yaml, build.gradle.kts, .env.example, Docker/local/compose.yml:
  kafka-clients + Testcontainers Kafka, bootstrap local/Compose, test scheduling disabled.
- TestJwt.properties отключает outbox publisher и для local profile integration tests.
- Course/Schedule/Series tests: cleanup outbox, intentional overlaps подтверждаются true; старый upgrade V4→V5 target=5.
- Context/Infrastructure ожидают V6/outbox. compileJava прошёл; regression run stage-7-first-test.log, session 78188 ещё проверить.
- ADR-003-schedule-events-v1.md: новый внутренний event/topic/key contract, не notification consumer.

Новые ConflictHttpTest и настоящий Kafka/PG suite ещё не созданы. Полного test/bootJar/Compose V6 пока нет.
README пока описывает прежнее отсутствие checker/outbox — обновить перед завершением.
После сжатия перечитать этот раздел, stage-7-plan, progress/findings/ADR-003 и контракты.

Итоговые файлы этапа 7:
- schedule/LessonSeries.java — JDBC 4.2 LocalTime для московского start_time без сдвига через JVM timezone.
- src/test/java/tutor/educationservice/ConflictHttpTest.java — 13 HTTP/PostgreSQL проверок, включая rollback и V5 → V6.
- src/test/java/tutor/educationservice/OutboxKafkaTest.java — Kafka 4.1.1 и PostgreSQL 17, два publisher, rollback, отказ и ACK-gap.
- services/education-service/README.md — правила подтверждения, конфигурация Kafka, команды запуска тестов.
- stage-7-verification.md — итоговый DoD и ссылки на логи.
