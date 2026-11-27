# Ход работы

2026-11-20: аудит завершён, пять контрактов и план прочитаны. Каркас и инфраструктурный код созданы.
Docker Desktop запущен; существующие PostgreSQL/Redis/Kafka доступны.
Блокер: Quay возвращает 401 при pull MinIO/mc. Продолжить независимые части этапа, фиксировать реальные неуспехи.

- Gradle Kotlin DSL, Boot 4.1.1, JDK17, wrapper 9.8.2 с SHA-256.
- Созданы profiles, JPA/Flyway/Actuator, безопасный ObjectStorage, ключи и метаданные.
- Compose дополнен отдельной БД Education, MinIO + volume, mc bootstrap, приложением; existing services не изменены.
- Локальные секреты сгенерированы только в игнорируемом .env; приложение не использует root MinIO credentials.
- Multi-stage Docker image реально собран; JDK/JRE закреплены digest, runtime UID/GID 10001:10001.
- Полный compose config проходит. Полный up блокируется MinIO Quay 401.
- Диагностический `up -d --no-deps --build education` прошёл; миграция V1 применена к чистой PostgreSQL.
- Liveness 200 UP; readiness 503 DOWN без MinIO. Это ожидаемый отказ, не полная готовность.
- Первый test запуск был остановлен после 5м33с ожидания pull; затем повтор с TESTCONTAINERS_PULL_TIMEOUT=20.
- Подтверждены 5 unit и 3 PostgreSQL/context/HTTP теста. S3 suite initializationError (image pull).
- Исправлены компиляционные ошибки несовпадения Spring Boot LocalServerPort, AssertJ и Docker API; финальный повтор запущен.
- Linux `sh -n` bootstrap прошёл после исправления Windows mount пути. Git Bash `./gradlew --version` работает.
- README с реальными командами и ограничениями написан.

## Финальное состояние этапа 1

- Блокер Quay разрешён явным выбором Silo в ADR-001, как допускают пользователь и план.
- Server/client RELEASE.2026-11-12T00-00-00Z фактически скачаны и закреплены digest в Compose и тестах.
- Полный `./gradlew test bootJar` через Git Bash: BUILD SUCCESSFUL, все 15 тестов прошли.
- Первый Silo suite выявил смену случайного host-порта Docker при restart. Подтверждено отдельным контейнером;
  исправлено стабильным свободным host-портом в restart-тесте. Runtime-код приложения не обходился и не ослаблялся.
- Полный `docker compose ... config --quiet` и `up -d --build` прошли. Bootstrap exit 0, повторный запуск exit 0.
- Education, PostgreSQL Education и Silo healthy. Liveness/readiness HTTP 200 UP.
- Отдельный Compose smoke: PUT сервисным пользователем, anonymous 403, restart MinIO/Silo, тот же GET и DELETE успешны.
- В первом CLI smoke повторный `mc rm` отсутствующего объекта вернул ошибку CLI; финальный smoke использует один CLI DELETE.
  Идемпотентность ObjectStorage.delete проверена отдельно настоящим SDK integration test и проходит.
- Скан фактических локальных паролей по исходникам и артефактам: утечек нет. .env/build/.gradle игнорируются Git.
- Identity Service не менялся; существующие PostgreSQL/Redis/Kafka сохранили контейнеры и настройки.
- Итоговый отчёт: verification.md. Работа остановлена на границе этапа 1; этап 2 требует разрешения пользователя.

## Этап 2 — первичный прогон до разрешения lookup

Получено разрешение «Давай следующий этап». Выполнен только этап 2, без бизнес-маршрутов и изменения Identity.

- JWT проверяется настоящим RS256 decoder: public key Identity, issuer, время, UUID subject и массив ролей.
- CurrentUser/PermissionService отделяют роль от владения. Identity профиль проверяется на каждом API-запросе без кеша.
- Используется фактический GET /api/v1/users/me с cookie access_token (Identity игнорирует Authorization).
- Блокировка и отзыв ролей закрывают следующий запрос. HTTP/timeouts/bad schema fail closed; upstream данные не раскрываются.
- Security/MVC Problem JSON, DTO/UUID/limit/cursor validation, AES-256-GCM курсоры с scope и 24h TTL.
- Local/test профили стартуют независимо; PostgreSQL/Flyway migration re-run не теряет данные;
  keyset SQL проверен с равными timestamps и удалённой опорной строкой.
- `./gradlew test bootJar`: все 53 теста прошли (0 failures/errors/skipped); реальные PostgreSQL и S3 сохранены.
- Полный Compose config/up --build прошёл. Education healthy, liveness/readiness 200; без/с плохим JWT API 401 Problem JSON.
- Jar не содержит TestRoutes/TestJwt/SecurityHttpTest. Public key монтируется read-only; приватный ключ в Education не передаётся.
- Остановлена только подзадача lookup другого пользователя по UUID: маршрута нет, Identity менять запрещено.
  Минимальный предлагаемый GET /api/v1/directory/users/{id} описан в ADR-002; это не согласованный и не реализованный API.
- Обнаружено: dev_secrets/jwt_private.pem уже отслеживался Git до работ. Файл не читался/не менялся/не копировался в Education;
  уборка истории и ротация не выполнялись. Новые секреты находятся только в игнорируемом .env.
- Отчёт: stage-2-verification.md. Этап 3 не начинать; для закрытия оставшегося lookup нужно отдельное разрешение на Identity.

## Этап 2 — завершён полностью

После предложения точечного GET /api/v1/directory/users/{id} пользователь поручил «Заверши весь второй этап».
Lookup реализован в существующем DirectoryController/DirectoryService; контракт зафиксирован в ADR-002 и Identity endpoints.
Ответ ограничен UUID/ФИО/roles/blocked, TEACHER/ADMIN; blocked target виден, blocked actor запрещён.
Education IdentityClient.user подключён к настоящему маршруту: target 404 отдельно от timeout/ошибки связи/5xx → 503.

- Полный Education `./gradlew test bootJar`: 64 теста, 0 failures/errors/skipped.
- Полный Identity `./gradlew test bootJar`: 43 теста, 0 failures/errors/skipped.
- В Identity проверен настоящий HTTP с подписанным cookie JWT и PostgreSQL, в том числе блокировка с тем же JWT.
- Временная PostgreSQL Identity создана с динамическим host-портом; после проверки container ID удалена.
- Повторный полный Compose up --build успешен; рабочие БД/volumes сохранены.
- Отчёт и DoD обновлены. Этап 3 не начинать до разрешения пользователя.

## Этап 3 — завершён

Разрешение: «Давай тогда третий этап». Добавлены только course и четыре маршрута create/get/list/rename.
V2 создаёт course с checks/defaults/индексами и внешним teacher UUID без SQL FK.
Owner только из JWT, admin target проверяется существующим Identity lookup до SQL transaction.
GET/PATCH owner/admin only, DELETING и чужие скрываются 404; student PATCH — 403; DTO не раскрывает deletion_state.
PATCH меняет только title, неизвестные поля отклоняются 400. Блокировка строки не даёт PATCH коммитить после DELETING.
OWNED/ALL используют PostgreSQL keyset createdAt/id, limit+1 и scope-bound encrypted cursor.

- Первый CourseHttpTest: 15/17 прошли, два падения оказались чрезмерным запретом слова teacherId в helper проверки ошибок.
  Исправлен helper на проверку JSON-поля вместо имени параметра; XML первоначального провала сохранён.
- Полный `./gradlew test bootJar`: 81 тест, 0 failures/errors/skipped; все JWT/Identity/S3 регрессии проходят.
- Compose config/up --build успешны; V2 реально применена к предыдущей V1 в том же PostgreSQL container/volume.
- Контейнер Education healthy, оба probes 200 UP, protected API без JWT — 401 Problem JSON.
- В SQL диагностической команде исправлено quoting через stdin psql; исправленный финальный smoke проверяет exit codes.
- Identity не менялся в этом этапе. ENROLLED/student membership reads помечены частичными до этапа 4;
  ENROLLED возвращает 503 вместо фиктивной пустой страницы. Удаление курса остаётся этапом 16.
- README, карта файлов и stage-3-verification.md актуальны. Этап 4 не начинать без разрешения пользователя.

## Этап 4 — в работе

Разрешение: «Давай тогда дальше». План: stage-4-plan.md. Созданы V3/course_student,
EnrollmentController/Service, repository/entity/DTO; добавлены membership reads и ENROLLED.
Чтение и управление курсом разделены для защиты от повышения прав через сочетание TEACHER+STUDENT.
Identity lookup выполняется до SQL write transaction; mutations блокируют родительский course.
` .\gradlew.bat compileJava --console=plain` выполнен успешно (BUILD SUCCESSFUL).

После сжатия по просьбе пользователя перечитаны карта, планы, прогресс, findings, ADR и отчёты прошлых этапов,
раздел ЭТАП 4 и связанные правила REST/DB/requirements/chat; проверены наличие исходников и git status.
Обнаружены устаревшие границы этапа 3 в task_plan.md; обновлены на текущее состояние.
Тесты этапа 4 ещё не добавлены, старые migration/ENROLLED ожидания требуют обновления.
Полный test/bootJar, Compose upgrade V2→V3 и probes текущего кода не проверены. Этап 4 не завершён.
Identity и будущие этапы не расширять. Следующий шаг — тесты и исправления по их результатам.

## Этап 4 — проверки 20–21.11.2026

- Добавлены 14 HTTP/PostgreSQL тестов в существующий CourseHttpTest (теперь 31), без копирования стенда.
- Обновлены ожидания V3 в Context/Local/Infrastructure и ENROLLED; прежний V1→V2 upgrade закреплён target=2.
- 20.11 первый запуск JVM упал до тестов: Windows native memory allocation, errno=1455.
  Уменьшен начальный test heap до 64 MiB, максимум 384 MiB; Mockito self-attach избегает дополнительной JVM.
  При исчерпанной памяти повтор также не смог работать. Диагностика с повышенными правами тогда не прошла
  автоматическую проверку из-за лимита использования сервиса, без решения о небезопасности команды.
- 21.11 доступная память восстановилась, Docker Desktop запущен скрыто. Получены настоящие результаты:
  20/29 проходят, 9 падают, потому что Jackson требует отсутствующий primitive confirmConflicts.
  Исправлен необязательный DTO field на Boolean; первоначальный XML сохранён stage-4-first-failure.xml.
- Повтор CourseHttpTest: 29/29. Затем добавлены recheck roster после Identity и SQL lock гонки DELETE/PATCH.
- Полный `./gradlew test bootJar --console=plain --max-workers=1`: 95 tests, 0 failures/errors/skipped,
  BUILD SUCCESSFUL in 39s. Настоящие PostgreSQL/S3/privacy/restart/bootstrap прошли.
- README обновлён. bootJar не содержит тестовых классов/PEM. git diff --check успешен.
- Перед Compose upgrade БД по-прежнему V1+V2, container 9431f81f3151f7c0811d8f99e1b86c5d1eb3e02dcfbd398c83edfd6725afc843,
  volume tutor-platform-local_education_postgres_data. config --quiet прошёл; up -d --build ещё выполняется.

## Этап 4 — завершён 21.11.2026

- Полный Compose build/up exit 0, minio-init exit 0; Education healthy, liveness/readiness 200 UP.
- В сохранённом PostgreSQL применена V3, ID и volume не изменились. Нет business smoke-данных: обе таблицы пусты.
- Protected ENROLLED без JWT возвращает 401 Problem JSON. Авторизованное поведение проверено изолированными HTTP тестами.
- Все 95 тестов и bootJar прошли; финальный отчёт stage-4-verification.md, сводка XML stage-4-test-summary.log.
- Новые секреты в исходники/артефакты не попали; git diff --check успешен.
- README, task_plan, file_map и DoD актуальны. Identity в этапе 4 не менялся.
- Работа остановлена перед этапом 5. Расписание/материалы/чат/checker этапа 7 не реализовывались.

## Этап 5 — начат 21.11.2026

Разрешение: «Давай слеедующий этап». Перечитаны skills, планы, карта, прогресс и контракты разовых занятий/календарей.
Созданы V4, TimeInterval/CalendarService, single lesson entity/repository/service/DTO/controller.
SQL календарь использует overlap [monthStart,monthEnd) в Europe/Moscow; me — own TEACHER/current STUDENT union.
Mutations используют существующий course row lock; отмена физическая, неизвестный lesson после отмены — 404.
reason/confirmConflicts принимаются, но outbox/checker не реализованы (этап 7); серии не начаты (этап 6).
Общий Identity HTTP fixture выделен из CourseHttpTest, чтобы не дублировать стенд в ScheduleHttpTest.
Старые migration tests закреплены целевыми версиями, context/infra ждут V4. Новые тесты с настоящим HTTP/PG/JWT,
unit проверка московских границ под UTC/Los_Angeles/Tokyo, leap year. Java/test компиляция прошла;
целевой suite Course/Schedule/CalendarTime запущен, результат ещё ожидается.

## Этап 5 — завершён 21.11.2026

- Целевой suite: 47 tests, BUILD SUCCESSFUL in 21s, с первого запуска.
- Полный `./gradlew test bootJar --console=plain --max-workers=1`: 111 tests, 0 failures/errors/skipped,
  BUILD SUCCESSFUL in 37s. Настоящие PostgreSQL/S3/privacy/restart/bootstrap прошли.
- Compose config/up -d --build успешны. V4 применена к существующему PG container/volume;
  liveness/readiness 200 UP, Education healthy, minio-init exit 0. Неавторизованный schedule — 401 Problem JSON.
- SQL local single_lesson=0, тестовые записи в общей БД не создавались.
- README/планы/карта/отчёт stage-5-verification.md актуальны; bootJar не содержит test classes/PEM,
  скан действующих секретов чист, git diff --check прошёл.
- Провалов этапа 5 не было. Identity, Compose topology, env, S3 и будущие этапы не менялись.
- Расписание SINGLE готово в рамках этапа; серии (6), conflict-checker/outbox (7) ещё не реализованы.
  reason принимается, но события переноса/отмены пока не публикуются. Остановка перед этапом 6.

## Этап 6 — начат 21.11.2026

Разрешение «Дальше». Перечитаны skills/планы/карта/контракты серии и исключений.
Созданы V5, rule/exception entities/repositories/services/controller и pure RecurringCalendar.
Calendar course/me теперь объединяет SINGLE и RECURRING; exception query выбирает original-date window OR
actual moved interval, включая stopped series. Генерация не сканирует историю от startDate и не пишет строки уроков.
Все mutations используют existing course lock; отменённое вхождение сохраняется, повтор cancel 204,
перенос после cancel 409. Stop inclusive по originalDate; повтор same date 200, другая дата 409 без resume.
Weekdays проверяются API и SQL immutable predicate; UTC instants/Moscow dates разделены.
Обновлены cleanup/migration ожидания прежних тестов. Java/test compile прошли, schema/SINGLE regression запущен.
Новые series tests и полный suite/Compose ещё предстоят. Конфликты/outbox/Kafka не реализовывались.

- Schema/SINGLE regression: 20 tests, BUILD SUCCESSFUL in 18s.
- Первичный series suite: 15 HTTP/PostgreSQL tests + 1 timezone unit, всё прошло с первого запуска.
- Отдельно 15 HTTP tests прошли при JVM America/Los_Angeles через временный ignored Gradle init script.
  Это проверяет roundtrip LocalDate/LocalTime/PostgreSQL, а не только pure Java вычисления.
- По общим соглашениям REST список series подключён к существующему CursorCodec: Page,
  default30/max100, keyset createdAt/id DESC и actor/course scope; добавлен 16-й HTTP test.
- Проверен personal union TEACHER+STUDENT без дублей для recurring + single. README обновлён.
- Полный ./gradlew test bootJar запущен, ожидается результат. Compose V5 ещё не запускался.

## Этап 6 — завершён 21.11.2026

- Полный suite прошёл. Затем усилена точная регрессия: rule включает 15-е и исходное 25-е,
  перенос15→25/stop20 оставляет только перенесённое; полный suite повторён, BUILD SUCCESSFUL in 46s.
- 128 tests, 0 failures/errors/skipped; bootJar и реальный S3 suite успешны.
- Compose config/up --build успешны, V5 применена к прежнему PG container/volume; Education healthy,
  liveness/readiness 200 UP; bootstrap exit0; series API без JWT — 401 Problem JSON.
- Local lesson_series/lesson_exception пусты: авторизованные business сценарии проверялись в изолированных HTTP/PG tests.
- README/карта/планы/отчёт stage-6-verification.md актуальны, bootJar без тестов/PEM, скан secrets чист/diff check успешен.
- Провалов этапа 6 не было. Identity, Compose topology, env, S3, зависимости и будущие этапы не менялись.
- Конфликты/outbox/Kafka ещё не реализованы, этап 7 не начинался. Остановка до разрешения пользователя.

## Этап 7 — в работе

Разрешение «Давай дальше». Пользователь временно попросил также этап 8, затем отменил: завершить только текущий этап 7.
Созданы ScheduleConflictService, 409 safe Conflict DTO/handler; source scopes включают teacher + актуальных students
и все их ACTIVE courses. Self edit исключается по stable lesson identity. Confirm true всё равно вычисляет snapshot.
New series horizon = 30 Moscow days от max(startDate,today); enrollment без 30day cap использует конечные
окна вокруг start/stop/exception/fixed dates, покрывая weekly stable segments без генерации бесконечного календаря.
RecurringCalendar upper date исправлен на ceiling для arbitrary instant windows (month поведение сохранено).
Созданы V6 outbox, v1 event/writer (same SQL transaction), SKIP LOCKED publisher с bounded ACK/Kafka producer.
ADR-003 фиксирует внутреннюю новую схему; consumer не придуман. Kafka service/listeners не менялись,
Education env получил kafka:29092 и topic, добавлены Kafka client/testcontainers module.
Single/Series/Enrollment подключены к checker, перенос/отмена/stop пишут event. Повтор cancel/stop без изменения не пишет event.
CompileJava прошёл. Test fixtures обновлены: disabled scheduler во всех HTTP/local tests,
cleanup outbox/V6 ожидания/intentional overlap confirmation. Regression suite запущен, session 78188 проверить.
Новые business conflict tests и настоящий Kafka/PG recovery/restart/ACK-gap suite ещё предстоят;
полный test/bootJar/Compose/README/DoD не выполнены. Этап 8 не начинать.

## Завершение этапа 7

145 тестов без пропусков и ошибок, bootJar собран, Compose config/up успешны.
V6 на прежнем томе. Readiness и liveness HTTP 200 UP. Локально Education PG доступен на 15433.
Текущий итог — stage-7-verification.md. Остановка перед этапом 8.
