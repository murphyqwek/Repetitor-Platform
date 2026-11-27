# Assignment Service — пошаговая реализация для Codex

**Редакция:** 27.11.2026. **Назначение:** исполняемое ТЗ для coding agent. **Стек:** Java 17, Spring Boot, Gradle Kotlin DSL, PostgreSQL 17, Kafka, MinIO, текущая инфраструктура `docker/local`. Версии зависимостей взять из существующего проекта, не обновлять другие сервисы ради этого задания. **Вход:** все семь спецификаций из README и актуальные контракты Identity/Education.

## 0. Правила работы агента

1. Перед первым изменением прочитать requirements, db_design, rest/internal/kafka contract, test_plan и текущий этап этого файла. Изучить дерево репозитория, существующие Gradle modules, migration convention, DTO/error/security, compose, CI и тестовые профили. Не угадывать package/module name, пути запуска или текущий способ JWT. Если файл не найден — искать фактическое имя, не придумывать контракт.
2. Реализовывать ровно один этап за запуск, если пользователь не указал иной диапазон. В каждом этапе закончить код, миграции, ошибки, права, тесты; не менять бизнес-логику для удобства реализации. Не оставлять production stubs там, где DoD обещает функцию. Временный test adapter допустим только в test profile.
3. При конфликте документов действует последняя редакция из комплекта; не переписывать существующие исторические контракты молча. При обнаружении несовместимого контракта указать точные файлы и предложить минимальную адаптацию, продолжить независимые подзадачи. Дополнительные технические решения перечислены в requirements §13. JSON-импорт преподавателем, родители, Notification, прокторинг, полноценный математический парсер и агрегаты аналитики вне MVP.
4. Assignment — отдельная БД и MinIO credentials/bucket; никаких SQL к Identity/Education, публичных S3 URLs или доверия actorId из клиента. Spring Security, проверка актуального Identity, ProblemDetail; не ослаблять production auth для тестов. Spring transactional self-invocation учитывать. Все внешние HTTP/MinIO/Kafka вызовы вне долгой SQL транзакции; lock порядок по db_design.
5. Flyway incremental, `ddl-auto=validate`, SQL constraints и индексы, реальные PostgreSQL Testcontainers. Не использовать H2 для конкурентных тестов. Не добавлять таблицы сверх 36 без обоснования; tombstone reference — отдельно Education. Repository API и domain/application separation по стилю проекта, не строить лишние абстракции. Не добавлять зависимости без необходимости.
6. После каждого этапа — компиляция, тесты данного этапа + предыдущие, проверка git diff, отчёт: что изменено, файлы/миграции, команды и фактический результат, какие DoD выполнены, known blockers. Не писать «всё прошло», если не запускалось. Не пушить или коммитить без явной инструкции. Не перетаскивать секреты/артефакты сборки в git.

## Этап 1. Создание проекта и подключение MinIO

**Что:** отдельный Assignment Service, локальная инфраструктура, Dockerfile, конфигурация хранения и тестовый запуск.

**Как:**
- Найти корневую Gradle Kotlin DSL конфигурацию и существующие сервисы. Добавить модуль по их соглашениям: Spring Web, Validation, Security Resource Server, Data JPA/JDBC (принятый в проекте способ), Flyway PostgreSQL, Actuator, Kafka, MinIO SDK, Testcontainers/JUnit. Версии согласовать через BOM проекта; не смешивать несовместимые версии.
- Создать базовые пакеты по доменным модулям bank/exam/assignment/submission/review/practice/analytics/import/storage/integration/outbox; общие security/error/config. Не создавать преждевременно все пустые controllers.
- `docker/local`: добавить отдельную PostgreSQL DB/user/password для Assignment, параметры подключения Kafka, существующий экземпляр MinIO или подключить его, если отсутствует. Приватный bucket assignment-private, отдельные credentials с минимальными правами, не использовать root credentials в приложении, не читать education bucket. Init bucket через существующий способ (idempotent), без anonymous access. Учитывать реально доступный образ MinIO проекта; не заменять непроверенным тегом.
- Config `local`, `test`, production env; .env.example без секретов. Настроить multipart limits (ответ 20 MB плюс multipart overhead, проверка бизнес-размера отдельно), S3 endpoint, streaming timeouts; PostgreSQL UTC, pool limits, Hibernate validate. Dockerfile multi-stage или существующий шаблон, non-root где возможно, image build, health/readiness. Не требовать Kafka для liveness; readiness policy документировать.
- Storage adapter: put/get/delete/head private object, random immutable key, streaming, exception mapping, tests. Пока без пользовательских endpoints файлов. Для local integration использовать ту же инфраструктуру, не менять конфиги Identity/Education без причины.

**DoD:** сервис запускается из IDE/Gradle и Docker, compose валиден; own DB/bucket существуют, тестовый round-trip MinIO, public access denied; `/actuator/health` согласно security; конфигурация не содержит секретов; при отказе зависимости понятная диагностика. **Тесты:** T01.09, storage smoke T04.11/12 на уровне adapter; build/test, compose config, upload/read/delete exact bytes. Этап не обещает готовое бизнес-API.

## Этап 2. Security, внутренние клиенты и общие соглашения

**Что:** защищённая основа API, Identity actual checks, Education recipient/membership.

**Как:**
- Сверить действующий код Identity/Education: JWT/cookie/CSRF, issuer, audience, claims, маршруты пользователей. Согласовать service JWT выдачу/проверку с существующими ключами; добавить минимальные недостающие provider endpoints по internal contract. Проверка signature/expiry/type/scope/aud, не выдавать internal наружу. Если реальная инфраструктура не соответствует проектному варианту — фиксировать адаптацию в docs, не вводить третий auth.
- Identity client single/batch, parse strict DTO, blocked/incomplete/missing policy, no Redis cache; пользовательский запрос с валидным JWT всё равно проверяет актуальные права. Education resolve/membership, timeout 1s/3s, bounded retry только безопасных вызовов; no calls внутри длительных SQL locks.
- Global `application/problem+json` с code, bean validation, malformed JSON/UUID, authorization failures; cursor codec scoped to actor/sort/filter; common file security headers. Clock injectable. Подготовить HTTP stubs для зависимостей и тестовый issuer, а не insecure production profile.

**DoD:** 401/403/503 корректны, service JWT отдельно, actual Identity/Education provider contract tests или явно отмеченный blocker, нет обхода прав. **Тесты:** T01.03–08, T05.01–04. На этом этапе нет необходимости реализовывать материал references; они в этапе 6.

## Этап 3. Миграции базовых справочников, шкалы и конвертер

**Что:** subject/topic/exam_type/exam_definition/exam_structure_version/exam_position/exam_position_topic/grading_scale/grading_scale_version/scale_value (10 таблиц), endpoints §3.

**Как:**
- Создать последовательные миграции по db_design, composite FK current pointer, unique type/year/position/version, checks, indexes. Seeds только математика/3 типа, не выдумывать реальные экзаменационные структуры и шкалы. ID, UTC.
- Topic admin CRUD/deactivate; subject/exam types read. Структура создаётся атомарно, versioned copy + expected version. Scale full map 0..max, compatible same exam/structure, диапазоны и монотонность. `ExamScoreConverter.convert(scaleVersionId,primaryScore)` с точной версией. Старая версия неизменна.
- При обновлении структуры подготовить перенос совместимых позиций; выполнение переноса задач добавится на этапе 5, до этого task таблиц нет. Не считать функцию переноса завершённой раньше этапа 5. Не поддерживать специальные официальные условия перевода, пока реальные шкалы не проверены; при необходимости отдельный change request.

**DoD:** CRUD/чтение справочников, versioned structures/scales и conversion работают на реальной БД, invalid scale отклоняется. **Тесты:** T02.05/06/08/09 (конвертер; сквозное сохранение версии в этапе 9), T01.01/02 в части созданных таблиц. Проверить миграции fresh+upgrade.

## Этап 4. Файловый модуль, временные изображения и cleanup

**Что:** stored_file, cleanup_job (2 таблицы), image upload/preview/delete, safe lifecycle.

**Как:**
- Таблица metadata purpose/state, unique object key, TTL, content_hash, cleanup jobs with lease/token. Пока upload_submission_id/item FK добавить в этапе 9 после создания соответствующих таблиц. Не создавать фиктивные foreign tables. FileService создаёт UPLOADING до сетевого PUT, validate byte length and signature, READY после проверки, stream. Uploading owner only preview, no public S3.
- Реализовать worker TTL, delete outside SQL, retry/backoff; безопасное завершение загрузки: уникальный key, lease/token, stale PUT не может создать READY, durable deletion marker сохраняется на период reconciliation; если результат PUT неизвестен, периодически сверять orphan objects по собственному namespace. Не считать истечение lease доказательством отсутствия позднего PUT. Регулярная reconciliation повторно удаляет object keys без metadata; исключить in-flight uploads (metadata до PUT), не удалять key с active metadata/links. Для multipart abort unfinished uploads via storage lifecycle/adapter. Объём scanning ограничен pagination, prefix own bucket.
- В этапе 5 добавить task_version_file и учитывать shared references; в этапе 9 answer_attachment. До этих этапов API не выдаёт/не привязывает несуществующие контексты. Lock stored_file перед attach/delete, удаление metadata после подтверждения MinIO; stable cleanupJobId на repeat.

**DoD:** загрузка/preview/delete temp изображений, TTL и fault recovery, no direct storage links, late PUT не создаёт доступный файл и eventually cleaned. **Тесты:** T04.01/04/05/11/12, authorization временного файла; расширение T04.02/03/06 в этапах 5/9. Real MinIO, bounded tests.

## Этап 5. Банк задач, форматы, версии и классификация

**Что:** import_source (seed RESHUEGE, без сетевого парсера), task/task_version/task_version_exam_position/task_version_topic/task_version_file/deleted_imported_task (7 таблиц). Банк API §2 без материалов до этапа 6.

**Как:**
- Миграции origin checks, current pointer composite FK, unique external key, partial pending uniqueness, copied_from, versioned topics/positions, shared images, tombstone. Для source identity race lock по source/externalKey (advisory lock до task) при delete/import, не только проверка SELECT.
- Реализовать `AnswerValidator`, `AnswerChecker` strategy по 6 форматам, canonical hash нормализованных данных и image content hashes; maxScore правила. Parameterized tests, запрет partial autoscore, BigDecimal exact. `task_version` immutable после ACTIVE. Public/teacher DTO раздельно, access per role and owner.
- List scopes/filters OR topics/EXISTS position, keyset; create/update expectedVersion, no-op hash, copy, version history; admin manualOverride; deletion hide + job при отсутствии refs, tombstone atomic. Проверку ссылок template/practice/assigned добавить с таблицами позже; не допустить удаления истории на последующих этапах. Содержимое Markdown validate references, statement/explanation flags, file links, no external image/raw HTML.
- Завершить перенос классификации при новой структуре: сравнить совместимость по семантике/темам/max/check mode, создать новые immutable versions для безопасных позиций, сохранить старые mapping. При material links выполнять через Saga после этапа 6; для массового переноса не удерживать locks на всех задачах во время внешнего I/O. При конфликте правки пропустить с диагностикой, не перетирать. Проверить bank query plans.

**DoD:** полноценный банк без внешних материалов; импортированный fixture создаётся внутренним test path, не публичным admin create; правильные ответы скрыты от студентов, history pins. **Тесты:** T02.01–04/07, T03.01–06/09, T04.02/03/06; T03.07/08 расширить после создания связанных таблиц. Не обещать production parser до этапа 13.

## Этап 6. Материалы Education и Saga публикации версий

**Что:** task_version_material (1 таблица) и полноценная интеграция ссылок, дополнения в Education.

**Как:**
- Проверить existing Education material_reference POST/DELETE (сохранить маршруты/базовый DTO). Добавить owner/actor validation, metadata/content, service scopes, release tombstone в Education отдельной миграцией. Serialize referenceId POST/DELETE (advisory lock) + material_file lock, защита от удаления folder containing used file. Тесты provider side.
- В Assignment pending version + stable reference IDs, creator/owner, lease/token and error; worker register, commit ACTIVE only all linked, previous current stays until complete. Permanent error ABORTING -> release all known/unknown-outcome refs through cleanup. Tombstone protects late POST; no dangling reference after retry. Metadata snapshot, no shared DB. GET publication status, 202 with Location, preview linked materials. Не добавлять материал к imported task без владельца.
- Restart/reconcile, task deletion vs pending, stale lease; предыдущие ACTIVE версии и их изображения/материалы сохранять. Полная физическая очистка unused task освобождает только больше не нужные refs. Интеграция совместимых версий из этапа 5 через Saga.

**DoD:** version without materials sync; version with refs 202→ACTIVE; failure keeps old current, compensation confirmed, repeated remote calls idempotent; real Education integration tests before claiming done. **Тесты:** T05.05–12 (user download end-to-end после этапа 9), T03.05/07/08, T04.03/05. Проверить tombstone гонку lost response и late POST.

## Этап 7. Шаблоны и генерация экзаменационных вариантов

**Что:** assignment_template/template_item (2 таблицы), CRUD/copy/generate §4.

**Как:**
- Migrations revision, pinned task version/position/max score, FK and duplicate taskId validation. Lock template then tasks stable order; title/description limits. Version mismatch 409, atomic replacement, empty drafts allowed, new version hint. Template usage/delete rule будет дополнен в этапе 8.
- Teacher-owned/private/imported visibility; ADMIN edits existing as owner scope, no creation for other. Strict exam variant validate positions/check mode/structure/score, incomplete saved; generator FILL_EMPTY/REGENERATE_ALL, no duplicates, matching algorithm maximises number filled if candidates overlap; deterministic seeded randomness in tests, partial result explicit.
- No automatic version replacement, deleted task blocks publish and new selection. Copy independent ID. Task delete checks template references from now.

**DoD:** teacher can build ordinary/strict draft, get/list/copy/edit/generate, wrong owner rejected; revision/old versions stable. **Тесты:** T06.01–07, T03.07/08 (template refs), T01.06. Publish endpoint in next stage.

## Этап 8. Публикация ДЗ, course snapshots и минимальный Outbox

**Что:** assignment, assignment_course, assigned_item, submission, submission_course, deadline_reminder, outbox_event (7 таблиц); publication-preview, publish, teacher list/detail/revoke/archive/patch.

**Как:**
- Migrations: snapshot, idempotency receipt, teacher/actor, deadline revision, event sequence, review/score fields, unique(template_id,student_id), composite FK. Reminders 72/24/1h future only. Outbox insert service with event payload/seq, publisher later stage 12.
- Preview Education resolve + Identity batch; conflict report; publish re-resolve, validate eligibility, skip only explicit blocked/assigned. Bound 20 courses/500 recipients/100 items. Atomic SQL lock template/tasks, expectedRevision, validate strict complete/scale and due; insert common items once, submissions with effective deadline, course snapshots/reminders/events. Idempotency key actor+hash stable receipt saved same tx; read existing success first after auth, handle concurrent unique conflict after rollback. No network in SQL locks; course snapshot race accepted.
- Teacher GET filters, counts/status, history; PATCH allowed fields only, deadline revision and recalc reminders. Cancel if no submitted, archive otherwise; preserve active in archive, individual revoke only before cutoff, no reassign. Actual expiry finalization implemented stage 9: at this stage reject mutation of expired pending rows requiring finalization rather than incorrectly revoking; complete automatic finalization before closing stage 9.

**DoD:** whole assignment atomic, idempotent publication, no duplicate recipients or task copies, events persisted even Kafka down; history/filters by snapshot. **Тесты:** T07.01–09/10/12 non-expired, T12.01/02 and outbox rollback; T07.11/12 expired path finish next stage. Real PostgreSQL race tests.

## Этап 9. Выполнение, вложения, таймер, автоматическая сдача

**Что:** answer, answer_grade, answer_attachment (3 таблицы), upload context FKs; student API, autograding and expiry worker.

**Как:**
- Migrations composite FK answer→submission + assigned_item with same assignment, revision; grade constraints; attachment position and unique fileId. Update stored_file context fields/FKs. No premature materialization at publish.
- Student list/detail/task, context-aware images/materials/download, start idempotent; hide all exam contents before explicit start. Clock after locks, effective deadline min, canEdit false even worker delayed. Lock order assignment (shared where feasible)→submission→child/file. Mutations use same guard, state/time check. No current membership check for old work.
- Save/clear body and revision; allow partial matching / transient numeric draft, no correctness until submit. FILES optional photos also for auto. Upload outside SQL, recheck auth/time and per-item/total bytes/count under submission lock, attach directly, cleanup rejected late uploads. Download context authorization, no object_key, stream.
- Common submit service explicit vs expiration cause: materialize missing answers, auto scores pinned version and max, mixed SUBMITTED vs pure REVIEWED, pinned scale, reminders cancel, outbox. Blank explicit started work permitted; expiry no saved -> MISSED. Background worker scans effectiveDeadline, locks/rechecks; no Identity dependence. Repeated submit no duplicate effects. Finish deadline PATCH/archive interactions with expired rows and strict cutoff; no reopen even pending worker.
- All four reveal policies, initial auto score visibility, explanation image gate. Add teacher access for review file context; review API itself next stage.

**DoD:** student can execute full ordinary and timed exam, close/reopen, autosave, attach, submit or auto-expire; exact state/score, no late saves, no correct answers leaked. **Тесты:** T04.06–12, T08.01–12, T07.11/12, T05.11, T01.02 cross assignment, T12.04 (worker race). Real concurrency/MinIO and fixed Clock.

## Этап 10. Ручная проверка и публикация оценок

**Что:** review queue/summary/history, review start/grade/comment/publish/correction §8; новых таблиц нет.

**Как:**
- Teacher/ADMIN query owner scope, current Identity check; batch names, oldest-first keyset, status counters. All items full detail only after submit; file URLs context-specific. Start SUBMITTED→IN_REVIEW idempotent, no checker exclusive lock.
- Per item manualScore (0 distinct from null), comment, override auto unchanged; revision on submission and author/time. All writes under same submission lock, stale 409. Hide draft values/comments/aggregates from student and analytics.
- Publish require explicit score for every FILES incl blank; auto initial exists, effective score = coalesce, sum and converter pinned scale, REVIEWED/reviewedAt, single outbox event. Repeat no-op. PATCH reviewed atomic batch; validate all before changes, recalc totals/scale, revision, gradeUpdated event; no historical grade table, no reopening.

**DoD:** mixed work complete teacher review and student visible results only after publish, corrections coherent, original auto preserved. **Тесты:** T09.01–10, T02.09, T08.12, T01.06; assert DB and events, not only status code.

## Этап 11. Тренировки, аналитика, автоподбор

**Что:** practice_attempt (1 таблица), training/analytics/suggestions API §9–11.

**Как:**
- Migrations UNIQUE(student,task), composite FK historical version; list imported auto + per-student status, submit exact version, repeated identical request returns result, different conflict. No files/draft/second attempt, invalid does not consume. Lock task, protect delete vs attempt. Historical task and image routes remain available after hide.
- Analytics SQL union all REVIEWED grades + practice, ratio numeric, date submitted, distinct task count, topic N:M, no duplicate exam join, empty null, thresholds before rounding. ACL teacher current Education membership, student self, admin all, no parent; hide foreign IDs in exams. summary/topics/dynamics/weak topics/exams filters, UTC day/week/month, bounded buckets. No aggregate tables. Add indexes and check plans with realistic data.
- Suggestions read-only, weak 30d, weight allocation largest remainder, priority unseen (not assigned/attempted), dedup, fallback prior solved, partial warnings, no arbitrary replacement by strong topic, no auto-publish.

**DoD:** one practice attempt, historical result, accurate stats instantly after review correction, candidate scope and ACL. **Тесты:** T10.01–12, T03.07/08 final, E01/E04 partial. Confirm T10.06 43.33%, no integer division.

## Этап 12. Напоминания, Kafka Publisher, фоновые процессы

**Что:** publish previously persisted events, reminders, retry/cleanup robustness, monitoring. Таблицы уже существуют.

**Как:**
- Envelope + 8 payload types, stable eventId/sequence, `assignment.events.v1`, key aggregateId. Claim batch 100 with SKIP LOCKED and earliest unpublished per aggregate, lease 60s, short SQL then send/ack outside, CAS mark token; retry exp backoff+jitter max5min, poll 1s. `acks=all`, producer idempotence. Keep PUBLISHED 7d, never delete pending; acknowledge late duplicate possibility, consumer fixture dedup. Real Kafka Testcontainers.
- Reminder worker: lock assignment/submission/reminders, due revision checks, skip old/past, latest due only after downtime, canceled after terminal; insert event+mark in one tx. No parent. No separate table for expiry; stage 9 worker remains. Separate executors/pools and graceful shutdown; no long DB transactions.
- Cleanup worker and pending Saga scanning lease, retry safe; reconcile orphans with key namespace/metadata; add metrics structured logs for age/backlog, expiry lag, cleanup/saga, error counts. Do not include content/passwords/tokens; avoid high-cardinality labels. No Notification consumer in production.

**DoD:** durable events eventually published after Kafka restore; same eventId on ack-before-mark crash; reminders exactly one logical event and no stale enqueue after terminal; worker metrics. **Тесты:** T12.01–12, T04.05, T05.08–10, E05. Include Kafka outage and stale leases.

## Этап 13. Импорт: управление, worker, адаптеры и внешний источник

**Что:** import_config/import_job/import_job_error (3 таблицы), all 36 total; source/config/jobs/run/retry endpoints, parser module.

**Как:**
- Migrations source/config snapshot, deleted_at, job lease/status, partial unique active source, idempotency key/hash/receipt, retry link, error table and counters. Cron Spring six fields UTC, nextRunAt, source enable, missed runs coalesce/skip busy. Admin only, no arbitrary source URLs. Run/retry 202, poll; retry new job with old snapshot; terminal expired lease -> FAILED/INTERRUPTED, stale worker fenced.
- Interface SourceParser returning normalized candidate with source namespace/external ID, statement/explanation, answer formats, image references, exam mapping. Adapter registry by code, no separate microservice. Test adapter fixtures for all formats. Canonical hash include meaningful content, normalized image bytes hash, topic/mapping sorted; preserve prior mappings across import scopes where appropriate. No global content dedup that merges unrelated source IDs.
- Per-task operation: fetch/parse/images outside SQL, rate limit, timeout/retry/jitter, validate allowed host/size/MIME, prevent SSRF/redirect to private networks, sanitize HTML→Markdown/LaTeX. Under source identity+task locks recheck job lease, tombstone and manualOverride, unchanged skip, changed new version, commit counts with task operation. One failed task doesn't roll back others, failed uploads cleanup, no incomplete published task. Store safe errors, counts processed=sum outcomes, no stack traces in public. Terminal status+TaskImportFailed in one tx.
- **External-source gate:** verify permission and actual allowed interface/format/namespace for РЕШУ ЕГЭ before any live integration. Do not bypass site restrictions, use synthetic/authorized fixtures. If unresolved, leave production source disabled, implement explicitly marked fixture adapter in tests, document live adapter as blocker; rest of import infrastructure and platform can pass tests. Do not invent official structures/scales or claim real parser ready if not verified.

**DoD:** configured admin import works with approved source or test fixtures, repeat/update/manual override/tombstone and partial errors reliable; live readiness separately requires verified access and adapter contract. **Тесты:** T11.01–12, T03.09, T12.06/07 for import event, E04. Final table count 36 in Assignment (excluding Flyway metadata); provider tombstone Education separate.

## Этап 14. Интеграционная приёмка, документация и эксплуатация

**Что:** весь MVP, end-to-end с фактическими Identity/Education/MinIO/Kafka, smoke/performance, operational docs.

**Как:**
- Fresh install and upgrade from each prior migration; check all routes vs REST table, error contract, roles/multi-role, exact DTO fields. Verify no parent-only endpoints, no JSON mass import, no public internal routes, no correct answer leak, no credentials in logs. Check SQL constraints/composite FKs/unique and indexes. Review deadlock risk common lock order; tests force race and rollback.
- E01–E06: actual course/Identity setup, ordinary mixed, exam timeout, material Saga and deletion course, training+analytics+import fixtures, outages/restarts. Provider contract tests run both services. Validate event payloads and topic key, no producer assumes notification delivered.
- Load smoke with 500 recipients×100 tasks, only 100 common items and 500 submissions, lazy answers, list query counts (batch identity), concurrent saves, deadline workers, streaming file memory. EXPLAIN ANALYZE representative queries; measure p50/p95 not invent SLA. Do not load-test external source.
- README dev run/env setup/compose commands/health/metrics; API examples or OpenAPI generated from actual controllers if project uses it; deployment notes migrations, backup, Kafka and MinIO access, how to inspect failed jobs/leases and orphan cleanup. Record unresolved live parser, service-auth differences, official scales if any. No automatic rollout or external publication.

**DoD:** all scoped contract/test cases pass, no regressions, fresh compose start and E2E verified. If actual integration or live parser blocked, explicitly list affected cases and do not claim full readiness; independent parts remain deliverable. **Тесты:** full T01–T12 + E01–E06, report exact commands and results.

## 15. Карта покрытия и порядок выполнения

| Этап | Основные тесты | Накопительное число таблиц Assignment (без Flyway) |
|---|---|---:|
| 1–2 | T01, T05.01–04 | 0 |
| 3 | T02.05–09 | 10 |
| 4 | T04 storage | 12 |
| 5 | T02 formats, T03, T04 images | 19 |
| 6 | T05 Saga | 20 |
| 7 | T06 | 22 |
| 8 | T07, T12 enqueue | 29 |
| 9 | T08, T04 answer attachments | 32 |
| 10 | T09 | 32 |
| 11 | T10 | 33 |
| 12 | T12 | 33 |
| 13 | T11 | 36 |
| 14 | E01–E06, full suite | 36 |

Примечание: на промежуточном этапе возможны дополнительные миграции колонок/связей для таблиц, создаваемых позже. Не создавать всю схему заранее в обход порядка зависимостей и не объявлять DoD финального функционала до реализации его полного этапа. Приоритет — вертикальные срезы, сначала рабочая проверка ДЗ, затем аналитика, фоновые процессы и импорт.

### 15.1. Экраны учебного кабинета

Использовать существующий Next.js кабинет `/learn` и добавлять экраны после появления соответствующих REST-маршрутов. Браузер обращается к серверу Next.js с текущей сессией Identity. Сервер Next.js передаёт токен в Assignment без выдачи токена клиентскому коду. Показывать действия по роли и доступности операции из API, сервер повторно проверяет права.

| Этапы API | Разделы и сценарии фронтенда |
|---|---|
| 3–6 | Банк задач с фильтрами и предпросмотром. Преподаватель создаёт и редактирует свои задачи, загружает изображения, прикрепляет материалы. Администратор управляет справочниками, шкалами и существующими задачами в пределах API. |
| 7–8 | Конструктор ДЗ и пробных вариантов, выбор курсов и учеников, предпросмотр получателей, публикация и управление дедлайнами. |
| 9 | «Мои задания» для ученика. Карточка работы, явный старт пробного экзамена, таймер, черновик, ответы, вложения и сдача. Показ результатов и правильных ответов по политике назначения. |
| 10–11 | Очередь ручной проверки, оценивание и публикация результатов. Тренировки и аналитика с доступом по роли. |
| 13–14 | Управление импортом для администратора и сквозная приёмка сценариев по ролям. |

Для новых экранов применять общие токены оформления из `frontend/src/app/themes.css`. Пользователь может выбрать розовую, голубую, рыжую, красную или зелёную тему. Розовая сохраняет исходное нежно-розовое оформление и используется по умолчанию. Выбор применяется ко всему приложению и сохраняется в браузере.

## 16. Шаблон задания для Codex

```
Прочитай все документы docs/assignment/ и действующие контракты Identity/Education.
Изучи существующий код, структуру и соглашения репозитория.
Реализуй только ЭТАП N из assignment_service_codex_implementation_plan.md.
Не изменяй утверждённую бизнес-логику. Сначала сформируй краткий план файлов и миграций.
Выполни код, тесты, проверки DoD. Запусти тесты текущего и предыдущих этапов.
При несовместимости контракта явно зафиксируй проблему и продолжи независимые подзадачи.
В конце перечисли изменённые файлы, миграции, реальные команды и их результаты,
невыполненные тесты/блокеры, предложенные технические изменения. Не начинай этап N+1.
```
