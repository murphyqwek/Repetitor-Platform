# Assignment Service — тест-план

Редакция 27.11.2026. Это спецификация тестов для Codex, не отчёт о запуске. Идентификаторы Txx используются в пошаговом плане. Источники: requirements, db_design, rest/internal/kafka contracts из этого комплекта. Каждый тест проверяет HTTP/бизнес-ответ и состояние БД/MinIO/Outbox при применимости. При несовпадении теста с контрактом исправлять тест или уточнять контракт, а не ослаблять инварианты.

## 1. Стратегия и окружение

- Unit: JUnit 5, параметризованные тесты форматов, State/Policy, ExamScoreConverter, сроков, квот, нормализации, cursor codec, parser fixtures; Mockito только для изоляции зависимостей. Clock injected, фиксированное время, без `Thread.sleep` для точных границ времени.
- Integration: Spring Boot test, реальные PostgreSQL 17 + Flyway через Testcontainers, MinIO с приватным bucket и Kafka Testcontainers для файлов/событий. Не заменять PostgreSQL H2: JSONB, частичные индексы, locks, SKIP LOCKED, FK должны проверяться на реальном движке. Identity/Education HTTP stubs (WireMock или принятый в репо эквивалент), включая timeout, lost response, malformed DTO, 4xx/5xx; реальные provider contract tests в их сервисах.
- API tests: MockMvc или HTTP client, реальные подписанные test JWT или test issuer; отдельные service JWT, audience/scope. Не отключать production security, не использовать mock роли как единственное доказательство авторизации. Проверять JSON на отсутствие секретных полей рекурсивно.
- Concurrency: две независимые транзакции/соединения, barriers/latches, controlled failpoints, bounded await, commit вне enclosing test transaction. Проверять итоговые строки, constraint и число событий, не только HTTP. Тесты worker: fake clock, ручной tick, deterministic failpoints, Awaitility для реальной асинхронности. Не скрывать flaky tests retries.
- E2E: local compose либо отдельное интеграционное окружение с Identity+Education+Assignment+MinIO+Kafka. Fixtures синтетические, без реальных пользовательских данных. Внешний РЕШУ ЕГЭ не вызывается из CI; адаптер тестируется на легально полученных или синтетических HTML/JSON.
- Команды сверить с репо (`./gradlew :<module>:test`, integrationTest, `docker compose ... config/up`); не выдумывать имя модуля. После этапа прогон новых и всех прежних тестов Assignment. JUnit reports и фактические команды приложить к отчёту. Тест не считается пройденным, если не запускался.

## 2. Базовые fixtures

Учителя T1/T2, ученики S1/S2/S3/S4, P1 родитель, A1 администратор, заблокированный B1, incomplete I1, multi-role M1. Курсы C1(T1: S1,S2), C2(T1: S2,S3), C3(T2:S4); Cdel DELETING. Фиксируем time `t0=2026-11-27T12:00:00Z`, дедлайн `t0+96h`, exam duration 60 min. Imported auto I01..I10, manual I11; private T1 P01, private T2 P02. V1/V2 отличаются ответом и темами; file F1 shared in two versions, explanation-only F2, Education file E1 owner T1. Exam test structure 3 positions max 1,1,3, scale test fixture 0..5 → условные values. Не использовать эти цифры как официальные экзаменационные. Mixed template H1 (auto 1/1, manual 3), pure auto H2, incomplete exam H3. Для analytic 5 distinct задач и повтор одной, multi-topic, старые/новые результаты. Fixtures создаются через domain API или миграции для конкретных тестов, не ломают constraints.

## 3. Матрица тестовых сценариев

### T01 — инфраструктура, миграции и безопасность

| ID | Действие / условия | Ожидаемый результат |
|---|---|---|
| T01.01 | Пустая PostgreSQL; Flyway migrate, повторный запуск | 36 таблиц Assignment; validate OK; seed math/3 exam types/source; повтор без дубликатов |
| T01.02 | Невалидные origin, FK чужой версии, answer чужого assignment, template/student duplicate, score <0, reminder bad offset | SQL constraint отклоняет; транзакция rollback |
| T01.03 | Нет JWT, подпись/iss/aud/exp неверны, обычный JWT на internal | 401/403, no writes |
| T01.04 | Активный JWT, Identity затем blocked/incomplete/missing | 403/403/401; без изменения данных; фоновые jobs продолжают |
| T01.05 | Identity timeout / malformed DTO / 5xx | 503 fail closed; не считать пользователя активным |
| T01.06 | S1 обращается к чужому приватному task/submission/attachment, T2 к чужому template, PARENT-only к аналитике/ДЗ | 404/403; нет leakage; ADMIN может управлять разрешёнными объектами |
| T01.07 | Multi-role, попытка передать чужой teacherId/body actor | права только по JWT + Identity + ownership, никакой подмены |
| T01.08 | cursor неверен/другой scope/filter/actor, limit 0/101, malformed UUID/JSON | 400 Problem JSON, нет SQL exception в ответе |
| T01.09 | Docker compose config, image build, health/readiness, env missing | старт с валидным env, fail-fast понятная ошибка конфигурации, secrets не в git |

### T02 — форматы, шкалы, справочники

| ID | Действие | Ожидаемый результат |
|---|---|---|
| T02.01 | TEXT accepted ["Москва", "город Москва"], регистр/пробелы, `Ё` vs `Е` | нормализованный match, Ё≠Е, без partial |
| T02.02 | NUMBER `2,5`, `2.50`, `+2.5`, `5/2`, `NaN`, `1e2`, длинный ответ | первые match, остальные invalid/0 при сдаче; BigDecimal, без double |
| T02.03 | SINGLE valid/unknown, MULTIPLE reorder/duplicate/unknown/missing, MATCHING all/partial/unknown | полное совпадение 0/max; invalid schema rejected; partial matching 0 при сдаче |
| T02.04 | FILES correctAnswer задан; maxScore 0/101; invalid answerConfig | 422; auto default 1; manual max обязателен |
| T02.05 | Topic same name/subject duplicate, чужой subject, deactivate used topic | duplicate conflict; чужой subject reject; history remains, new classification denied |
| T02.06 | Exam type/year duplicate, позиции повторяются/пропущены, отрицательная длительность/баллы | reject; create structure atomic |
| T02.07 | Structure v2 с совместимой и несовместимой позицией | только подтверждённо совместимые получают новые task versions; old template/assignment pinned; ручные правки не теряются |
| T02.08 | Scale incomplete, duplicate scores, mismatch structure, score outside ranges | reject; полная шкала 0..max, правильный conversion |
| T02.09 | Изменение шкалы после сдачи | старая работа использует old version, новая совместимую актуальную; исправление old score конвертируется прежней шкалой |

### T03 — банк задач и версии

| ID | Действие | Ожидаемый результат |
|---|---|---|
| T03.01 | Teacher создаёт private, admin пытается создать, T2 пытается edit P01 | 201, 403, 404; owner immutable |
| T03.02 | Imported visible, scope OWNED/AVAILABLE/IMPORTED/ALL, filters OR topics и позиции N:M | правильные строки, no duplicate при join, cursor стабилен при одинаковом createdAt |
| T03.03 | Student GET/list/version endpoint, включая known versionId | no correctAnswer/explanation/explanation-only images; historical version endpoint denied |
| T03.04 | PUT expected V1 → V2, identical update, stale expected | 201, 200 no-op, 409; V1 immutable |
| T03.05 | Два concurrent PUT V1 | одна V2; второй conflict; current принадлежит task |
| T03.06 | Copy imported -> edit copy, original unchanged | independent taskId/V1; shared image сохранено |
| T03.07 | Delete unused imported/private vs referenced by template/assignment/practice | 202 cleanup unused, 204 hide referenced; snapshot/history intact; no reimport |
| T03.08 | Delete vs publication/new template/practice concurrency | либо ссылка до hide и история сохранена, либо операция denied; no dangling FK |
| T03.09 | Admin изменяет imported, затем parser sees changed upstream | manualOverride true, no overwrite; после снятия update new version |

### T04 — файлы и MinIO

| ID | Действие | Ожидаемый результат |
|---|---|---|
| T04.01 | Valid image upload, MIME mismatch, SVG, oversize at limit+1 | READY 201; 415/413; no accessible invalid objects |
| T04.02 | Temporary file other owner, invalid markdown reference/raw HTML/remote image | reject; no disclosure; sanitize per policy |
| T04.03 | Attach file to V1 and V2; delete one version/unused copy | physical object survives until last reference removed |
| T04.04 | Unbound TTL at 24h, retry worker twice | DELETING then removed; no attached deletion |
| T04.05 | Upload crash before/after PUT, timeout unknown result; delayed successful PUT after logical delete | no usable orphan, reconcile eventual delete; key never reused |
| T04.06 | Student GET explanation image via bank/submission before reveal and via wrong context | 404/no bytes; correct context after reveal 200 |
| T04.07 | Answer attachment exact 20M/50M/200M boundaries, 10th and 11th file | at limit success, over limit reject; DB totals correct |
| T04.08 | Parallel final file uploads (limit 10 or total bytes) | one succeeds, other 422; orphan cleaned |
| T04.09 | Upload begins before deadline, finishes after; submit races attach | no late association; cleanup; saved-before-submit included |
| T04.10 | Delete attachment before submit, after submit, other student | 204 async, 409, 404; minio outage does not undo detach |
| T04.11 | MinIO unavailable on upload/download/cleanup | 503 upload/download; cleanup retry; no phantom references |
| T04.12 | Streaming valid PDF/image, forged fileId, wrong item | authorized bytes match, safe headers; 404 on wrong context |

### T05 — межсервисные REST и Saga

| ID | Действие | Ожидаемый результат |
|---|---|---|
| T05.01 | Internal JWT wrong audience/scope/type, user token | reject; internal not externally routed |
| T05.02 | Identity batch missing IDs/500/501; service timeout | deterministic all IDs classified; >500 reject; 503 on outage |
| T05.03 | Education resolve C1+C2+S2, C3/Cdel, individual S4 | dedup, per-student course origin, invalid ownership/deleting/ineligible; no truncation |
| T05.04 | Membership after removal/re-add/course deletion | current analytics authorization changes; prior submissions untouched |
| T05.05 | Register reference duplicate / conflicting id / чужой материал | 201 then 200; 409; 403; correct unique reference |
| T05.06 | Education reference registration vs delete file/folder parallel | if registration wins delete 409; if deletion wins register 409/404; no dangling published link |
| T05.07 | POST committed remotely but HTTP response lost | same referenceId retry; one remote row, version ACTIVE only after all confirmed |
| T05.08 | Multi-material partial permanent failure, crash during compensation | ABORTING, stable IDs, all references eventually released; old ACTIVE current unchanged |
| T05.09 | DELETE compensation arrives before late POST | durable release tombstone prevents late registration; no ghost reference |
| T05.10 | Two concurrent versions/Saga vs task deletion; stale lease token | one pending; late worker never overwrites current/deleted; cleanup |
| T05.11 | Student download linked material after course deleted or ACL revoked | valid via submission, Education internal registered reference; wrong link/file denied |
| T05.12 | Education outage during registration/metadata/content | pending retry or 503; no partially published version; no loss of prior work |

### T06 — шаблоны и генерация

| ID | Действие | Ожидаемый результат |
|---|---|---|
| T06.01 | Empty template, valid ordinary template, duplicate taskId with different versions | save empty; normal created; duplicate reject |
| T06.02 | Revision stale / concurrent PUT; template reorder | one atomic success; old assignment unaffected |
| T06.03 | Copy template, delete unused vs published | independent ID; unused physical, used hidden; assigned history intact |
| T06.04 | Task V2 appears while template pins V1 | newerVersionAvailable, no automatic replacement |
| T06.05 | Incomplete exam variant, wrong position/check mode/max | save valid incomplete, publish reject; invalid item 422 |
| T06.06 | FILL_EMPTY / REGENERATE_ALL, cross-position overlapping candidates | valid distinct tasks, preserve filled in FILL, maximise coverage, partial + missingPositions if impossible |
| T06.07 | Foreign/deleted/pending task, admin generating for T1 | denied; candidate scope owner T1, no leakage T2 |

### T07 — публикация и управление назначением

| ID | Действие | Ожидаемый результат |
|---|---|---|
| T07.01 | C1+C2+S2 selected | 3 unique submissions, one common assigned_item set, S2 two course rows; correct snapshot names |
| T07.02 | Individual S1 only | valid membership, no assignment_course/submission_course unless selected course |
| T07.03 | Blocked, invalid role, missing, invalid course, already assigned | no partial write; 409/422 as appropriate; skip flags only blocked/previously assigned |
| T07.04 | skipAlreadyAssigned/skipBlockedStudents true; all excluded | others published; all excluded 422; no silent exclusions |
| T07.05 | Same key same request (including after title change), different request same key | original receipt; 409 mismatch; exactly one set outbox/reminders |
| T07.06 | Concurrent publication different keys to same template/student | one success or explicit skip; rollback conflict; no duplicated work |
| T07.07 | Publication failure after inserting submissions before outbox | rollback all; safe retry |
| T07.08 | Snapshot fetched then course deleted/exclusion | allowed based on snapshot; new publish checks current membership; no cascade |
| T07.09 | Preview valid then data changes | publish rechecks; no reliance on preview |
| T07.10 | PATCH title/comment/due before expiry; attempt change frozen fields | allowed only listed fields; recalculation, reminder revision; reject prohibited |
| T07.11 | Extend after general or individual expiration (worker delayed) | 409 general; individual expired remains closed; no re-open |
| T07.12 | Cancel no submitted vs archive with submitted; repeat; revoke before/after cutoff | proper status, existing results unchanged, pending revoke, no duplicate event, cannot reassign same template |

### T08 — выполнение, таймер и автоматическая проверка

| ID | Действие | Ожидаемый результат |
|---|---|---|
| T08.01 | Start ordinary/exam, duplicate/concurrent start | fixed startedAt, timer once; not started exam hides items/files |
| T08.02 | Start exam close to common due, reopen other device | effective=min, warning, server time unchanged |
| T08.03 | Save initial revision0, update, stale save, DELETE If-Match | correct increment; stale 409; cleared body but attachments kept |
| T08.04 | Save wrong answer before submit | accepted format, no score/result/correctness leaked |
| T08.05 | Save/clear/attach racing with submit and expiry | lock serializes; only accepted before cutoff and submit included; never after |
| T08.06 | At deadline exactly, worker not processed | deny modification/start; no client-clock bypass |
| T08.07 | Pure auto partial/empty explicit submit, mixed with manual | auto REVIEWED and grades 0/max, mixed SUBMITTED with final null; materialized missing answers |
| T08.08 | Duplicate submit after lost HTTP response, concurrent submit/worker | same state, exactly one grade set and logical event |
| T08.09 | Expiry with saved answers / no answers / no start | auto-submit saved; MISSED otherwise; cancellation of reminders |
| T08.10 | Blocked student at expiry, Identity down | worker still finalizes; protected user access denied |
| T08.11 | Concurrent due patch/save/start/worker | lock order no deadlocks under test; no expired reopen; timer original start retained |
| T08.12 | Four reveal policies × states × times, direct explanation file access | only authorized; AFTER_DEADLINE needs common due+submitted, after review actual REVIEWED; no leakage in error/list |

### T09 — ручная проверка

| ID | Действие | Ожидаемый результат |
|---|---|---|
| T09.01 | Queue oldest first, tie id, filters, summary | correct ownership/count, cursor, no draft/private foreign |
| T09.02 | Start review twice, read full review | status IN_REVIEW, revision not reset, teacher sees correct answers |
| T09.03 | Grade valid/negative/over max/decimal/null | correct 0..max; reject invalid; null resets; auto original remains |
| T09.04 | Teacher/ADMIN concurrent grade/comment same revision | one succeeds, other 409, no lost update |
| T09.05 | Review draft visible to student? | only initial auto, no teacher comments/manual/aggregate; analytics unchanged |
| T09.06 | Publish before grading all manual incl blank response | 422 REVIEW_INCOMPLETE with ids; explicit manual 0 required |
| T09.07 | Publish complete with override and pinned scale | REVIEWED, final sum and conversion consistent, one outbox, all visible according reveal |
| T09.08 | Repeat publish after loss, concurrent correction | no duplicate reviewed event; atomic revision conflict or latest result |
| T09.09 | PATCH reviewed grades incl invalid second item | rollback whole patch; valid patch updates totals/revision/analytics and emits one update |
| T09.10 | Previously removed student/course | teacher still reviews own work; another teacher denied; admin allowed |

### T10 — самостоятельные тренировки и аналитика

| ID | Действие | Ожидаемый результат |
|---|---|---|
| T10.01 | Practice catalog imported auto only; filter correct/incorrect/new | private/manual/deleted excluded; correct per student |
| T10.02 | Submit version stale, bad answer, valid | 409/422 without consumption; valid stored and reveal |
| T10.03 | Duplicate same payload; different answer; two threads same task | same result/409; one row per permanent taskId; new version no second attempt |
| T10.04 | Delete used task then get attempt and file | old version & solution available to owner only |
| T10.05 | Same task in practice and two assignments | 3 observations, distinct=1, independent attempts; no join multiplication |
| T10.06 | 1/1,0/1,2/3,1/2,0/1 distinct items | avg 43.33%, WEAK; numeric not integer, not SUM(score)/SUM(max) |
| T10.07 | 4 distinct vs 5, exact 50/80 and rounding near threshold | insufficient until 5; exact thresholds correct before rounding |
| T10.08 | Empty period, boundaries from/to, 30d, week/month UTC | avg null, start inclusive/end exclusive, correct buckets; date submitted not reviewed |
| T10.09 | Multi-topic/version history/exam filters, review correction | each topic gets result, global no duplicates, historical classification, fresh analytics |
| T10.10 | PARENT, former teacher, foreign teacher, own student/admin | denied/allowed as contract; foreign private item IDs absent |
| T10.11 | Suggest with weak scores 15/35/45, N=15 | quotas 6/5/4, total <= N, dedup and prefer unseen |
| T10.12 | No weak/insufficient candidates, overlaps, previously solved fallback | clear warning; partial if exhausted; no DB writes/auto assignment |

### T11 — импорт

| ID | Действие | Ожидаемый результат |
|---|---|---|
| T11.01 | Admin config manual/scheduled, invalid cron/positions, non-admin | validation/403; UTC nextRunAt, no arbitrary host |
| T11.02 | Concurrent run same source, idempotent repeat, key mismatch | 1 active job; duplicate 202 same job; conflict 409 |
| T11.03 | Fixture new task, exact repeated source, changed content/answer/image | new V1, skipped, new V2; hash independent of generated IDs |
| T11.04 | Same numeric external ID across namespaces | separate task IDs; mapping source-defined |
| T11.05 | Source missing item, tombstone, manual override | no deletion/no restore/no overwrite |
| T11.06 | One invalid task or broken image among valid | only failed item excluded, others commit; counts and errors correct |
| T11.07 | Source rate limit/timeout/5xx, partial page malformed | bounded retry/backoff; terminal FAILED or partial; no tight loop |
| T11.08 | Restart mid import/lease lost; stale worker finishes | old job terminal FAILED/INTERRUPTED, stale write rejected, retry new job/snapshot, no duplicates |
| T11.09 | Schedule missed runs, disabled/deleted config, source busy | no backlog replay; next tick moves; existing job history preserved |
| T11.10 | Retry previous job after config edited | new job uses exact old snapshot; old history immutable, no data duplication |
| T11.11 | Delete imported task vs import concurrent / admin edit vs import | source identity lock/tombstone; no restoration or lost manual edit |
| T11.12 | Parser normalization, unsafe html/remote URL/oversize, unsupported format | safe canonical Markdown, fixture validated, no SSRF/XSS, no partial publish |

### T12 — напоминания, Kafka и cleanup

| ID | Действие | Ожидаемый результат |
|---|---|---|
| T12.01 | Publish due+96h, due+10h | three vs only 1h future reminders; no past events |
| T12.02 | Due changed and returned to old date | new deadlineRevision, old pending canceled; distinct cycles |
| T12.03 | Worker downtime across 72h/24h/1h, still before deadline | max latest due reminder; others SKIPPED, no burst |
| T12.04 | Submit/revoke/expiry vs reminder concurrent | linearized state; no new reminder after terminal; already committed event can't be recalled |
| T12.05 | Multi replica reminder/expiry/cleanup workers | one logical effect per action; lease/stale token protection |
| T12.06 | Serialize all 8 payloads | correct topic/key/envelope/version/nullability/sequence, no PII secrets, no parents |
| T12.07 | Kafka down during commit | business succeeds; durable pending event; after restore publish |
| T12.08 | Failure after broker ack before mark PUBLISHED | duplicate same eventId, consumer test dedup; no lost business |
| T12.09 | Concurrent publishers same aggregate with retry | no later event claimed before older PUBLISHED; consumer stale sequence tolerant |
| T12.10 | Lease expires while processing; stale worker attempts mark | CAS token prevents overwrite; retries recover |
| T12.11 | Retention 7d | only published old removed, pending kept; sequence not reset |
| T12.12 | Cleanup remote delete succeeded but DB commit failed; repeat | idempotent delete then finalize; no accidental shared file deletion |

## 4. Сквозные приёмочные сценарии

| ID | Сценарий | Критерий |
|---|---|---|
| E01 | T1 creates topic/task with images, template, publish C1+C2; S2 start/save/photo/submit mixed; teacher grades, publishes, corrects | one submission per unique student, exact pinned versions, correct score, correct event list, analytics changes immediately |
| E02 | Exam variant generated, strict scale, explicit start, client disconnect, clock advances to effective end | no restart, worker auto-finalizes, all saved answers, pinned converter |
| E03 | Shared material registered, course deleted in Education, student downloads, teacher tries delete file/folder | historical access continues, Education delete blocked, no orphan refs |
| E04 | Import synthetic adapter fixture, manual correction, repeat/import update, deletion tombstone, practice, weak topics and suggestions | no overwrite/recreate, independent one attempt, correct analytics, no auto publish |
| E05 | Restart services with pending saga/cleanup/outbox, Kafka/MinIO outages | recover using same IDs; no data loss; reports reflect dependencies |
| E06 | Cross-role/security sweep all public and internal endpoints, file URLs, hidden correct answers | deny unauthorized, no secrets/PII, no direct MinIO access |

## 5. Definition of Done общего тестирования

Все требования имеют позитивный/негативный тест, транзакционные инварианты проверены на реальном PostgreSQL; race/failure tests reproducible; миграции на чистой БД и upgrade test от предыдущего этапа; нет падений ранее реализованных тестов. E2E выполнены на реальных соседних сервисах после интеграции. Отдельно фиксировать не пройденные/не запущенные тесты и причину. Нагрузочный smoke: 500 recipients x 100 tasks (не создавать 50k answers при публикации), пагинация 100, concurrent saves across students, очередь worker и streaming. Измерить p50/p95, SQL count, heap, backlog; абсолютные SLA не согласованы, не обещать RPS заранее. Не применять нагрузку к внешнему источнику. Не включать production Notification Service, родительские API или массовый JSON импорт в тесты MVP кроме запрета/отсутствия маршрутов.
