# Online Lesson Service — пошаговый план реализации для Codex

Редакция 28.11.2026. Исполняемая спецификация, не отчёт о коде. Перед началом прочитать README и все документы комплекта плюс действующие Identity/Education/Assignment contracts. Стек Java17, Spring Boot и Gradle Kotlin DSL по репо, PostgreSQL17, Mongo replica set, MinIO, Kafka, LiveKit. Выполнять один этап за запуск, если не указан диапазон. Не обновлять чужие сервисы/версии без необходимости, не коммитить/пушить без разрешения. Сначала inspect реальный layout, auth/CSRF, Gradle wrapper, compose, conventions, existing endpoints/events. При противоречии фиксировать точный источник, не изобретать контракт, продолжать независимое. Никаких production stubs, секретов в Git, H2 вместо PostgreSQL, direct S3 URLs, cross-service DB, Redis/HA/CRDT. Не заявлять tests passed без запуска.

## Общий Definition of Done
Для каждого этапа: соответствующие миграции/индексы, валидация/ошибки/security, реализация и тесты (positive/negative/concurrency where relevant), `./gradlew test` и `bootJar` фактического модуля, regression прежних этапов, compose config/build/up при infra changes, git diff review. Report список файлов, выполненное поведение, команды и фактические результаты, невыполненные критерии/риски. Не начинать следующий этап автоматически. Если frontend часть принадлежит партнёру — оформить проверяемый контракт/harness, не считать её сделанной.

## Этап 0. Проверка технических рисков и репозитория
**Что:** подтвердить совместимость, не начинать основную бизнес-логику.
**Как:** проверить реальные versions, JWT/cookie/CSRF, internal service auth, existing outbox, course/schedule mutations. Изолированные PoC: LiveKit real server create/delete/token/mute/update, auto-create off и stale token against retired room, browser WSS/ICE/TURN; Mongo single-node replica set transaction + receipt atomic rollback; Angular React wrapper Excalidraw onChange/updateScene/addFiles, remote update feedback, versions/bindings/order, per-user undo, page switch, PDF.js raster, LaTeX/graphs/geometric tools feasibility. Зафиксировать версии, лицензии сторонних extensions, measured results, deployment ports. Не встраивать непроверенное стороннее расширение. При невозможности math tool сообщить gap, не выкидывать ТЗ.
**DoD:** reproducible commands, PoC report, known risks and decisions, no unverified production claims. **Tests:** T01.03, T06.01–03 smoke, T12.04, T17.03 partial.

## Этап 1. Инициализация сервиса и инфраструктура
**Что:** Spring Boot app, Dockerfile, own SQL DB, Mongo replica set, LiveKit, existing MinIO bucket, Kafka.
**Как:** module/layout по repo; Web MVC, security, validation, JPA/Flyway, Mongo, STOMP, Kafka, MinIO SDK, LiveKit SDK, Actuator/Testcontainers (добавлять зависимости по мере надобности). Compose persistent volumes, rs.initiate idempotent, reachable replica advertised host from app/test, health checks, separate DB users/MinIO creds, private bucket init, env.example without secrets, public LiveKit endpoint config. Dockerfile multi-stage/non-root where possible. No Redis. Smoke SQL/Mongo txn/MinIO bytes/LiveKit Room API, readiness/degradation policies. Не менять существующие сервисы без необходимости.
**DoD:** IDE+Docker startup, real Mongo tx, private bucket roundtrip, config valid, health. **Tests:** T01.01 partial,T01.02, storage/media smoke.

## Этап 2. Модель данных и общая security
**Что:** 11 таблиц, Mongo indexes, base DTO/error/security.
**Как:** incremental Flyway по db_design, partial unique occurrence/teacher/admin/session, check constraints, UTC, actual ID convention. Create collections/indexes; verify fresh+upgrade. Clock, ProblemDetail, cursor codec, request IDs, service JWT auth, provider client strict parsing/timeouts. No endpoint stub promising behavior. Introduce bounded per-lesson lane with fairness and lifecycle cleanup, tests parallel lessons/same lane ordering; enforce singleton deployment. 
**DoD:** schema valid, constraints proven, JWT/internal denied tests, no unsafe production profile. **Tests:** T01.01,T02.01–03 base,T04.04 lane.

## Этап 3. Provider changes — Identity/Education
**Что:** минимальные необходимые изменения существующих сервисов.
**Как:** reuse Identity user single/batch; add block/unblock outbox/revisions; Education cap8 under lock, existing meeting_url PATCH with null semantics, resolve single/recurring, batch membership, guard POST/GET/DELETE and release tombstone. Shared course/occurrence lock for reserve/cancel/move/stop; allow delete course logical event. Extend Education outbox removed/deleted, adapt existing cancel/reschedule envelope without breaking consumers. Inspect existing oversized courses, report migration blocker rather than silently remove. Update provider docs and tests; no Assignment changes except regression. 
**DoD:** real provider contract tests, guard race solved, URL validation and atomic cap, events transactional. **Tests:** T04.01–02,T05.03,T13.01/03/04,T02.02,T14.01 provider.

## Этап 4. Lifecycle and reservation saga
**Что:** start/resolve/current/details/finish skeleton with real lifecycle, no media dependency.
**Как:** unique claim STARTING and job; guard reserve stable attempt ID; snapshot roster; Mongo board init atomic; transition ACTIVE only ready board/current window/permissions, outbox Started; deadline5min compensation START_FAILED with release tombstone and cleanup; no new attempt until compensation confirmed. Active max6h, finish reason and logical transition, durable cleanup jobs; temporary components registered as implemented, no claim COMPLETED before actual cleanup. Implement job claim leases/backoff/unknown outcome. Use local lane for final state changes, network outside SQL.
**DoD:** repeated start one attempt; Mongo failure compensated; never-started no history; teacher concurrency/window; finish closes app access. **Tests:** T03.01–04,T04.01–02,T15.01; finish event/cleanup full in later stage.

## Этап 5. Participant permissions and authorization
**Что:** assignment, roster, global/personal permissions, kick/restore, admin slot.
**Как:** initial snapshot; explicit new assignment with current membership; independent flags, effective permissions AND global; no auto restore re-enroll; admin one slot, source flags, access revision, current actor; batch15s freshness60s and suspension, publish local state after commit; integration hook for durable media rotation; checks REST/WS admission/send/subscribe/outbound. Build provider stubs/actual tests. No per-pointer HTTP.
**DoD:** rights enforced backend; unauthorized subscription impossible; cap/restore semantics. **Tests:** T02.01–04,T05.01–04,T13.01–02.

## Этап 6. LiveKit integration
**Что:** media state, create/provision/rotate/close, token, moderation, webhook and reconcile.
**Как:** SDK verified PoC, stable generation, strict revoke close old then create new, old grants invalid for new room, auto_create false, no remote unmute, max10, media can fail independently. Token initial60s scoped, no secret log; forced mute vs durable grant revoke. Raw signed webhook durable inbox, event/SID fence; periodic room/participants 15s and orphan cleanup. Retry unknown outcomes; never reopen finished. Frontend reconnect event and fallback board URL. 
**DoD:** real LiveKit tests for old token, room rotation, outage, invalid signature; no promise instantaneous remote close on partition. **Tests:** T06.01–06,T03.03,T17.02 partial.

## Этап 7. Presence and attendance
**Что:** APP sessions/heartbeat, MEDIA observations, union intervals, history/attendance.
**Как:** one APP and MEDIA each; session supersession fence; heartbeat10s timeout30s, presence poll5s, reconciliation feeds reducer under lane/SQL lock. PRESENT/ABSENT/UNKNOWN; teacher absent continuous60m, reset/unknown breaks, hard6h, lifecycle poll5s. Persist intervals disjoint, first/last/count/seconds, unknown separately, assignedAt/eligibility. Finish finalizes atomically, include absent students. History cursor and owner/admin access, snapshot course title after delete.
**DoD:** exact deterministic time boundary tests and no double count; restart unknown not fictitious attendance. **Tests:** T07.01–04,T08.01–03,T03.05–06.

## Этап 8. Chat
**Что:** lesson_chat_message, REST history/delete, STOMP send/ACK.
**Как:** text only4000, max5/s, SQL lock state + permission, unique clientMessageId/hash, ACK after commit, deleted tombstone, REST keyset/reconnect, independent from course and LiveKit. Fallback UI no chat; active history retained. Deny outbound after revoke, physical cleanup registered.
**DoD:** duplicate/lost ACK, moderation/permissions, pagination, no stale leak. **Tests:** T09.01–05,T04.03–04 chat.

## Этап 9. Board metadata and page management
**Что:** board init, read snapshots, page add/delete/rename/reorder/focus/clear/background.
**Как:** Mongo txn for metadata+page+receipt, board revision and page epoch/revision, max50, no delete last, permission checks, image background server protected, structural REST idempotency and events; lane shared with finish/revoke. Focus transient; page presence routing; no bytes Mongo. 
**DoD:** metadata/indexes consistent, structural race tests, stale epoch. **Tests:** T10.01–03,T11.03/04 subset,T04.03–04.

## Этап 10. Board object synchronization
**Что:** OCC, batch atomics, dedup, revision gap recovery, cursors and undo.
**Как:** implement CREATE/UPDATE/DELETE/RESTORE/LOCK/UNLOCK and validation; element version vs page revision, compare-and-set, atomic Mongo receipt, retry uncertain commit, ACK then broadcast. Size/rate/queue limits, compact receipts, bounded data. Cursor/selection/hand in memory; revision heartbeat10s. Client contract harness subscribe-buffer-snapshot, retry original command, no offline edits, own undo as inverse with expected versions. Test complex Excalidraw binding/order and remote update feedback; coordinate actual Angular implementation.
**DoD:** no lost accepted writes, conflicts atomic, no foreign undo, no unauthorized mutation, restart repair. **Tests:** T11.01–09,T04.03–04,T16.01 smoke.

## Этап 11. Files and materials
**Что:** MinIO upload/download, quota/dedup, PDF workflow, file references.
**Как:** validate image actual signature/dimensions and safe name, hash; reserve quota and unique key before PUT, READY only active+lease, streaming safe headers, teacher/admin upload, admitted read. 100 files/200MB/10MB/4096²/3 concurrent; no public URL/base64, no individual deletion. Check Mongo element references READY same lesson. Education existing owner/admin material content via frontend, PDF.js renders selected page and upload; no source reference/Saga. Reconcile late PUT/orphans by prefix, protect active uploads, minIO failure independent.
**DoD:** real MinIO exact bytes and negative tests, quota race, late PUT not published, copy survives source delete. **Tests:** T12.01–06,T04.03.

## Этап 12. Complete finish, cleanup and crash recovery
**Что:** all lifecycle steps, final attendance, cleanup across components.
**Как:** lane SQL FINISHING + frozen attendance + outbox events/jobs; revoke WS/token, close all media generations, release guard, delete all lesson Mongo documents/receipts/MinIO/SQL chat and technical connections. Durable step checkpoints/lease; unknown outcome reconciled; START_FAILED compensation and orphan scan. COMPLETED only after required confirmations; history remains during cleanup. Retention technical payload7d, compact dedup tombstones, pending never age-delete. Worker intervals lifecycle/presence5s, auth/media15s, jobs1s, files5min, retention1h.
**DoD:** simulated restart every critical step; logical access closed even outage; no orphan resurrection, history preserved. **Tests:** T15.01–04,T03.04/06,T08.03,T09.05,T12.06.

## Этап 13. Kafka and observability
**Что:** outbox publisher, incoming transactional inbox, metrics.
**Как:** stable envelope/sequence, claim earliest unpublished per aggregate, acks=all idempotent producer, bounded timeout, lease, retry/backoff. Consume six source events, eventId dedup with durable effects before offset ACK; fanout safely serialized, stale revisions never regrant. Started/Finished/AttendanceFinalized contract tests. Actuator/Micrometer counters/histograms/gauges, cardinality bounded; logs correlation IDs no content/tokens. Kafka outage no business rollback. 
**DoD:** real broker tests, outbox/inbox fault cases, event schema, metrics exposed. **Tests:** T14.01–04,T13.02,T15.04.

## Этап 14. End-to-end, performance and release
**Что:** full compose integration, frontend PoC completion/contract handoff, measured performance.
**Как:** run T17 and T16 scenarios, real browser camera/mic/screen and mobile, fallback same board, malformed client, authorization revocation incl stale token; test math/geometry/background tools with partner. Measure p95 local change→remote render target≤500ms with conditions; report p99/throughput/error/memory, tune configurable limits only with evidence. Verify all migrations/upgrades, complete docs/env/ports/health/cleanup runbook, existing Identity/Education/Assignment regression. Audit no accidental exposed internal routes/private files/credentials. Provide production limitations (singleton, no HA, Telemost external privacy, media outage, no recording/offline). 
**DoD:** all available tests executed, reproducible commands/results, no silent skipped MVP requirements; if frontend or real environment unavailable mark exact blockers. **Tests:** T16.01–03,T17.01–03 and full suite.
