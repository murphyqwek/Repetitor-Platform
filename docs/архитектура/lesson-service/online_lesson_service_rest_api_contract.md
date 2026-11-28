# Online Lesson Service — публичный REST API v1

Все маршруты относительно `/api/v1`. JSON camelCase, UUID, ISO UTC; локальная originalDate YYYY-MM-DD Europe/Moscow. Auth/CSRF как фактически реализовано в Identity/Education (cookie vs Bearer сверить, не вводить третий механизм). JWT + текущий actor из security context; не доверять actorId из body. Новые admission/start/token/assign/restore — синхронная проверка Identity/Education; остальные операции проверяют локальные права и freshness ≤60s. `Cache-Control: no-store` для lesson DTO, tokens, snapshots, chat, private files. Ошибки `application/problem+json` с code и безопасными details. 400 malformed, 401 auth, 403 role/blocked/suspended, 404 missing/hidden, 409 state/version/limit conflict, 413 payload too large, 415 unsupported media, 422 semantics, 429 rate/queue, 503 dependency. Не выдавать чужой lesson existence, JWT или object key в ошибках.

## 1. DTO и правила
`LessonRef` строго один тип: `{"courseId":"uuid","lessonType":"SINGLE","singleLessonId":"uuid"}` или `{"courseId":"uuid","lessonType":"RECURRING","seriesId":"uuid","originalDate":"2026-12-01"}`.
`LessonDto`: `{id,courseId,courseTitle,teacherId,lessonRef,status,scheduledStartsAt,scheduledEndsAt,actualStartsAt,actualEndsAt,finishReason,mediaStatus,mediaGeneration,meetingUrl,boardUrl,studentsCanDraw,studentsCanChat,stateRevision,serverTime}`. Nullable actual/finish/meetingUrl/boardUrl при отсутствии, boardUrl только ACTIVE+access. `meetingUrl` — резервная HTTPS ссылка Education; не обещать отзыва у уже знающего её. `status` includes START_FAILED, mediaStatus отдельный. Для истории meetingUrl/boardUrl null. `ParticipantDto`: `{userId,displayName,role,assignedAt,accessStatus,canDraw,canChat,canPublishCamera,canPublishMicrophone,canShareScreen,cursorColor,presence,handRaised,activePageId,accessRevision}`. Админ/учитель видят roster включая недопущенных и причины; ученик — допустимые участники и собственные effective rights, не чужие причины блокировки. Role chosen by server. `AccessStatus`: ALLOWED/KICKED/MEMBERSHIP_REVOKED/IDENTITY_BLOCKED/SUSPENDED; внутренние причины независимы. `PageDto`: `{pageId,title,revision,epoch,background,elements}`; element envelope `{elementId,serverVersion,createdBy,updatedBy,locked,deleted,data}`. Tombstones могут быть компактными в snapshot, но версии и deleted сохраняются. `BoardDto`: `{boardId,lessonId,revision,pages:[{pageId,title}],focusedPageId}` (focus transient). `FileDto`: `{id,fileName,mimeType,sizeBytes,width,height,state,downloadUrl}`. `MessageDto`: `{id,author:{id,displayName,role},clientMessageId,text,createdAt}`. Client-generated identifiers UUID; element IDs Excalidraw safe ASCII up to 128 chars. Cursor pagination `{items,nextCursor,hasMore}`, limit 30 max100 except chat; opaque signed/versioned scoped cursor, stable tie-breaker id.

## 2. Lifecycle (8 routes)
| Method | Path | Auth / response |
|---|---|---|
| POST | /online-lessons/start | teacher owner; 202 STARTING or 200 existing attempt/ACTIVE |
| GET | /online-lessons/{id} | authorized participant or owner/admin history; 200 LessonDto |
| GET | /online-lessons/current | teacher/admin/current assigned student; 200 list |
| POST | /online-lessons/resolve | allowed schedule viewer; 200 resolve DTO |
| POST | /online-lessons/{id}/finish | owner/admin; 200 logical result |
| POST | /online-lessons/{id}/video-token | ACTIVE authorized; 200 token |
| GET | /online-lessons/history | owner/admin; 200 paginated |
| GET | /online-lessons/{id}/attendance | owner/admin; 200 attendance |

Start body LessonRef; optional `Idempotency-Key` UUID is correlation only unless persistence implemented; inherent idempotency by occurrence+teacher and active attempt. 409 LESSON_ALREADY_COMPLETED, TEACHER_BUSY, START_WINDOW_CLOSED, SCHEDULE_GUARDED; START_FAILED may retry after compensation; return 202 with Location and poll GET; no token until ACTIVE. 503 unresolved dependencies before claim. No duplicate Started. Resolve body LessonRef, non-mutating: `{lessonRef,plannedStartsAt,plannedEndsAt,onlineLessonId,status,canStart,canJoin,meetingUrl,reason}`; null status/ID if not started; never disclose active details to unassigned student; `canJoin=false` if no ACTIVE/eligibility. GET current includes allowed STARTING (owner/admin only) and ACTIVE; not historical. GET details for START_FAILED owner/admin. Finish body optional empty; owner/admin reason inferred, no client-supplied timestamp or reason; STARTING abort → START_FAILED, ACTIVE → FINISHING, repeat FINISHING/COMPLETED returns existing actualEndsAt; no second events. Video token `{url,token,expiresAt,roomName,generation}` no-store; 409 MEDIA_UNAVAILABLE/ROOM_ROTATING, 409 ADMIN_SLOT_OCCUPIED, 503 dependencies; token bound to exact room current generation with explicit grants. History query `courseId?,teacherId?` (admin only), `from?,to?,limit,cursor`, actual start DESC,id DESC, FINISHING/COMPLETED only, snapshot title preserved. Attendance `{lessonId,actualStartsAt,actualEndsAt,participants:[{userId,displayName,role,assignedAt,eligibilityEndedAt,firstJoinedAt,lastLeftAt,presenceSeconds,unknownSeconds,connectionCount,intervals:[{kind,startedAt,endedAt,estimated}]}]}`; active provisional, finalized immutable, include absent students, exclude unassigned, no claim of Telemost measurement.

## 3. Participants (7 routes)
Prefix `/online-lessons/{id}`; all require ACTIVE unless history explicitly above.
| Method | Path | Body / result |
|---|---|---|
| GET | /participants | roster, 200 |
| POST | /participants | `{studentId}` owner/admin; 201 or 200 existing assignment |
| POST | /participants/{userId}/kick | owner/admin, student only, 200 |
| POST | /participants/{userId}/restore | owner/admin, student only, 200 |
| PATCH | /participants/{userId}/permissions | owner/admin, student only, 200 |
| POST | /participants/{userId}/mute | owner/admin, `{source:"CAMERA"|"MICROPHONE"}`, 200/202 |
| PATCH | /permissions | owner/admin, `{studentsCanDraw?,studentsCanChat?}`, 200 |
Assign checks current membership/Identity, max 8 eligible, assigns once, no automatic restore if already kicked/revoked. Restore explicitly clears kick and membership revocation only after fresh membership/Identity, retains other restrictions. Kick closes channels and queues media rotation, returns 202 if external job pending; effect local immediate, no guarantee during external outage. Permissions body `{canDraw?,canChat?,canPublishCamera?,canPublishMicrophone?,canShareScreen?,expectedAccessRevision}` with absent fields unchanged, null invalid, stale revision 409; revoke media grants rotates if stale grants would permit action. Mute is one-time not persistent permission; track absent no-op, 503 if media unavailable (do not claim muted), 202 if bounded job used. No remote unmute. Global permissions effective AND individual; serialize with commands, broadcast new state after commit. Errors PARTICIPANT_NOT_ASSIGNED, PARTICIPANT_LIMIT, MEMBERSHIP_REQUIRED, ACCESS_REVOKED, REVISION_CONFLICT. Admin slot for admission handled atomically; admin not a student.

## 4. Board (8 routes)
Prefix `/online-lessons/{id}`. Read for admitted ACTIVE participants; mutate structure owner/admin. All persistent mutations serialized by lesson lane; Mongo transaction for structure/receipt. Structural write body `operationId` UUID and expected metadata/page revision as appropriate, even DELETE via query `operationId&expectedBoardRevision`; same payload retry idempotent. No change after finish.
| Method | Path | Body/result |
|---|---|---|
| GET | /board | BoardDto |
| GET | /board/pages/{pageId} | PageDto snapshot |
| POST | /board/pages | `{operationId,expectedBoardRevision,title?}` →201 page |
| PATCH | /board/pages/{pageId} | `{operationId,expectedBoardRevision?,expectedPageRevision?,title?,background?}` →200 |
| DELETE | /board/pages/{pageId} | operationId/expectedBoardRevision query →204 |
| PUT | /board/pages/order | `{operationId,expectedBoardRevision,pageIds:[...]}` →200 board |
| POST | /board/pages/{pageId}/clear | `{operationId,expectedEpoch}` →200 new revision/epoch |
| POST | /board/pages/{pageId}/focus | `{}` →200 `{pageId}` |
Title 1..100 trim, default `Страница N`; max50, last deletion 409 LAST_PAGE. Reorder exact permutation. PATCH title metadata CAS; background page CAS; both one transaction if both present. Background `{type:"BLANK"|"GRID"|"RULED"|"COORDINATE",gridSize?,scale?,fileId?}`; positive finite bounded parameters (gridSize 5..200 scene units, scale 0.01..100 technical defaults); PDF/image background file READY same lesson, server-controlled, not erasable by students. Clear wipes elements/tombstones and image background, preserves grid type/config; epoch++, revision++, no undo across clear. Focus ephemeral broadcast, participants may later choose another page. Deleting current page redirects to first surviving page; stale subscription updates rejected. Snapshot includes latest page revisions, no file bytes; GET no mutation. Errors BOARD_NOT_READY, PAGE_NOT_FOUND, PAGE_LIMIT, PAGE_SIZE_LIMIT, EPOCH_CONFLICT, REVISION_CONFLICT. Structural receipt hash includes path/body, repeat DELETE 204 even after page removed while lesson active.

## 5. Chat/files (5 routes)
Prefix `/online-lessons/{id}`, ACTIVE authorized only.
| Method | Path | Body/result |
|---|---|---|
| GET | /chat/messages | `limit=50` max100, `before` opaque, `{messages,nextCursor,hasMore}` DESC by createdAt,id |
| DELETE | /chat/messages/{messageId} | owner/admin 204 idempotent for already hidden |
| POST | /files | owner/admin multipart `file`, 201 READY, 200 dedup READY, 409 UPLOAD_IN_PROGRESS |
| GET | /files/{fileId} | FileDto if READY same lesson |
| GET | /files/{fileId}/content | binary stream |
No POST chat messages; STOMP only. Deleted messages excluded, receipt remains; deletion event after commit. File content safe Content-Type, Content-Length, `nosniff`, private no-store; no raw object key/presigned URL. Validate limits (10MB,4096²,100,200MB,3 uploads/user), safe filenames ≤255, bytes validated. Source PDF pages uploaded as images; no backend arbitrary URL. 413 oversized,415 format,422 invalid image/quota,503 storage unavailable. Race finish: late upload never READY; cleanup. No file DELETE or download after FINISHING.

## 6. Examples of errors
```json
{"type":"about:blank","title":"Conflict","status":409,"code":"ELEMENT_VERSION_CONFLICT","detail":"Объект был изменён"}
```
Return safe current revision/epoch for board conflicts, not other private lesson data. Rate limit 429 with Retry-After where feasible. Apply security on every route including snapshots/history, never serialize JPA entities.
