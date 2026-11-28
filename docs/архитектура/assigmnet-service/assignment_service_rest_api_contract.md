# Assignment Service — публичный REST API

Редакция 27.11.2026. Все пути ниже относительно `/api/v1`. Основа — согласованные требования и модель БД. Примеры UUID сокращены до читаемых обозначений; реальные значения — UUID. Примеры экзаменационных структур/баллов условные, не официальные шкалы.

## 1. Общие соглашения

- JSON, camelCase, UTF-8, UTC ISO 8601, `application/problem+json` для ошибок. Файлы — multipart upload и binary stream download. ID сущностей UUID; subject/examType smallint, topic/source integer, import error bigint.
- JWT и проверка актуального пользователя (Identity); механизм передачи JWT и CSRF унифицировать с реально работающим Education/Identity. Не вводить третий формат. Для защищённых запросов Identity проверяет роли, blocked, profileCompleted; user ID берётся из аутентификации. PARENT-only не имеет доступа к Assignment в текущем MVP (вопрос чтения ДЗ не согласован, fail closed). Мультироли не теряют разрешённые права другой роли.
- TEACHER управляет своими объектами и общим банком только на чтение/копирование; ADMIN управляет существующими объектами всех, импортом и справочниками, не создаёт приватные задачи. Создание шаблонов/назначений за учителя не входит в MVP; обычный TEACHER создаёт свои. ADMIN редактирует/проверяет чужие существующие работы. STUDENT видит собственные работы/тренировки и активный импортированный банк. Чужие приватные ресурсы — 404, запрет роли/блокировка — 403. Никаких прямых MinIO URL или serialization JPA entities.
- Курсорные списки: `limit` default 30 max 100, непрозрачный cursor с версией, сортировкой, фильтрами, scope, идентификатором пользователя/контекста; неправильный cursor 400. Keyset с устойчивым tie-breaker id; не обещать snapshot всей выдачи. Ответ `{ "items": [], "nextCursor": null, "hasMore": false }`. Малые справочники возвращаются целиком.
- 200 чтение/изменение, 201 создание + Location, 202 принята асинхронная операция, 204 без тела, 400 malformed, 401 invalid auth, 403 forbidden, 404 отсутствует/скрыт, 409 конфликт состояния, 413 размер файла, 415 формат, 422 семантика, 503 зависимость. Пример ошибки:

```json
{"type":"about:blank","title":"Conflict","status":409,"code":"TASK_VERSION_CONFLICT","detail":"Задача изменена","currentVersionId":"version-uuid"}
```

- `Idempotency-Key` обязательный UUID для публикации и ручного запуска импорта/retry. Уникальность по actor + операции, hash канонического запроса с маршрутом; совпадение возвращает исходный успешный результат до повторной проверки изменившихся бизнес-условий, но после auth. Иной payload — 409. Не считать Redis источником истины. Ошибка до commit не резервирует ключ навсегда. Другие POST без явно описанной идемпотентности могут создавать новый ресурс; клиент не повторяет вслепую. Revision конфликты 409, missing revision 400. Повторы start/submit/review publish имеют специальную семантику ниже.
- `GET` не запускает экзамен и не изменяет ответы. Все списки/DTO используют серверное время для разрешённых действий, даже если worker отстаёт. HTTP cache-control `no-store` для пользовательских ответов/правильных решений/файлов.

## 2. Банк задач

### 2.1. Маршруты

| Method | Path | Доступ / результат |
|---|---|---|
| GET | `/tasks` | STUDENT/TEACHER/ADMIN, список |
| POST | `/tasks` | TEACHER, 201/202 |
| GET | `/tasks/{taskId}` | доступ к актуальной ACTIVE версии |
| PUT | `/tasks/{taskId}` | owner/ADMIN, 201 новая версия, 200 no-op, 202 pending |
| DELETE | `/tasks/{taskId}` | owner/ADMIN, 204 hide / 202 cleanup |
| POST | `/tasks/{taskId}/copy` | TEACHER, импортированная задача, 201 |
| GET | `/tasks/{taskId}/versions` | owner/ADMIN/TEACHER для imported, история |
| GET | `/tasks/{taskId}/versions/{versionId}` | те же права |
| PATCH | `/tasks/{taskId}/import-settings` | ADMIN, imported, 200 |
| POST | `/tasks/images` | TEACHER/ADMIN, multipart file, 201 |
| DELETE | `/tasks/images/{fileId}` | загрузивший/ADMIN, только unbound, 204 |
| GET | `/tasks/{taskId}/versions/{versionId}/images/{fileId}/content` | контекст версии, binary |
| GET | `/tasks/{taskId}/versions/{versionId}/publication` | owner/ADMIN, статус Saga (техническое дополнение) |
| GET | `/tasks/images/{fileId}/content` | загрузивший/ADMIN, только временный собственный файл (дополнение для предпросмотра) |
| GET | `/tasks/{taskId}/versions/{versionId}/materials/{materialId}/content` | owner/ADMIN/допустимый преподаватель, контекст версии (дополнение) |

### 2.2. Список и карточка

`GET /tasks?scope=AVAILABLE&subjectId=1&examDefinitionId=...&position=6&topicIds=12,15&answerType=NUMBER&search=логарифм`.

Фильтры: scope OWNED (свои, ADMIN с teacherId), IMPORTED, AVAILABLE (свои+импортированные для преподавателя, imported для ученика; ADMIN — доступные), ALL (ADMIN); teacherId только ADMIN; subjectId, examDefinitionId, examStructureVersionId, position, topicIds (OR), checkMode AUTOMATIC/MANUAL, answerType, search по условию, cursor/limit. Default AVAILABLE. Сортировка `(task.created_at DESC, task.id DESC)`. Только текущие ACTIVE неудалённые версии. STUDENT не получает приватную задачу через каталог; выданная версия доступна через submission.

TaskSummary: `{id,versionId,version,subjectId,statement,answerType,checkMode,maxScore,origin,source:{code,externalKey}|null,topics:[{id,name}],createdAt}`. `origin=IMPORTED|PRIVATE`. TaskDetail дополнительно `teacherId,answerConfig,examPositions:[{id,examDefinitionId,structureVersionId,position}],images:[FileDto],materials:[MaterialDto]`. Преподаватель/ADMIN в допустимом контексте получают `correctAnswer,explanation`; ученик не получает эти поля и explanation-only images даже при известном UUID. Ученик получает правильные ответы только через разрешённый контекст submission или practice attempt. Для списка никому не возвращать решения. `FileDto={id,fileName,mimeType,sizeBytes,downloadUrl}`; `MaterialDto={id,fileName,mimeType,sizeBytes,downloadUrl}` (метаданные Education, не ключи S3). Не выдавать материал из приватного банка ученику вне назначения. Для текущего импортированного банка изображения условий доступны ученику, пояснения — нет.

### 2.3. Создание и редактирование

```json
{"subjectId":1,"statement":"Решите $2x+4=10$.","explanation":"$x=3$.","answerType":"NUMBER","answerConfig":{},"correctAnswer":{"acceptedAnswers":["3"]},"maxScore":1,"topicIds":[12],"examPositionIds":[],"materialIds":[]}
```

POST создаёт task и V1. Для auto maxScore default 1, для FILES требуется положительное значение. PUT — полный тот же DTO + `expectedVersionId`; subjectId неизменяем (техническое уточнение; не ломаем классификацию), меняется версия. Under lock проверяем expected и отсутствие pending версии; no-op 200 без новой версии, изменение 201; при ручной правке imported ставим manualOverride. Нельзя редактировать удалённое. При материал-ссылках 202 `{id,versionId,version,publicationState:"PENDING"}`; без них 201 `{id,versionId,version,publicationState:"ACTIVE"}`. Location — `/api/v1/tasks/{id}` либо для pending — status route. `GET .../publication` возвращает `{taskId,versionId,publicationState,errorCode}`; PENDING/ACTIVE/ABORTING; после компенсации 404, ошибка ранее доступна через ABORTING и лог. Предыдущая ACTIVE версия не скрывается до успешной новой. Одновременно одна незавершённая версия на task. Опрос статуса — техническое дополнение к 202, не менять содержимое pending версии.

История версий: `{items:[{id,version,current,createdAt}],nextCursor,hasMore}`, version DESC; доступ к исторической версии — owner/ADMIN или teacher для imported, не STUDENT. Для удалённой задачи история допустима владельцу/ADMIN и в уже выданном контексте, банк скрывает её. DELETE: использовалась в template/assigned_item/practice — hide 204; иначе DELETING cleanup 202 `{resourceId,cleanupJobId}`; повтор той же операции без дубликата, после физического удаления 404. Tombstone imported атомарно с удалением. Шаблон с удалённой задачей нельзя публиковать/генерировать с ней; опубликованные работы неизменны. Копирование `{sourceVersionId}` — отдельный task приватный с V1, файлы могут переиспользоваться; только активная imported задача и опубликованная версия. PATCH import-settings `{manualOverride:false}` — следующий импорт снова может обновить; удалённую не восстанавливает.

### 2.4. Схемы ответов

| answerType | answerConfig | correctAnswer | body ученика |
|---|---|---|---|
| TEXT | `{}` | `{"acceptedAnswers":["Москва"]}` | `{"value":"Москва"}` |
| NUMBER | `{}` | `{"acceptedAnswers":["2.5"]}` | `{"value":"2,5"}` |
| SINGLE_CHOICE | `{"options":[{"id":"A","text":"12"},{"id":"B","text":"15"}]}` | `{"correctOptionId":"B"}` | `{"optionId":"B"}` |
| MULTIPLE_CHOICE | `{"options":[...]}` | `{"correctOptionIds":["A","C"]}` | `{"optionIds":["C","A"]}` |
| MATCHING | `{"keys":["A","B","C"]}` | `{"A":"1","B":"2","C":"3"}` | `{"A":"1","B":"2","C":"3"}` |
| FILES | `{}` | null (SQL NULL) | null, вложения отдельно |

TEXT trim, Unicode case-fold на основе Locale.ROOT, последовательности пробельных символов → один пробел; Е и Ё различны; несколько допустимых ответов. NUMBER строки, trim, запятая→точка, десятичная грамматика `[+-]?[0-9]+([.,][0-9]+)?`, BigDecimal.compareTo; без дробных выражений, NaN/Infinity, экспоненты. SINGLE optionId существует; MULTIPLE сравнение множеств, неизвестные/повторяющиеся IDs отклонять; MATCHING ключи объявлены, значения нормализуются как TEXT; full match. Черновик MATCHING может быть неполным; пустые значения не считаются ответом, при сдаче неполное соответствие = 0; для practice требуется полный непустой ответ. Автопроверка 0/максимум, ручная 0..max целые. Валидация correctAnswer при создании обязательна; правильные ответы не уходят в логи.

Лимиты: statement/explanation 20 000 символов каждый; строка ответа 1000; acceptedAnswers/options/keys/topicIds до 20; maxScore 1..100; изображения до 10, каждое 10 000 000 байт, сумма 50 000 000; JPG/JPEG/PNG/WEBP. Лимиты всех пользовательских строк проверять на backend, не только UI. Markdown+LaTeX безопасный рендеринг, запрещены raw HTML и внешние изображения; проверять `attachment:<uuid>` и связи с версией. Не разрешать JS URL, произвольные URL хранилища, SVG. Содержимое файла проверяется по сигнатуре и типу, не доверять имени. POST images -> `{id,fileName,mimeType,sizeBytes,state:"READY",markdownReference:"attachment:file-uuid",expiresAt}`. TTL 24h для непривязанных. При `DELETE` привязанного файла 409. Скачивание в контексте разрешённой версии, explanation-only скрыто от ученика до разрешённого раскрытия.

## 3. Предметы, темы, экзамены и шкалы

| Method | Path | Доступ / DTO |
|---|---|---|
| GET | `/subjects` | все разрешённые роли; `[{id,name}]` |
| GET | `/topics` | `subjectId,search,active,limit,cursor`; TopicDto `{id,subjectId,name,isActive}` |
| POST | `/topics` | ADMIN `{subjectId,name}`, 201 |
| PATCH | `/topics/{topicId}` | ADMIN `{name?,isActive?}`, 200 |
| DELETE | `/topics/{topicId}` | ADMIN, деактивация, 204 |
| GET | `/exam-types` | `subjectId`; `[{id,code,name,subjectId}]` |
| GET | `/exams` | `examTypeId,year,limit,cursor`; ExamSummary |
| POST | `/exams` | ADMIN; создание экзамена и V1 структуры, 201 |
| GET | `/exams/{examId}` | ExamDetail текущей структуры |
| GET | `/exams/{examId}/structures` | история, version DESC, cursor |
| POST | `/exams/{examId}/structures` | ADMIN, полная новая структура, 201 |
| GET | `/exams/{examId}/structures/{versionId}` | StructureDto |
| GET | `/exams/{examId}/scales` | ScaleSummary list |
| POST | `/exams/{examId}/scales` | ADMIN, 201 |
| GET | `/grading-scales/{scaleId}` | ScaleDto актуальной версии |
| GET | `/grading-scales/{scaleId}/versions` | история, cursor |
| POST | `/grading-scales/{scaleId}/versions` | ADMIN, новая версия, 201 |
| GET | `/grading-scales/{scaleId}/versions/{versionId}` | ScaleVersionDto |

Предметы и типы экзамена фиксируются миграциями: Математика, EGE_PROFILE/EGE_BASE/OGE; нет CRUD. Темы деактивируются, старые ссылки сохраняются; не выбирать неактивную тему для новой классификации. Название темы уникально в предмете, текущий label может обновиться для истории (ID неизменен). Списки справочников при пагинации сортировать `(name,id)` или `(year DESC,id)` для экзаменов.

POST exams:
```json
{"examTypeId":1,"year":2027,"structure":{"durationMinutes":180,"positions":[{"position":1,"maxScore":1,"checkMode":"AUTOMATIC","topicIds":[12]},{"position":2,"maxScore":2,"checkMode":"MANUAL","topicIds":[15]}]}}
```

ExamSummary `{id,examTypeId,year,currentStructureVersionId}`; ExamDetail добавляет `structure`; StructureDto `{id,examDefinitionId,version,durationMinutes,maxScore,positions:[{id,position,maxScore,checkMode,topics:[{id,name}]}],createdAt}`. POST structures принимает объект structure + `expectedStructureVersionId`. Проверка уникальности (type,year), последовательные уникальные положительные позиции, maxScore 1..100, 1..100 позиций, duration 1..1440, темы того же предмета. Старые структуры неизменны. Переносить привязки к новым позициям только для подтверждённо совместимых: прежняя позиция, балл, checkMode и семантика/темы; при сомнении не переносить, требуется переклассификация. Создавать новую версию задачи, не менять старую; не перетирать ручные изменения, использовать expectedVersionId и Saga при материалах. Прогресс массовой переклассификации — техническая задача реализации; старые шаблоны остаются рабочими.

POST scales:
```json
{"name":"Основная шкала","structureVersionId":"structure-uuid","values":[{"primaryScore":0,"testScore":0},{"primaryScore":1,"testScore":5},{"primaryScore":2,"testScore":10}]}
```
ScaleSummary `{id,examDefinitionId,name,currentVersionId}`; ScaleVersionDto `{id,scaleId,structureVersionId,version,values:[{primaryScore,testScore,schoolMark}],createdAt}`; ScaleDto включает текущую версию. Новая версия требует `expectedVersionId`, полной карты, соответствия структуры тому же exam, уникального значения для каждого целого 0..max, монотонности, testScore 0..100 для профильного или schoolMark 2..5 для прочих; второе поле null. При публикации строгого варианта обязательна совместимая версия шкалы (даже mode HOMEWORK). `ExamScoreConverter` получает конкретную версию. Дополнительные официальные условия по разделам — отдельное уточнение модели, не выдумывать.

## 4. Шаблоны и конструктор

| Method | Path | Доступ / статус |
|---|---|---|
| GET | `/assignment-templates` | owner/ADMIN, cursor |
| POST | `/assignment-templates` | TEACHER, 201 |
| GET | `/assignment-templates/{id}` | owner/ADMIN, 200 |
| PUT | `/assignment-templates/{id}` | owner/ADMIN, 200 |
| DELETE | `/assignment-templates/{id}` | owner/ADMIN, 204 |
| POST | `/assignment-templates/{id}/copy` | TEACHER owner, 201 |
| POST | `/assignment-templates/{id}/generate` | owner/ADMIN, 200 |
| POST | `/assignment-templates/{id}/publication-preview` | TEACHER owner, 200 |
| POST | `/assignment-templates/{id}/publish` | TEACHER owner, 201 |

POST/PUT body (PUT добавляет expectedRevision):
```json
{"title":"Логарифмы","description":"Повторение","subjectId":1,"examStructureVersionId":null,"items":[{"taskVersionId":"version-uuid","position":1,"maxScore":3}]}
```
Для exam variant `examStructureVersionId` задан, item содержит `examPositionId`, maxScore определяется сервером, несовпадающее значение отклоняем. Позиция совпадает с номером examPosition; пропуски допустимы только в черновике строгого варианта. Обычная работа — позиции 1..N без пропусков. Предметы/права/формат проверки совместимы; no duplicate taskId даже разных версий; active task/version, запрет pending/deleted. Пустой шаблон разрешён, публикация — нет. Версии не обновляются автоматически. PUT атомарно заменяет состав + revision++, stale → 409. Существующую удалённую задачу можно убрать из черновика; нельзя заново добавить. Copy `{title}` — новый ID и те же разрешённые версии, deleted task препятствует копированию до замены.

TemplateSummary `{id,teacherId,subjectId,title,description,revision,examStructureVersionId,taskCount,maxScore,publishable,createdAt,updatedAt}`; detail добавляет items `[{id,taskId,taskVersionId,version,currentVersionId,position,maxScore,examPositionId,statement,answerType,taskDeleted,newerVersionAvailable}]`. Список фильтры subjectId,type HOMEWORK/EXAM_VARIANT,examDefinitionId,search,teacherId (ADMIN); `(updated_at DESC,id DESC)`. DELETE не публиковался — физически, иначе soft delete. Архив истории назначения остаётся.

Generate `{expectedRevision,strategy:"FILL_EMPTY"|"REGENERATE_ALL",taskScope:"IMPORTED"|"AVAILABLE"}` — только строгий вариант. Подбор случайно из подходящих доступных активных версий, без повторного taskId. Не жертвовать полным заполнением из-за жадного подбора: использовать сопоставление позиций и кандидатов при пересечениях; при FILL_EMPTY зафиксировать существующие. Ответ `{templateId,revision,generatedCount,missingPositions:[{examPositionId,position,reason:"NO_AVAILABLE_TASKS"}],complete}`. Частичный результат сохраняется атомарно и возвращает 200; запрос с устаревшей ревизией 409. Для ADMIN AVAILABLE означает банк владельца шаблона, не чужие приватные задачи.

## 5. Публикация и управление назначением

Preview `{courseIds,studentIds}` -> `{recipientCount,eligibleCount,blockedStudents:[{studentId,displayName}],alreadyAssignedStudents:[{studentId,displayName}],invalidCourseIds,ineligibleStudentIds,publishable}`. Preview не резервирует состав/права. Выбранные курсы принадлежат owner, отдельные ученики в хотя бы одном его активном курсе, объединяем и дедуплицируем, фиксируем снимок Education, проверяем Identity (роль STUDENT, blocked=false, profileCompleted=true). Неверные course/student — ошибка, не пропускать. Список blocked/previously assigned — 409 без изменений по умолчанию.

POST publish, обязательный Idempotency-Key:
```json
{"expectedRevision":3,"title":"ДЗ №5","comment":"До пятницы","mode":"HOMEWORK","dueAt":"2026-12-04T17:00:00Z","durationMinutes":null,"revealPolicy":"AFTER_DEADLINE","scaleVersionId":null,"courseIds":["course-uuid"],"studentIds":["student-uuid"],"skipAlreadyAssigned":false,"skipBlockedStudents":false}
```

Дедлайн в будущем; режим EXAM требует строгого полного варианта и duration 1..1440 (если не передано — стандарт структуры; HOMEWORK null). Политика reveal по умолчанию AFTER_DEADLINE; после публикации неизменна. ScaleVersionId для строгого варианта обязателен, для обычного null. Публикация проверяет template revision, owner, все версии ACTIVE и не удалены, наличие материалов, полный состав. Пропуск уже назначенных/blocked только при соответствующих флагах; если получателей 0 — 422 NO_RECIPIENTS. Курс snapshot, получателей не добавлять после. Лимиты 100 задач, 20 выбранных курсов, 500 уникальных получателей после объединения до фильтрации; 500 явно выбранных studentIds. Title 255, description/comment 4000, revoke reason 1000. Все assignment/items/submissions/course snapshot/reminders/outbox/idempotency в одной SQL транзакции. Снимок Education не атомарен с локальным commit; допустима гонка с исключением после снимка. Конкурентная публикация — проверка UNIQUE + блокировка шаблона, повторная оценка конфликтов.

Ответ 201 `{id,templateId,title,status:"ACTIVE",recipientCount,taskCount,maxScore,dueAt,publishedAt,skippedStudentIds:[]}`; сохранённый receipt для повторов. `Location: /api/v1/assignments/{id}`.

| Method | Path | Доступ / результат |
|---|---|---|
| GET | `/assignments` | owner/ADMIN, cursor |
| GET | `/assignments/{id}` | owner/ADMIN, detail |
| PATCH | `/assignments/{id}` | owner/ADMIN, 200 |
| DELETE | `/assignments/{id}` | owner/ADMIN, 204 |
| GET | `/assignments/{id}/submissions` | owner/ADMIN, cursor |
| POST | `/assignments/{id}/submissions/{submissionId}/revoke` | owner/ADMIN, 204 |

Список filters status,courseId,templateId,mode,search,publishedFrom,publishedTo,dueFrom,dueTo,teacherId (ADMIN), cursor. Default ACTIVE, sort `(published_at DESC,id DESC)`. AssignmentSummary `{id,templateId,teacherId,title,mode,status,dueAt,publishedAt,taskCount,maxScore,recipientCount,statusCounts:{NOT_STARTED,IN_PROGRESS,SUBMITTED,IN_REVIEW,REVIEWED,MISSED,REVOKED}}`. Detail добавляет comment,durationMinutes,revealPolicy,examStructureVersionId,scaleVersionId,courses:[{id,title}],items с закреплённой версией/максимумом. Индивидуальные работы отдельно, формат SubmissionSummary (раздел 6/8), фильтры studentId,status,courseId,sort,cursor.

PATCH `{title?,comment?,dueAt?}`: только эти поля; absent не изменяет, null допустим только для comment. Не продлевать старый истёкший дедлайн; новый строго future. Под общей блокировкой пересчитать effectiveDeadline незавершённых и ещё не истёкших, отменить/пересоздать reminders, события. ARCHIVED не прекращает выполнение, допускает разрешённые изменения; CANCELLED неизменяем. DELETE: если есть SUBMITTED/IN_REVIEW/REVIEWED — ARCHIVED, оставшиеся активны; иначе CANCELLED, незавершённые ещё не истёкшие REVOKED, MISSED остаются. Уже истёкшие попытки сначала логически финализировать по сроку, затем выбрать cancel/archive. Повтор DELETE no-op. Revoke `{reason?}` только NOT_STARTED/IN_PROGRESS до фактического дедлайна; REVOKED повтор 204; прочие 409. Повторное назначение template/student даже после revoke запрещено. Причина сохраняется в событии (не отдельный audit).

## 6. Выполнение: API ученика

| Method | Path | Результат |
|---|---|---|
| GET | `/submissions/my` | cursor, собственные |
| GET | `/submissions/{id}` | metadata и навигация |
| POST | `/submissions/{id}/start` | 200, без body |
| GET | `/submissions/{id}/items/{itemId}` | полная задача |
| PUT | `/submissions/{id}/items/{itemId}/answer` | 200, ответ сохранён |
| DELETE | `/submissions/{id}/items/{itemId}/answer` | 204, очищен body |
| POST | `/submissions/{id}/items/{itemId}/attachments` | multipart file, 201 |
| DELETE | `/submissions/{id}/items/{itemId}/attachments/{fileId}` | 204 |
| GET | `/submissions/{id}/items/{itemId}/attachments/{fileId}/content` | binary |
| GET | `/submissions/{id}/items/{itemId}/images/{fileId}/content` | binary |
| GET | `/submissions/{id}/items/{itemId}/materials/{materialId}/content` | binary |
| POST | `/submissions/{id}/submit` | 200, без body |

STUDENT owner; file GET также teacher-владелец назначения и ADMIN в рамках разрешённого контекста. Для проверки используется отдельный teacher DTO. `fileId` в attachments маршрутах означает ID связи answer_attachment (для images — stored_file). Отозванные работы скрыты от ученика, сохраняются преподавателю. Права на существующую работу не зависят от членства/удаления курса. Список `/my` filters status,mode,teacherId,courseId,subjectId,search,dueFrom,dueTo,scope ACTIVE/AWAITING_REVIEW/COMPLETED,limit,cursor. ACTIVE означает доступна для выполнения по времени, AWAITING_REVIEW — SUBMITTED/IN_REVIEW, COMPLETED — REVIEWED/MISSED; временно истёкшая строка с задержкой worker отражается с canEdit=false, `expirationPending=true`, не выдаётся как активная. Sort `(assigned_at DESC,id DESC)`.

SubmissionSummary `{id,assignmentId,title,teacher:{id,displayName},mode,status,taskCount,answeredTaskCount,maxScore,automaticScore,finalScore,dueAt,effectiveDeadlineAt,assignedAt,expirationPending}`. Считаем answered по непустому body/привязанным файлам; частичное matching считается начатым ответом. Detail добавляет `comment,serverTime,startedAt,submittedAt,reviewedAt,durationMinutes,revealPolicy,canEdit,canSubmit,canStart,testScore,schoolMark,teacherComment,items:[{id,position,answerType,maxScore,answered,attachmentCount}]`. До EXAM start items пуст или без содержимого/типов (только count), никакие задачи/файлы/материалы недоступны; до старта warning если доступное время меньше duration. HOMEWORK frontend вызывает start при открытии; start обязателен перед записью/submit. Повтор start активной попытки возвращает тот же startedAt/effectiveDeadlineAt; завершённую не открывает. Response `{id,status,startedAt,effectiveDeadlineAt,serverTime}`. Серверный таймер не зависит от клиента.

ItemDetail:
```json
{"id":"item-uuid","taskId":"task-uuid","taskVersionId":"version-uuid","position":1,"statement":"Решите $2x+4=10$.","answerType":"NUMBER","answerConfig":{},"maxScore":1,"answer":{"body":{"value":"3"},"revision":2,"updatedAt":"2026-11-27T12:10:00Z"},"attachments":[],"materials":[],"images":[],"result":null}
```

Absent answer -> `{body:null,revision:0,updatedAt:null}`. Материал/картинки доступны лишь если связаны с закреплённой версией и разрешены политикой. PUT `{expectedRevision:2,body:{value:"3"}}` -> `{body,revision:3,updatedAt}`; первое expected=0. Только формат, не проверка правильности; черновик NUMBER может содержать незавершённый ввод в пределах длины (техническое уточнение для автосохранения; при сдаче некорректное число = 0, для practice строго формат). Сериализовать запросы frontend, debounce 500–1000 ms, flush при смене задачи, дождаться подтверждения всех save/upload перед submit. Revision stale 409 `currentRevision`; повторный GET помогает восстановить состояние. DELETE требует `If-Match: "2"` с ревизией, очищает только body, сохраняет строку и увеличивает revision; 204. FILES body null, через attachment.

Upload: 20 000 000 B per file, 10 per item, 50 000 000 B per item, 200 000 000 B per submission, JPG/JPEG/PNG/WEBP/PDF; decimal MB. Предварительная проверка, metadata UPLOADING, потоковый PUT в приватное хранилище, повторная авторизация и SQL lock submission, проверка срока/лимитов и немедленная привязка READY→ATTACHED. Непривязанное — TTL 24h, late/fail -> cleanup, уникальный object key. Успех `{id,fileName,mimeType,sizeBytes,position,downloadUrl}`. При upload после срока или после submit не прикреплять. Optional photos в автоматической задаче допустимы, автопроверка по body. DELETE attachment отвязывает под тем же lock, создаёт cleanup_job; физически асинхронно. Download streaming, Content-Type/Content-Disposition безопасны, `nosniff`, no-store, никаких object_key или presigned URL.

Submit: под общей блокировкой закрепить сохранённые ответы, материализовать пустые, auto 0/max, manual ожидают оценки; status SUBMITTED при наличии FILES, иначе REVIEWED, итог и шкала. Успех `{id,status,submittedAt,taskCount,answeredTaskCount,automaticScore,automaticMaxScore,pendingManualTaskCount,maxScore,finalScore,testScore,schoolMark}`. Повтор уже SUBMITTED/IN_REVIEW/REVIEWED -> актуальный результат без новых событий. Явная пустая сдача начатой работы допустима (делегированное техническое решение): 0 авто, ручные требуют явного 0. По времени — автоотправка при сохранённых непустых ответах/файлах, иначе MISSED; не восстанавливать. Запоздалый submit не принимает новые ответы и не заменяет правила worker; завершить по правилам истечения и вернуть состояние (MISSED -> 409 DEADLINE_EXPIRED). Для NOT_STARTED -> 409 при явной сдаче, EXAM обязателен start. Все записи проверяют server clock после lock (`clock_timestamp()` либо свежий Clock), не только NOW() в начале транзакции.

## 7. Видимость результатов

| Состояние | Ответ ученика | Исходный автобалл | Ручной балл/комментарии | Итог |
|---|---|---|---|---|
| NOT_STARTED / IN_PROGRESS | свои, EXAM условия только после start | нет | нет | нет |
| SUBMITTED / IN_REVIEW | да | да | нет | нет |
| REVIEWED | да | да | опубликованные | да |
| MISSED | нет новых ответов | нет | нет | нет |
| REVOKED | скрыто | нет | нет | нет |

Item.result после сдачи `{automaticScore,manualScore,effectiveScore,comment,correctAnswer,explanation}`; скрытые поля исключаются или null согласно единой DTO-схеме, файлы пояснений отфильтрованы. `NEVER` никогда не раскрывает правильный ответ; AFTER_SUBMISSION — submittedAt есть; AFTER_DEADLINE — submittedAt есть и текущий момент >= общий assignment.dueAt; AFTER_REVIEW — REVIEWED. До сдачи не раскрывать, MISSED не считается сдачей. Правильные решения/пояснения/их images используют ту же policy. Даже на прямой download route проверять context/reveal. Для преподавателя, проверяющего собственную сданную работу, правильные ответы доступны. Тренировка общего банка может раскрыть ответ к той же задаче в ДЗ; прокторинга и блокировок между режимами нет. Автоматические баллы независимы от reveal policy.

## 8. Проверка преподавателем

| Method | Path | Результат |
|---|---|---|
| GET | `/submissions` | история owner/ADMIN, cursor |
| GET | `/submissions/review-queue` | SUBMITTED/IN_REVIEW, cursor |
| GET | `/submissions/review-queue/summary` | счётчики |
| GET | `/submissions/{id}/review` | все задачи, решения и оценки |
| POST | `/submissions/{id}/review/start` | 200, без body |
| PUT | `/submissions/{id}/items/{itemId}/grade` | 200 |
| PUT | `/submissions/{id}/review/comment` | 200 |
| POST | `/submissions/{id}/review/publish` | 200 |
| PATCH | `/submissions/{id}/review` | 200, исправление опубликованного |

Owner/ADMIN, даже если ученик исключён. Очередь sort `(submitted_at ASC,id ASC)`, filters courseId,studentId,assignmentId,templateId,teacherId (ADMIN),status,mode,submittedFrom,submittedTo,limit,cursor. QueueSummary `{submittedCount,inReviewCount,totalCount}` с теми же разрешёнными фильтрами, без cursor. QueueItem `{id,assignmentId,templateId,title,student:{id,displayName},mode,status,submittedAt,taskCount,automaticScore,automaticMaxScore,manualTaskCount,gradedManualTaskCount,maxScore}`. History поддерживает все статусы, фильтры очереди + диапазоны назначения/проверки/дедлайна, sort ASSIGNED_DESC (default), SUBMITTED_DESC, REVIEWED_DESC, DUE_ASC с id tie-breaker, NULLS LAST. `/assignments/{id}/submissions` тот же формат со scope назначения. Не смешивать private данные других учителей при получении очереди.

ReviewDetail `{id,assignmentId,title,status,reviewRevision,student,submittedAt,maxScore,automaticScore,finalScore,draftScore,teacherComment,manualTaskCount,gradedManualTaskCount,canPublish,items:[{id,position,answerType,statement,correctAnswer,explanation,maxScore,answer,attachments,images,materials,grade:{automaticScore,manualScore,effectiveScore,comment}}]}`. Только после сдачи; данные файлов в metadata, не base64. Для teacher/ADMIN review view включает оба балла. `draftScore` — сумма имеющихся effectiveScore, отсутствующие не означают проверенный 0. Start SUBMITTED→IN_REVIEW, reviewRevision++, повтор IN_REVIEW no-op. Нет эксклюзивного checker lock. До start изменение черновика запрещено.

PUT grade `{expectedReviewRevision:4,manualScore:2,comment:"Ошибка вычисления"}`; `manualScore:null` сбрасывает override/ручную оценку, 0 — полноценная оценка. Для автоматических можно менять override, исходный automaticScore неизменен. Ответ `{itemId,automaticScore,manualScore,effectiveScore,maxScore,comment,reviewRevision,draftScore,gradedManualTaskCount,canPublish}`. PUT comment `{expectedReviewRevision:5,comment:"Повторить тему"}` -> `{teacherComment,reviewRevision}`. Оба комментария nullable, ≤4000 символов. Блокировка submission и общая ревизия, ++ на успешное изменение; stale 409. Параллельное изменение разных задач также может конфликтовать.

POST publish `{expectedReviewRevision:10}`: все auto имеют automaticScore, все FILES manualScore (явный ноль даже за пропуск), пересчёт суммы и шкалы, status REVIEWED, reviewedAt/by, outbox SubmissionReviewed, revision++. Нет частичного publish. Ответ `{id,status,automaticScore,finalScore,maxScore,testScore,schoolMark,reviewRevision,reviewedAt}`. Повтор исходной команды при уже REVIEWED не генерирует событие и возвращает актуальный итог; не может менять оценки. Незавершённая проверка 422 `ungradedItemIds`.

PATCH published review:
```json
{"expectedReviewRevision":11,"grades":[{"itemId":"item-uuid","manualScore":2,"comment":"Исправление"}],"teacherComment":"Повторно проверено"}
```
Поля absent не меняются; null комментарий очищает; grade manualScore=null допустим для auto, для ручной опубликованной — запрещён. Все изменения атомарны, без черновика, status REVIEWED, пересчитать агрегаты и версию шкалы, сохранить latest author/time/revision; исходные auto и reviewedAt сохраняются. При реальном изменении создать SubmissionGradeUpdated; no-op не публикует. Ответ как publish. История старых ручных оценок не хранится.

## 9. Самостоятельные тренировки

| Method | Path | Результат |
|---|---|---|
| GET | `/practice/tasks` | cursor списка импортированных auto |
| GET | `/practice/tasks/{taskId}` | TaskDetail без решения + practiceStatus/attemptId |
| POST | `/practice/tasks/{taskId}/submit` | 201 первая попытка, 200 повтор |
| GET | `/practice/attempts` | cursor истории |
| GET | `/practice/attempts/{attemptId}` | историческое решение |
| GET | `/practice/attempts/{attemptId}/images/{fileId}/content` | binary |

STUDENT только свои attempts. Фильтры practice/tasks как банк, status ALL/NOT_ATTEMPTED/CORRECT/INCORRECT, sort как tasks. TaskSummary дополняется practiceStatus, attemptId. Submit `{taskVersionId,answer:{value:"3"}}`; сначала искать существующую попытку того же student/task: та же версия и канонически одинаковый payload -> 200 сохранённый результат даже после смены версии/удаления; иначе 409 PRACTICE_ALREADY_ATTEMPTED. Если попытки нет, требуем текущую ACTIVE auto imported версию; устаревшая 409 TASK_VERSION_CONFLICT без расхода, неверный формат 422. UNIQUE(student_id,task_id), lock task, answer check и insert. Ответ `{attemptId,taskId,taskVersionId,isCorrect,score,maxScore,answer,correctAnswer,explanation,submittedAt}`; GET attempt добавляет historical statement,answerType,answerConfig,images,topics. История фильтры isCorrect,topicId,examDefinitionId,from,to,limit,cursor, `(submitted_at DESC,id DESC)`. Удалённая задача остаётся в попытке. Images разрешены только по принадлежности попытке, можно показывать решение после submit. Другие виды работ не блокируются attempt. Practice не поддерживает файлы или черновик.

## 10. Аналитика

Все пути `/students/{studentId}/analytics/...`. STUDENT self, TEACHER current student любого своего активного курса (синхронный Education membership), ADMIN all, PARENT no. Не раскрываем приватные работы чужих преподавателей, в том числе item/assignment IDs. Общие фильтры subjectId,examDefinitionId,source ALL/HOMEWORK/EXAM/PRACTICE,from inclusive,to exclusive. Источник — REVIEWED и practice_attempt, дата сдачи (не проверки), `COALESCE(manual,auto)` / assigned_item.max_score либо attempt.score / task_version.max_score. Каждый оценённый item, в т.ч. пропущенный в сданной работе с 0, — наблюдение. MISSED/REVOKED/черновики исключены. Повторы одной задачи в разных выданных работах учитываются как отдельные наблюдения, distinctTaskCount по taskId. Фильтр экзамена использовать EXISTS, не размножать строки по N:M. Результат участвует в каждой теме своей версии; общий summary считает каждую попытку единожды. AVG нормализованных результатов *100, numeric, round до 2 знаков при выдаче, классификация до округления. Null при отсутствии данных. <5 различных задач — INSUFFICIENT_DATA; иначе <50 WEAK, <80 NEEDS_PRACTICE, >=80 MASTERED. Темы без данных возвращать для выбранного предмета (активные + исторические при наличии результатов), слабые — только за 30 суток по умолчанию. Интервалы UTC, неделя с понедельника. Нет отдельной таблицы агрегатов.

| Path | Дополнительные параметры | Response |
|---|---|---|
| `/summary` | общие | `{studentId,subjectId,attemptedTaskCount,distinctTaskCount,fullyCorrectTaskCount,averageScorePercent,reviewedHomeworkCount,reviewedExamCount,practiceAttemptCount,topicStatistics:{masteredCount,needsPracticeCount,weakCount,insufficientDataCount}}` |
| `/topics` | общие | `{items:[TopicStatistics]}` |
| `/topics/{topicId}` | общие | TopicStatistics |
| `/dynamics` | topicId,bucket DAY/WEEK/MONTH,from,to | `{bucket,points:[{periodStart,attemptCount,averageScorePercent}]}` |
| `/weak-topics` | subjectId,examDefinitionId,source | `{items:[TopicStatistics]}` только WEAK, ascending average, tie topicId |
| `/exams` | examTypeId,year,from,to,limit,cursor | cursor, ExamResult |

TopicStatistics `{topicId,topicName,attemptCount,distinctTaskCount,fullyCorrectCount,averageScorePercent,status}`. Topics сортировать по name/id; dynamics для пустых bucket count=0, avg=null; ограничить запрос 366 daily / 156 weekly / 120 monthly buckets (делегированный технический лимит), from/to обязательны. Exams `[{submissionId,examYear,examType,submittedAt,primaryScore,maxPrimaryScore,testScore,schoolMark}]` по `(submitted_at DESC,id DESC)` только REVIEWED строгие варианты (mode HOMEWORK или EXAM; добавить mode для различения); чужой преподаватель получает `submissionId:null`, не открывает чужое ДЗ. Summary topicStatistics — по переданному периоду; weak-topics фиксированные последние 30 дней.

## 11. Автоподбор задач

POST `/assignment-templates/task-suggestions`, TEACHER current student / ADMIN, read-only, 200.
```json
{"studentId":"student-uuid","subjectId":1,"examDefinitionId":"exam-uuid","taskCount":10,"taskScope":"AVAILABLE","checkMode":"ANY"}
```
TaskCount 1..100, scope IMPORTED/AVAILABLE (для ADMIN без владельца только IMPORTED, техническое безопасное ограничение), checkMode AUTOMATIC/MANUAL/ANY. Берём WEAK за 30 дней, веса `1-average/100`, квоты методом наибольших остатков, tie topicId; приоритет ранее не решённых и не назначенных задач; уникальность taskId; повторно решавшиеся при нехватке новых, перераспределить остаток по другим слабым темам при нехватке; не переходить к неслабым без запроса. Частичный результат, если банк исчерпан. Нет слабых тем/данных — empty + warning, не случайная подборка. Response `{studentId,requestedCount,selectedCount,complete,topics:[{topicId,topicName,averageScorePercent,requestedCount,selectedCount}],items:[{taskId,taskVersionId,position,maxScore,previouslyAttempted,selectionTopicId}],warnings:[{code}]}`. Warnings NO_WEAK_TOPICS, INSUFFICIENT_DATA, INSUFFICIENT_AVAILABLE_TASKS. Система ничего не сохраняет/назначает; преподаватель редактирует/создаёт template сам.

## 12. Управление импортом

Только ADMIN, включая чтение.

| Method | Path | Результат |
|---|---|---|
| GET | `/import-sources` | `{items:[{id,code,name,enabled}]}` |
| PATCH | `/import-sources/{id}` | `{enabled}`, 200 |
| GET | `/import-configs` | cursor, filters sourceId,enabled |
| POST | `/import-configs` | 201 |
| GET | `/import-configs/{id}` | ConfigDto |
| PUT | `/import-configs/{id}` | 200, полная конфигурация |
| DELETE | `/import-configs/{id}` | 204, deactivate/soft delete |
| POST | `/import-configs/{id}/run` | 202, Idempotency-Key |
| GET | `/import-jobs` | cursor истории |
| GET | `/import-jobs/{id}` | JobDto |
| GET | `/import-jobs/{id}/errors` | cursor |
| POST | `/import-jobs/{id}/retry` | 202, Idempotency-Key |

SourceCode определяется приложением, не принимать произвольные URL. Отключение source запрещает новые run/schedule, текущий запуск может закончиться. Config body `{sourceId:1,name:"Профиль",config:{examDefinitionId:"exam-uuid",positions:[1,2,3]},cronExpression:"0 0 3 * * MON",enabled:true}`. ConfigDto добавляет id,nextRunAt,createdAt,updatedAt; sourceId неизменяем при PUT; config snapshot фиксируется при запуске (включая выбранную версию структуры). Cron Spring 6 fields UTC, null при manual-only, enabled только при валидном cron. Ручной run допускается при disabled schedule, но не deleted config/disabled source. Сортировка configs `(created_at DESC,id DESC)`. DELETE сохраняет историю, отменяет расписание. Пропущенные scheduled runs не навёрстываются пачкой; если источник уже занят — пропустить этот тик и перенести nextRunAt. Только один PENDING/PROCESSING по source. Run/retry conflict 409 IMPORT_ALREADY_RUNNING; повтор idempotency возвращает первоначальный job. Reply `{jobId,status:"PENDING",createdAt}` + Location `/api/v1/import-jobs/{id}`. Retry только terminal FAILED/COMPLETED_WITH_ERRORS (решение по умолчанию), использует configSnapshot и новый job, прежний сохраняется. Если source disabled — 409. Повторная обработка всего набора без дубликатов, прежние версии неизменны. При перезапуске после потери lease job завершить FAILED/INTERRUPTED в журнале (status FAILED, errorCode INTERRUPTED), затем новый retry; не возобновлять счётчики с начала того же job. Это безопасное упрощение MVP, без дополнительной таблицы checkpoint.

JobDto `{id,sourceId,configId,triggerType,status,processedCount,createdCount,updatedCount,skippedCount,failedCount,startedAt,finishedAt,createdAt,retryOfJobId}`; trigger MANUAL/SCHEDULED; status PENDING/PROCESSING/COMPLETED/COMPLETED_WITH_ERRORS/FAILED. Poll 3–5 s, не показывать процент если total неизвестно. History filters sourceId,configId,status,triggerType,from,to; sort created_at DESC,id DESC. ErrorDto `{id,externalKey,errorCode,message,createdAt}` sort `(id ASC)`; ошибки SOURCE_UNAVAILABLE, INVALID_TASK_FORMAT, ANSWER_MISSING, IMAGE_DOWNLOAD_FAILED, CLASSIFICATION_FAILED, UNSUPPORTED_ANSWER_TYPE, INTERRUPTED. Не возвращать stack trace/credentials. Парсер fixture-first; реальный адаптер только после проверки доступа/прав.

## 13. Сводка ошибок

| HTTP | Codes (примеры обязательных бизнес-кодов) |
|---|---|
| 400 | INVALID_REQUEST, INVALID_CURSOR, INVALID_IDEMPOTENCY_KEY, REVISION_REQUIRED |
| 401 | UNAUTHENTICATED |
| 403 | FORBIDDEN, USER_BLOCKED, PROFILE_INCOMPLETE |
| 404 | TASK_NOT_FOUND, TEMPLATE_NOT_FOUND, SUBMISSION_NOT_FOUND, ITEM_NOT_FOUND, FILE_NOT_FOUND, IMPORT_JOB_NOT_FOUND |
| 409 | TASK_VERSION_CONFLICT, TASK_NOT_AVAILABLE, TEMPLATE_VERSION_CONFLICT, RECIPIENT_CONFLICTS, IDEMPOTENCY_KEY_CONFLICT, DEADLINE_EXPIRED, SUBMISSION_REVOKED, SUBMISSION_ALREADY_SUBMITTED, EXAM_NOT_STARTED, ANSWER_VERSION_CONFLICT, REVIEW_VERSION_CONFLICT, REVIEW_NOT_STARTED, SUBMISSION_NOT_SUBMITTED, SUBMISSION_ALREADY_REVIEWED, PRACTICE_ALREADY_ATTEMPTED, IMPORT_ALREADY_RUNNING, MATERIAL_DELETING, MATERIAL_IN_USE |
| 413 | FILE_TOO_LARGE |
| 415 | UNSUPPORTED_FILE_TYPE |
| 422 | EMPTY_TEMPLATE, DUPLICATE_TASK, EXAM_VARIANT_INCOMPLETE, EXAM_POSITION_MISMATCH, INCOMPATIBLE_GRADING_SCALE, NO_RECIPIENTS, INVALID_DEADLINE, INVALID_ANSWER_FORMAT, ATTACHMENT_LIMIT_EXCEEDED, REVIEW_INCOMPLETE, INVALID_SCORE, COMMENT_TOO_LONG |
| 503 | IDENTITY_UNAVAILABLE, EDUCATION_UNAVAILABLE, STORAGE_UNAVAILABLE |

Ошибки с дополнительными полями: RECIPIENT_CONFLICTS `conflicts:[{studentId,reason}]`, ANSWER_VERSION_CONFLICT `currentRevision`, REVIEW_INCOMPLETE `ungradedItemIds`. Не сообщать чужие private UUID/правильные ответы в ошибках. Все вызовы с актуальными правами, проверка принадлежности item/attachment/reference к указанному контексту обязательна независимо от валидного UUID.

## 14. Технические уточнения редакции

Добавлены status endpoint для pending версии, предпросмотр временного изображения и teacher material route, чтобы 202 и редактор были работоспособными. Зафиксированы безопасные defaults для ранее не определённых пограничных случаев (родители, пустая сдача, даты, cron, лимиты динамики, доступ ADMIN). Это делегированные решения, а не цитаты ранее явно согласованных требований. Состояние БД синхронизировано в соответствующем документе. Репозиторий и реальные интеграции не проверены; данный контракт не OpenAPI/сгенерированный клиент.
