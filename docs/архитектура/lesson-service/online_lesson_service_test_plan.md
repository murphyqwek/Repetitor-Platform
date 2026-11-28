# Online Lesson Service — тест-план

Это спецификация, тесты не запускались. JUnit5/AssertJ/Mockito, real PostgreSQL/Flyway, Mongo replica set, MinIO, Kafka via Testcontainers; LiveKit real container + browser media E2E. WireMock or repo equivalent for provider failure tests, separate real provider contract tests. No H2, no production security bypass. Inject Clock, deterministic barriers/latches/controlled failpoints; no sleep for time boundaries. Each test asserts HTTP/WS result AND durable state/no unwanted side effects. Commands actual repository, reports not invented. Fixtures T1/T2 teachers, S1..S9 students, A1/A2 admins, P1 parent, B1 blocked, M1 multirole; C1(T1,S1..S8), C2(T2,S9), deleted course, single and recurring moved/cancelled occurrences; synthetic images and fake media. Use t0 fixed, planned 14:00–15:00 UTC.

| ID | Scenario | Expected |
|---|---|---|
| T01.01 | Fresh+upgrade Flyway, Mongo indexes, replica set txn abort | 11 SQL tables, 3 collections, no partial Mongo writes |
| T01.02 | Compose build/up, private bucket isolation, invalid env | real services healthy, no anonymous reads or secrets, LiveKit config verified |
| T01.03 | Angular React wrapper + Excalidraw PoC, touch, addFiles, scene diff/undo | integration works without feedback loop; gaps explicitly documented |
| T02.01 | Bad JWT/aud/expiry, PARENT, wrong course teacher, unassigned student | 401/403/404, no leakage/token/WS subscription |
| T02.02 | service token wrong scope/user token/internal public path | deny; actual provider contract |
| T02.03 | forged actor/userId, cross-lesson page/file/receipt | rejected; no side effects |
| T02.04 | current WS subscriber kicked/blocked/expired | server stops outbound and SEND, not merely UI event |
| T03.01 | start at −15m, before −15m, at end, late within window | only valid window, actual start rechecked |
| T03.02 | concurrent repeated start, teacher two lessons | same attempt, one active; other TEACHER_BUSY |
| T03.03 | Mongo unavailable at start, LiveKit unavailable | STARTING retry/START_FAILED after5m vs ACTIVE fallback |
| T03.04 | finish teacher/admin/student, duplicate finish, reopen | permitted once, no duplicate events, student denied, no reopen |
| T03.05 | max duration, teacher absent59m59s/60m, reconnect resets | exact boundary, no early finish, cap6h |
| T03.06 | course delete, teacher blocked, cancellation | terminal reason and history preserved, cleanup |
| T04.01 | reserve vs move/cancel/stop under two transactions | one legal ordering, no started cancelled occurrence |
| T04.02 | start failure + late reserve response | tombstone blocks re-reserve, compensation before retry |
| T04.03 | finish vs board write/chat/upload | committed-before-finish only, no resurrected board/file, no ACK after reject |
| T04.04 | permission revoke vs queued draw/kick | lane linearization; no unauthorized accepted write afterward |
| T05.01 | initial roster, new enrollment then explicit assign | no automatic current access; assign valid, attendance starts assignedAt |
| T05.02 | remove/re-enroll, kick/restore, block/unblock | independent flags; no implicit grant; restore requires fresh checks |
| T05.03 | concurrent enrollment 7+2, admin A1/A2 | cap8, one admin slot, total room≤10 |
| T05.04 | global/individual draw/chat, source rights, raised hand | AND effective flags; presence transient; reset next lesson |
| T06.01 | real room create/retry after lost response, token scope/TTL | stable attempt, room-specific grants, no admin rights or secrets |
| T06.02 | mute track then self-unmute, remote unmute attempt | one-shot mute, no remote unmute, source ban distinct |
| T06.03 | kick + stale initial/refreshed token rejoin | retired room closed/auto-create disabled, new room denied to old token |
| T06.04 | rotate while LiveKit unreachable | application closed, no new grants, retry; disclose media unknown |
| T06.05 | empty room auto-close, old generation event, stale create | new generation, old state fenced, no lesson finish |
| T06.06 | invalid signed webhook, duplicate, lost/late leave | signature denied, durable dedup, SID-specific reconcile |
| T07.01 | APP heartbeat10s, timeout30s, replaced socket | newest session, old heartbeat denied; presence expires |
| T07.02 | media+APP both active then one lost | PRESENT until both confirmed absent; no double count |
| T07.03 | media API timeout, APP absent, teacher UNKNOWN | no false absence finish; cap6h still applies |
| T07.04 | restart with open DB connections | reverify, no fictitious presence or absence |
| T08.01 | overlapping media/app intervals | union duration, disjoint intervals, correct first/last |
| T08.02 | absent student/late assign/excluded student/unknown | included zeros, assignedAt, unknownSeconds, eligibility metadata |
| T08.03 | finish freezes attendance, late webhook | immutable summary/outbox; history survives course deletion |
| T09.01 | valid chat, blank/4001 chars, muted role | ACK after commit, denied invalid, read permitted |
| T09.02 | duplicate ID same/different payload, ACK lost | same ID/no duplicate, conflict on changed payload |
| T09.03 | delete as student/teacher, retry deleted message | student denied, hidden/tombstone, cannot revive |
| T09.04 | pagination same timestamps, reconnect + concurrent delete | no duplicates, fresh REST, no resurrection |
| T09.05 | fallback board then video return, finish | UI hides chat only, active history restored, physical delete after finish |
| T10.01 | initial page, add max50, delete last, reorder exact permutation | limits and atomically consistent board metadata |
| T10.02 | student forbidden structure, lock teacher object, background | denied writes; allowed viewing/pan/zoom |
| T10.03 | force focus, independent navigation, page deleted | navigate once, redirect, scoped presence |
| T11.01 | two users different objects same page rev | both accepted, revision increments |
| T11.02 | same object expected version, concurrent batch | first wins, rejected batch leaves no partial objects |
| T11.03 | clear epoch vs late command, tombstone restore | stale rejected, no resurrection; conditional restore |
| T11.04 | Mongo transaction fail after page update before receipt | both rollback, no ACK |
| T11.05 | commit before lost ACK/broadcast; same operation retry | same receipt, no duplicate; revision heartbeat resync |
| T11.06 | reconnect subscribe+snapshot with concurrent updates | contiguous revision, skip duplicates, gap reload |
| T11.07 | Undo after own update then foreign edit; redo; clear | conflict no foreign loss, reset history |
| T11.08 | unsafe IDs/NaN/iframe/base64/invalid binding/foreign file | validation, no partial write, no injection |
| T11.09 | 5000 objects, 8MiB incl tombstones, 100ops/256KiB/rate | exact limits, bounded queue, no unbounded memory |
| T12.01 | JPG/PNG valid, MIME mismatch/SVG/GIF, oversize, 4097px | accept allowed, reject unsafe before publish |
| T12.02 | parallel quota, same hash concurrent, max3 uploads | atomic reservation, one file/dedup, no excess bytes |
| T12.03 | file same lesson READY vs foreign/UPLOADING | valid ref/read only; no direct MinIO URL |
| T12.04 | PDF.js selected page, paste, locked background, original deletion | copy independent, draw on top, no original reference |
| T12.05 | delete object/clear then restore, reuse on other page | file retained until lesson end |
| T12.06 | late PUT after finish, MinIO failure, orphan scan | no READY late, eventually removed; ordinary drawing works |
| T13.01 | Identity batch malformed/missing, Education timeout | new join fail closed, no treating outage as false |
| T13.02 | auth refresh15s, outage until60s, recovery | lease expiry suspension/rotation; no unrelated flags cleared |
| T13.03 | recurring originalDate moves/stops, guard resolve | correct identity and times; no recurrence duplication |
| T13.04 | Telemost URL create/update/clear, invalid host | only owner/admin, no network fetch, correct fallback |
| T14.01 | business rollback, Kafka unavailable then up | no event on rollback, stable pending event, publishes later |
| T14.02 | duplicate/stale block/remove/delete event | inbox atomic, no duplicate jobs, no stale access grant |
| T14.03 | outbox ack-before-mark crash, stale lease, ordering | possible duplicate same ID, no sequence reset/overtaking |
| T14.04 | Finished/AttendanceFinalized before cleanup | exact payload incl absent, once, no temporary content |
| T15.01 | crash after guard, after Mongo create, before ACTIVE | resume or compensate, no orphan guard/board |
| T15.02 | crash after FINISHING before cleanup, each external outage | inaccessible, durable retry, eventually COMPLETED |
| T15.03 | failed Mongo commit unknown, restart, stale job | reconcile receipt; no late mutation or state regression |
| T15.04 | retention7d, replay ancient event | keep unfinished/tombstones, no replay side effects |
| T16.01 | 2 users10min, 9 users15min, 10 users15min | measure p95 end-to-end ≤500ms normal network, no accepted data loss |
| T16.02 | 10 concurrent writers10min, 5 groups/50 clients15min | bounded latency/queues/memory, no cross-lesson interference |
| T16.03 | 10 users1h, files/tombstones and slow consumers | memory/storage bounded, cleanup, overload explicit |
| T17.01 | full flow scheduled→start→video+chat+board→finish | actual Identity/Education/LiveKit/Mongo/MinIO/Kafka, history only |
| T17.02 | LiveKit down at start/midlesson, Telemost + same board | teacher presence APP, board independent, video return possible |
| T17.03 | desktop/mobile browser permission denied, reconnect, screen share | usable UI error/device handling, no remote camera enabling |

Load method: browser or realistic instrumented clients, synchronized clocks or shared test clock/measurement process, trace operationId from local change to remote render, distinguish accepted edits/conflicts/overload, include batching and RTT. Report hardware/network/versions, p50/p95/p99, errors, throughput, heap/CPU/Mongo size; don't invent capacity. Target is not SLA in adverse network. E2E frontend ownership coordinated with partner; backend contract harness alone doesn't prove UX/math tools. Test retention, cleanup and actual compose commands in CI where Docker available; if unavailable report exact blockers. No real children/user data in fixtures.
