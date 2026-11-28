# Online Lesson Service — Kafka v1

At-least-once, no Kafka for board/chat/cursor/heartbeat/media individual connection. Reuse Assignment envelope and outbox conventions. Topics `identity.events.v1`, `education.events.v1`, `online-lesson.events.v1` are project defaults; adapt existing topic names only by documented mapping. UTF-8 JSON camelCase, UUID, UTC. Kafka key aggregateId string. No tokens/meetingUrl/chat/board contents/file paths. Consumer groups distinct (`online-lesson-service`, future notification/metrics). ACL/internal network.

## 1. Envelope
```json
{"eventId":"uuid","eventType":"OnlineLessonStarted","eventVersion":1,"producer":"online-lesson-service","aggregateType":"ONLINE_LESSON","aggregateId":"lesson-uuid","aggregateSequence":1,"occurredAt":"2026-12-01T14:00:00Z","correlationId":"uuid","payload":{}}
```
Stable ID on retry, sequence monotonic per aggregate under provider lock, payload immutable, occurredAt logical transition. Sequence never reset. Existing Education events may have different envelope: adapter/version mapping, do not break existing Notification contract. Unknown version/type handled explicitly, invalid payload alert/quarantine not silent loss.

## 2. Incoming events
| Type | Producer/key | Payload fields (required unless ?) | Reaction |
|---|---|---|---|
| UserBlocked | Identity/userId | userId, blocked=true, sourceRevision | revoke participant; teacher finish |
| UserUnblocked | Identity/userId | userId, blocked=false, sourceRevision | fresh check, not restore unrelated restriction |
| CourseStudentRemoved | Education/courseId | courseId, studentId, sourceRevision | revoke all active assignments on course |
| CourseDeleted | Education/courseId | courseId, teacherId, sourceRevision, deletedAt | finish active/abort starting at logical DELETING |
| LessonCancelled | Education/courseId | lessonRef, oldStartsAt,oldEndsAt,reason? | reconcile pending start |
| LessonRescheduled | Education/courseId | lessonRef, oldStartsAt,oldEndsAt,newStartsAt,newEndsAt,reason? | reconcile pending start |
Existing series stop event handled via guard/provider reconcile; no new incoming event required by agreement. sourceRevision monotonic for respective source aggregate if provider implements; fallback verify current provider state before grants. Membership removal event should remain a revocation for existing assigned student even if re-enrolled before processing (re-enrollment not automatic restore); explicit restore after event/current check uses source revision fence to avoid stale removal undoing newer deliberate grant. If source version unavailable, fail closed on ambiguity and report. Block/unblock outbox in same Identity transaction, removal/course deletion outbox in Education transaction. Do not publish CourseDeleted only after physical delete.

Inbox: verify schema/type, insert eventId unique + local state changes and durable jobs in one SQL transaction; ack offset after commit. One event may affect multiple lessons; use durable fanout or apply all affected local rows in deterministic lesson lock order, coordinate with per-lesson lane (never acknowledge before all durable effects registered). No network calls inside SQL. Duplicate same event no repeated effect; conflicting eventId/hash operational incident. Delayed events cannot revive access/lesson; authoritative reconciliation every15s; use source revisions/fences for stale events. Keep compact dedup tombstone indefinitely MVP, payload7d. Poison event retry/quarantine explicit alert and manual recovery. Kafka unavailable does not stop existing lessons; auth freshness still enforced by REST.

## 3. Outgoing events
All key lessonId, aggregateType ONLINE_LESSON. Started only once ACTIVE; Finished and AttendanceFinalized in same logical finish SQL transaction, sequential aggregate sequence. No events for failed start, no repeated finish, no event per participant reconnect. Notification consumes Started if configured; Metrics consumes all three. Future Notification chooses recipients/preferences; no delivery guarantee here.

### OnlineLessonStarted
```json
{"lessonId":"lesson-uuid","courseId":"course-uuid","courseTitle":"Математика","teacherId":"teacher-uuid","lessonRef":{"courseId":"course-uuid","lessonType":"SINGLE","singleLessonId":"uuid"},"scheduledStartsAt":"2026-12-01T14:00:00Z","scheduledEndsAt":"2026-12-01T15:00:00Z","actualStartsAt":"2026-12-01T14:05:00Z","assignedStudentIds":["student-uuid"]}
```
### OnlineLessonFinished
```json
{"lessonId":"lesson-uuid","courseId":"course-uuid","teacherId":"teacher-uuid","scheduledStartsAt":"2026-12-01T14:00:00Z","scheduledEndsAt":"2026-12-01T15:00:00Z","actualStartsAt":"2026-12-01T14:05:00Z","actualEndsAt":"2026-12-01T15:10:00Z","durationSeconds":3900,"finishReason":"TEACHER_FINISHED","assignedStudentCount":8,"attendedStudentCount":7}
```
### OnlineLessonAttendanceFinalized
```json
{"lessonId":"lesson-uuid","courseId":"course-uuid","participants":[{"userId":"student-uuid","role":"STUDENT","assignedAt":"2026-12-01T14:05:00Z","eligibilityEndedAt":null,"firstJoinedAt":"2026-12-01T14:07:00Z","lastLeftAt":"2026-12-01T15:08:00Z","presenceSeconds":3480,"unknownSeconds":0,"connectionCount":2},{"userId":"absent-student-uuid","role":"STUDENT","assignedAt":"2026-12-01T14:05:00Z","eligibilityEndedAt":null,"firstJoinedAt":null,"lastLeftAt":null,"presenceSeconds":0,"unknownSeconds":0,"connectionCount":0}]}
```
All assigned students including zero attendance; teacher/admin if assigned/joined. Counts distinct student rows, attended iff presenceSeconds>0 (short subsecond joins can round to0; use exact intervals for business decisions if needed). Late assignment denominator begins assignedAt, exclusion eligibility separately indicated, unknown duration separately; intervals in REST history not event to limit payload. Never claim exact external attendance. Finished events generated before cleanup, duration actual end−start bounded, immutable. Missed unstarted lessons derived from Education schedule; reminder 24/7/1h owned by Education/Notification.

## 4. Outbox publisher
SQL business+event atomic. Every1s claim ≤100 eligible earliest unpublished per aggregate FOR UPDATE SKIP LOCKED, PROCESSING lease60s/token/attempt++, commit. Send outside SQL with `acks=all`, idempotent producer, bounded timeout <lease; mark PUBLISHED only matching lease token. Failure PENDING/backoff+jitter cap5min, stable eventId. Crash after broker ACK before SQL mark → duplicate expected; consumers dedup eventId + local effect, sequence guards stale transitions. No exactly-once claim. Retain published7d, never delete pending by age; inbox/webhook tombstones retained. Metrics backlog, oldest age, retries, consumer lag, duplicates. Test rollback, Kafka down/up, duplicate delivery, old sequence, multi-claim, stale lease, finish events order and absence of secrets.
