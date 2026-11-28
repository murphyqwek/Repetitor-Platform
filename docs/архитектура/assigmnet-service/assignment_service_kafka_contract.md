# Assignment Service — Kafka-контракт v1

Редакция 27.11.2026. Producer Assignment, consumer будущий Notification Service (не реализуется в рамках текущего MVP). Все события через transactional outbox, бизнес-commit независим от Kafka. Topic `assignment.events.v1`, Kafka key = aggregateId UUID string. JSON UTF-8, camelCase, UTC ISO 8601, UUID; version 1. Partition count/replication factor — конфигурация окружения; local single broker не даёт высокой доступности. Не добавлять consumer для Education/Identity lifecycle: актуальные проверки через REST.

## 1. Envelope

```json
{"eventId":"event-uuid","eventType":"AssignmentPublished","eventVersion":1,"producer":"assignment-service","aggregateType":"SUBMISSION","aggregateId":"submission-uuid","aggregateSequence":1,"occurredAt":"2026-11-27T12:00:00Z","correlationId":"correlation-uuid","payload":{}}
```

`eventId` уникальный и неизменный на всех retry. `eventType` из перечня ниже, `eventVersion` версия payload. `aggregateType` SUBMISSION / IMPORT_JOB. `aggregateSequence` монотонный positive bigint в рамках aggregate (техническое дополнение для порядка и дедупликации); генерировать под lock submission/import_job в той же SQL транзакции, не из clock. `correlationId` request/worker UUID, если нет — генерировать. `occurredAt` момент бизнес-перехода; не изменять на retry. payload fields ниже обязательны кроме явно nullable. Не передавать ответы/файлы/пароли/email/токены/stack trace; Kafka доступ ограничить ACL.

## 2. События

| Event type | Aggregate | Получатели будущего уведомления | Триггер |
|---|---|---|---|
| AssignmentPublished | SUBMISSION | studentId | одна публикация на submission |
| AssignmentDeadlineChanged | SUBMISSION | studentId | новый общий срок, работа ещё может выполняться |
| AssignmentDeadlineReminder | SUBMISSION | studentId | актуальный reminder |
| AssignmentRevoked | SUBMISSION | studentId | переход в REVOKED |
| SubmissionReceived | SUBMISSION | teacherId | сдача с ручной частью |
| SubmissionReviewed | SUBMISSION | studentId | первая REVIEWED, включая pure auto |
| SubmissionGradeUpdated | SUBMISSION | studentId | реальная коррекция результата/комментария |
| TaskImportFailed | IMPORT_JOB | ADMIN | terminal FAILED/COMPLETED_WITH_ERRORS |

Родителей не уведомляем. Никаких событий при автосохранении, draft grading, duplicate request/no-op. При MISSED нет отдельного события в согласованном наборе. Повтор submit/review не публикует заново. Уведомления для администраторов определяет Notification по актуальной роли, а не списку email в payload.

### AssignmentPublished
```json
{"assignmentId":"assignment-uuid","submissionId":"submission-uuid","studentId":"student-uuid","teacherId":"teacher-uuid","title":"ДЗ №5","mode":"HOMEWORK","dueAt":"2026-12-04T17:00:00Z"}
```

### AssignmentDeadlineChanged
```json
{"assignmentId":"assignment-uuid","submissionId":"submission-uuid","studentId":"student-uuid","title":"ДЗ №5","oldDueAt":"2026-12-04T17:00:00Z","newDueAt":"2026-12-06T17:00:00Z","effectiveDeadlineAt":"2026-12-06T17:00:00Z","deadlineRevision":2}
```

### AssignmentDeadlineReminder
```json
{"submissionId":"submission-uuid","assignmentId":"assignment-uuid","studentId":"student-uuid","title":"ДЗ №5","dueAt":"2026-12-04T17:00:00Z","offsetHours":24,"scheduledAt":"2026-12-03T17:00:00Z","deadlineRevision":1}
```

Напоминания 72/24/1 h относятся к общему сроку assignment, не к персональному экзаменационному таймеру; не отправлять, если индивидуальный effectiveDeadline уже наступил. При назначении/переносе интервалы в прошлом пропускаются. При недоступности worker после восстановления — не отправлять устаревшие пачкой: максимум самое свежее наступившее, если ещё актуально, остальное SKIPPED. Для 1h напоминания не отправлять после срока; точные grace windows не нужны для MVP, latest due reminder only. При изменении dueAt ++deadlineRevision (даже при возврате к прежнему времени), отменить прежние PENDING, создать новые будущие. Обработчик под lock assignment/submission проверяет revision, состояние, время; mark PROCESSED + insert outbox атомарно. При сдаче/отзыве/MISSED pending отменяются. Уже опубликованное событие нельзя отозвать из Kafka; будущий consumer сверяет актуальность/срок перед доставкой (например через будущий защищённый read API, его проектирование в Notification). Не обещать доставку ровно в срок.

### AssignmentRevoked
```json
{"submissionId":"submission-uuid","assignmentId":"assignment-uuid","studentId":"student-uuid","title":"ДЗ №5","reason":"Назначено ошибочно"}
```
reason nullable; при отмене назначения default reason null. Архив не создаёт revoke.

### SubmissionReceived
```json
{"submissionId":"submission-uuid","assignmentId":"assignment-uuid","studentId":"student-uuid","teacherId":"teacher-uuid","title":"ДЗ №5","submittedAt":"2026-12-03T16:00:00Z","manualTaskCount":3}
```

### SubmissionReviewed
```json
{"submissionId":"submission-uuid","assignmentId":"assignment-uuid","studentId":"student-uuid","title":"ДЗ №5","finalScore":12,"maxScore":15,"testScore":null,"schoolMark":null,"reviewedAt":"2026-12-03T18:00:00Z"}
```

testScore и schoolMark nullable; только одно может быть задано для экзамена. Числа целые, maxScore > 0, 0..max. Условные примеры баллов не являются официальной шкалой.

### SubmissionGradeUpdated
```json
{"submissionId":"submission-uuid","studentId":"student-uuid","title":"ДЗ №5","oldFinalScore":12,"newFinalScore":14,"maxScore":15,"testScore":null,"schoolMark":null,"reviewRevision":12}
```

Коррекция комментария при неизменном балле допускается, old/new тогда равны; не передавать текст комментария. reviewRevision помогает распознать устаревший результат.

### TaskImportFailed
```json
{"jobId":"job-uuid","sourceCode":"RESHUEGE","status":"COMPLETED_WITH_ERRORS","processedCount":1000,"failedCount":5,"finishedAt":"2026-11-27T13:00:00Z"}
```
FAILED или COMPLETED_WITH_ERRORS; глобальная ошибка может иметь failedCount=0 при отсутствии обработанных задач. Один terminal event на job.

## 3. Outbox storage / publisher

В той же PostgreSQL транзакции, что business update, insert immutable payload/envelope metadata со стабильным eventId и aggregateSequence. UNIQUE(aggregate_type,aggregate_id,aggregate_sequence). Исходная user command не ждёт Kafka. Транзакционный rollback -> нет события. Publisher:

1. Короткая транзакция: взять до 100 eligible rows `FOR UPDATE SKIP LOCKED` (PENDING либо PROCESSING с истекшим lease), next_attempt_at <= now; выбрать только самое раннее неопубликованное событие каждого aggregate, не обгонять failed older event. Claim status PROCESSING, lease 60 s, random lease_token, attempts++, commit.
2. Отправить Kafka key aggregateId с producer `acks=all`, enable.idempotence=true, bounded delivery timeout короче lease или продлевать lease при работе. Не держать SQL locks во время send.
3. После ack отдельная транзакция `UPDATE ... WHERE id=? AND lease_token=? AND status='PROCESSING'`; PUBLISHED, published_at, очистить lease. Просроченный worker не может обновить чужой claim. При failure оставить PENDING и nextAttemptAt backoff, jitter, max 5 min; не генерировать новый eventId.
4. После падения с ack до mark possible duplicate. Consumer MUST deduplicate eventId атомарно с локальным side-effect state; при внешней доставке требуется собственная идемпотентность/notification outbox. Producer idempotence не делает PostgreSQL+Kafka exactly-once. Даже при key и последовательной обработке possible late duplicate от старого worker; consumer отслеживает sequence и не регрессирует состояние от старого события. Если нужна строгая последовательность всех внешних эффектов, это задача будущего consumer, не гарантируется Kafka key alone.
5. Poll 1 s, опубликованные записи хранить 7 дней, удалять только PUBLISHED старше TTL; sequence хранится в агрегате и не сбрасывается при очистке. Неопубликованные никогда не удалять из-за возраста, алерт при росте backlog/age/attempts. Не делать бесконечный tight loop. При terminal malformed payload — операционный инцидент, не терять silently; исправление с сохранением диагностики.

## 4. Тесты и наблюдаемость

- Snapshot contract tests сериализации всех 8 payload/envelope, типы/nullability, topic/key, отсутствие чувствительных данных.
- Real PostgreSQL + Kafka Testcontainers: business rollback no event, Kafka unavailable business committed, восстановление publishes, два publisher lease/claim, ack-before-mark failure duplicate same ID, stale lease cannot mark, sequence monotonic and no overtaking, event retention only published; consumer demo dedup in test harness (Notification production не требуется).
- Deadline worker: three offsets, stale job after change, back to former due date, multiple replicas, submit/reminder race, missed/blocked, old reminder after downtime, no duplicate on retries.
- Metrics: outbox pending count, oldest pending age, publish successes/failures, retries, lease recovery, reminder lag; structured logs eventId/aggregateId/correlationId без содержимого ответов. Проверять cardinality labels, IDs только в логах.
