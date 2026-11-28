# Online Lesson Service — WebSocket/STOMP и синхронизация

## 1. Transport and security
`/ws` routed to Online Lesson Service (gateway path disambiguated from Education). One authenticated APP session per user per lesson, separate LiveKit connection. STOMP CONNECT with project auth (Bearer or validated cookie + origin/CSRF per actual setup); validate JWT expiry/issuer/audience, Identity/Education on admission. Bind session to lesson via first authorized subscription/heartbeat; new APP session supersedes old, close old on server. No spoofing userId/role. Check SUBSCRIBE destinations, SEND and outgoing delivery; on revoke/suspend/finish actively unregister subscriptions/close session and fence queued messages. Avoid leaking lesson events to `/topic` subscribers after rights change; user queue targeted by session when possible. JWT expiration requires refresh/reconnect. Heartbeat 10s, expire after 30s of last valid heartbeat. Single instance simple broker; no guaranteed WS delivery/order across transports, no durable message queue. Bound frames 256 KiB, sessions, queue length and processing time, reject overload. Cursors/selection 20/s; board writes 20/s; chat 5/s; limits per user+lesson not per socket. Only validated session can update presence.

## 2. Destinations
| SEND prefix `/app/lessons/{id}/` | Payload |
|---|---|
| chat.send | `{clientMessageId,text}` |
| board.apply | BoardCommand below |
| board.cursor | `{pageId,x,y,visible}` |
| board.selection | `{pageId,elementIds}` |
| board.page | `{pageId}` |
| hand.set | `{raised}` |
| heartbeat | `{pageId?,knownRevision?,knownBoardRevision?}` |
| SUBSCRIBE | Purpose |
|---|---|
| /topic/lessons/{id}/state | lifecycle/media |
| /topic/lessons/{id}/participants | roster, permissions, hand |
| /topic/lessons/{id}/chat | message changes |
| /topic/lessons/{id}/board | structural metadata |
| /topic/lessons/{id}/board/pages/{pageId} | committed page changes |
| /topic/lessons/{id}/board/pages/{pageId}/presence | transient cursor/selection |
| /user/queue/lesson.responses | personal ACK/rejection |
| /user/queue/lesson.control | revoke/reconnect/suspend/focus guidance |

Envelope `{type,lessonId,occurredAt,...payload}`, times UTC, server assigned actor. No token in topic. `LESSON_STARTED/FINISHED` include status, stateRevision, actual times; `MEDIA_STATUS_CHANGED` includes mediaStatus/generation; `MEDIA_RECONNECT_REQUIRED` generation (client requests token); `ACCESS_REVOKED` reason, `AUTHORIZATION_SUSPENDED` reason; `PARTICIPANT_JOINED/LEFT/ASSIGNED`, `PERMISSIONS_CHANGED`, `HAND_CHANGED` include participant or effective rights and accessRevision. On missed control messages GET lesson/participants; never rely on client cooperation to enforce access.

## 3. Board commands and OCC
```json
{"operationId":"uuid","pageId":"uuid","epoch":1,"operations":[{"type":"CREATE","elementId":"el-1","expectedVersion":0,"data":{"id":"el-1","type":"rectangle","x":0,"y":10,"width":100,"height":50}}]}
```
Data example shortened; actual schema pinned in PoC to chosen Excalidraw version. CREATE expects absent ID and version0; UPDATE requires active element+matching version and full replacement data; DELETE marks tombstone; RESTORE only matching tombstone, uses previous or validated replacement data; LOCK/UNLOCK teacher/admin only, version increments. No duplicate elementId in packet (client coalesces). All mutations require ACTIVE, assigned/current rights and matching epoch; locked elements not mutable by students including deletion/restoration, cannot set locked/createdBy/serverVersion via data. Student cannot introduce new image assets or modify background, may move existing unlocked image objects; validate existing fileId READY same lesson. Validate types, finite bounds, bounded arrays/text/points/customData, safe IDs, no executable content/iframe/external URI/base64, order and references to related elements within final scene. Preserve group/binding/order invariants across atomic batch. Max100 ops, 5000 active objects, BSON ≤8MiB incl tombstones. CLEAR_PAGE via REST (same lane), epoch++, no stale writes. Revisions start0, epoch1; page revision increases once per committed mutation; metadata revision separate.

Server lane checks rights then receipt `(lessonId,actorId,operationId)` hash (includes route/page/epoch/payload; canonical JSON). Existing identical accepted → same ACK, never re-broadcast; changed → OPERATION_ID_CONFLICT. For new command read current page, verify all expected versions, construct final doc, CAS revision/epoch, store compact receipt in same Mongo transaction, retry transient transaction without changing command; unknown commit resolve receipt. Only after commit send `BOARD_OPERATION_ACCEPTED` and broadcast `BOARD_CHANGED` on page. ACK `{operationId,pageId,epoch,revision,elementVersions:[{elementId,serverVersion}]}`; change `{operationId,actorId,pageId,epoch,revision,elements:[full changed envelopes],order?}`; DELETE includes tombstone. Rejection `{operationId,pageId,code,currentEpoch?,currentRevision?,conflicts:[{elementId,serverVersion,deleted,data?}]}`; codes ELEMENT_VERSION_CONFLICT, EPOCH_CONFLICT, DRAW_FORBIDDEN, ELEMENT_LOCKED, INVALID_ELEMENT, PAGE_NOT_FOUND, PAGE_SIZE_LIMIT, OPERATION_ID_CONFLICT, BOARD_UNAVAILABLE, OVERLOADED, ACCESS_REVOKED. No partial writes. Send current conflict values only for authorized active page. Different objects both succeed despite stale page revision; same object first commit wins. Do not rebase automatically or silently drop conflict.

## 4. Client algorithm
- `onChange` diff changed elements vs last acknowledged baseline, ignore remote `updateScene` feedback; maintain optimistic overlay separate from authoritative scene. Preserve actual Excalidraw version/versionNonce/order fields without trusting them as server version. Batch 100ms, flush on pointer-up; one in-flight per page; while waiting coalesce next changes, do not mutate already sent payload. On ACK update baseline; apply own broadcast by revision/id without duplicate; on conflict rollback rejected changes to latest snapshot/authoritative elements and show conflict. Never blindly overwrite newer remote objects. If unable to transmit frame due to very long stroke, client must chunk stroke safely or reject with UI; verify in PoC.
- On connect/reconnect or page switch: subscribe first, buffer, GET snapshot, discard buffered events ≤snapshot revision, apply contiguous newer events; gap/epoch mismatch or periodic `PAGE_REVISION` mismatch → pause and reload. Retry unresolved original operationId/payload after checking current access, receipt may acknowledge an old revision; never regress snapshot. Unconfirmed local action can be discarded if stale/rejected. Server emits revision heartbeat (default every 10s on authorized page) even no activity, including board metadata revision; catches lost last event. No offline edits, no durable event history.
- Cursor coordinates scene space, server adds actor/color/name/current page, finite validated coords, visibility false on disconnect/page switch. Selection bounded IDs, not persisted, remove stale ephemeral state. Pan/zoom independent. Hand state transient; reconnect GET participants. Focus event `PAGE_FOCUS_CHANGED` forces navigation once, not persistent restriction. Structural events PAGE_CREATED/DELETED/UPDATED/ORDER_CHANGED include board revision, page info; PAGE_CLEARED includes new epoch/revision and background; gaps reload board + selected page. Deletion redirects to first surviving page. Subscriptions only authorized current page for presence; snapshot other pages allowed.
- Undo/Redo: own action stack per page; inverse of committed command includes exact versions it produced. Undo CREATE → DELETE, UPDATE → prior data, DELETE → RESTORE, all as new operationId. Redo uses versions from accepted undo, never old versions. No inverse on foreign later update/lock; entire group atomic, conflict visible. Clear or full snapshot reset stack; remote updates not recorded in native history; verify captureUpdate API and wrapper, replace unsafe native undo behavior if necessary.

## 5. Chat
`chat.send` validates nonblank 1..4000, no files, effective permission, state and rate. SQL lesson lock + UNIQUE lesson/author/clientMessageId; existing same hash returns MESSAGE_ACCEPTED with messageId; different hash CLIENT_MESSAGE_ID_CONFLICT; deleted row MESSAGE_ALREADY_DELETED. ACK only after commit. `MESSAGE_CREATED` includes MessageDto, `MESSAGE_DELETED` includes messageId; no events on retry. Reject `MESSAGE_REJECTED` with clientMessageId/code. REST catch-up after subscribe+buffer, replace list, paginate before; apply deletes/dedup IDs, no chat_event journal. On fallback board-only UI no chat subscription; returning reloads active history. Authorization always backend.

## 6. Failure rules
No ACK before durable commit. After crash between commit and broadcast, retry receipt plus revision heartbeat repairs. Disconnect pauses edits, optimistic unsent actions may be lost, no claim of offline persistence. PostgreSQL unavailable → pause protected operations, close access if unable to validate. Mongo unavailable → board unavailable, no ACK; chat/video independent. All mutating board REST/STOMP/permissions/kick/finish serialize in lane; no concurrent writer outside single instance. WebSocket send failures do not rollback durable writes.
