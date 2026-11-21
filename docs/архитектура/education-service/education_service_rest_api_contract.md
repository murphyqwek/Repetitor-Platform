# Education Service — REST API-контракт (v1)

**Дата:** 20.11.2026  
**Статус:** **согласованный базовый REST API-контракт MVP**, включая маршруты, DTO, HTTP-статусы и подтверждение конфликтов. Финальные SQL-миграции и реализация ещё предстоят. Бизнес-правила — `education_service_requirements_latest.md`, таблицы — `education_service_db_design.md`.  
**Связанные документы:** `chat_history_api_contract.md`, `chat_websocket_contract.md`.

> **Граница:** REST используется для управления курсами, расписанием, библиотекой, вложениями и историей чата. **Отправка нового сообщения — только WebSocket/STOMP**, без `POST /chat/messages`. Удаление курса реализуется **сейчас**; влияние удаления на данные Assignment Service решим при проектировании Assignment.

## 1. Общие соглашения

- Базовый префикс: `/api/v1`. Все идентификаторы сущностей и пользователей — UUID, если явно не указано иное.
- Внешние HTTP-запросы: `Authorization: Bearer <JWT>`; пользователь определяется сервером из токена, а не по `authorId`/`teacherId` в запросе.
- Владельцу-преподавателю доступны только его курсы и библиотека. **Администратору** доступны любые курсы и учебные материалы. **Ученику** — активные курсы, в которых он сейчас состоит, их расписание, чат и разрешённые учебные файлы.
- `Identity Service` — источник истины о пользователях/ролях/блокировке. UUID из Identity не являются физическими SQL FK в БД Education. Заблокированный пользователь не может пользоваться платформой; при зачислении дополнительно проверяем, что ученик не заблокирован.
- **Для `POST /courses`, `POST /lessons`, `POST /lesson-series` и аналогичных операций создания не требуем `Idempotency-Key`** в MVP. Повтор после потери ответа может создать дубликат; клиенту нужно запрашивать актуальное состояние перед повтором. Отправка чата отличается: у неё обязательный `clientMessageId` и серверная дедупликация.
- Ресурсы в состоянии `DELETING` недоступны пользователям (`404` при чтении, `409` при конфликтующих изменениях, в зависимости от маршрута). Удаление учебного материала, используемого Assignment, запрещено всем, включая администратора.
- Для вложений чата **1 МБ = 1 000 000 байт**: максимум **20 000 000 байт на файл**, **50 000 000 байт суммарно на сообщение**. Это не MiB. Длина текста — до 4000 символов, не более 5 вложений, временные вложения — 24 часа.
- Имя автора сообщения сохраняется как **снимок `author_display_name` на момент отправки**, а не запрашивается заново из Identity для каждой страницы истории.
- **Администратор может отправлять сообщения в режиме `ANNOUNCEMENTS`**; ограничение на отправку в этом режиме действует только для учеников.
- Все абсолютные даты/время в JSON: ISO 8601 со смещением (`2026-12-01T17:00:00+03:00`) или UTC (`2026-12-01T14:00:00Z`). В ответах нормализуем в UTC. Календарь и локальные правила повторения — **Europe/Moscow**.
- Общая курсорная пагинация списков: `limit` (по умолчанию 30, максимум 100), `cursor` (непрозрачный). Ответ: `items`, `nextCursor`, `hasMore`. **Исключение:** контракт чата уже использует `limit` + `before` и поле `messages`.
- Ошибки оформляем как `application/problem+json` с дополнительными полями `code` и при необходимости `details`/`conflicts`:

```json
{
  "type": "about:blank",
  "title": "Conflict",
  "status": 409,
  "code": "RESOURCE_IN_USE",
  "detail": "Нельзя удалить файл, который используется в задании"
}
```

**HTTP статусы:** `200` чтение/изменение; `201` создание; `202` принято в асинхронное удаление; `204` успешно без тела; `400` неверный формат/параметры; `401` нет аутентификации; `403` недостаточно прав; `404` ресурс отсутствует или недоступен; `409` конфликт бизнес-условий/необходимость подтверждения; `413` слишком большой upload; `415` запрещённый тип файла; `422` семантически неверные данные; `503` критичная зависимость (например, Identity) недоступна.

### 1.1. Минимальный каталог маршрутов

| Метод | Маршрут | Назначение |
|---|---|---|
| `GET` | `/courses?scope=OWNED\|ENROLLED\|ALL` | Список своих / зачисленных / всех курсов |
| `POST` | `/courses` | Создание курса |
| `GET` | `/courses/{courseId}` | Детали курса |
| `PATCH` | `/courses/{courseId}` | Изменение названия |
| `DELETE` | `/courses/{courseId}` | Асинхронное удаление курса |
| `GET` | `/courses/{courseId}/students` | Список учеников |
| `POST` | `/courses/{courseId}/students` | Зачисление |
| `DELETE` | `/courses/{courseId}/students/{studentId}` | Исключение |
| `PATCH` | `/courses/{courseId}/students/{studentId}/chat-permissions` | Запрет/разрешение писать |
| `GET` | `/courses/{courseId}/schedule?month=YYYY-MM` | Календарь курса |
| `GET` | `/me/schedule?month=YYYY-MM` | Общий календарь текущего пользователя |
| `POST` | `/courses/{courseId}/lessons` | Разовый урок |
| `PATCH` | `/courses/{courseId}/lessons/{lessonId}` | Перенос разового урока |
| `POST` | `/courses/{courseId}/lessons/{lessonId}/cancel` | Отмена разового урока |
| `POST` | `/courses/{courseId}/lesson-series` | Правило регулярных занятий |
| `GET` | `/courses/{courseId}/lesson-series` | Правила курса |
| `POST` | `/courses/{courseId}/lesson-series/{seriesId}/stop` | Остановка серии с даты |
| `POST` | `/courses/{courseId}/lesson-series/{seriesId}/occurrences/{originalDate}/reschedule` | Перенос одного повторения |
| `POST` | `/courses/{courseId}/lesson-series/{seriesId}/occurrences/{originalDate}/cancel` | Отмена одного повторения |
| `GET` | `/materials?parentId=...` | Содержимое папки библиотеки |
| `POST` | `/materials/folders` | Создать папку |
| `PATCH` | `/materials/folders/{folderId}` | Переименовать папку |
| `DELETE` | `/materials/folders/{folderId}` | Асинхронно удалить дерево |
| `POST` | `/materials/files` | Загрузить файл в библиотеку |
| `PATCH` | `/materials/files/{fileId}` | Переименовать / переместить файл |
| `DELETE` | `/materials/files/{fileId}` | Асинхронно удалить файл |
| `GET` | `/materials/files/{fileId}/content` | Содержимое файла владельцу/админу |
| `GET` | `/materials/folders/{folderId}/course-access` | Доступ курсов к папке |
| `PUT` / `DELETE` | `/materials/folders/{folderId}/course-access/{courseId}` | Дать/отозвать доступ к папке |
| `GET` | `/materials/files/{fileId}/course-access` | Индивидуальные правила файла |
| `PUT` / `DELETE` | `/materials/files/{fileId}/course-access/{courseId}` | Установить/убрать override файла |
| `GET` | `/courses/{courseId}/materials` | Доступные файлы курса |
| `GET` | `/courses/{courseId}/materials/files/{fileId}/content` | Скачать доступный файл курса |
| `GET` | `/courses/{courseId}/chat/settings` | Текущий режим чата |
| `PATCH` | `/courses/{courseId}/chat/settings` | NORMAL / ANNOUNCEMENTS |
| `GET` | `/courses/{courseId}/chat/messages` | История с `before`-курcором |
| `POST` | `/courses/{courseId}/chat/attachments` | Временная загрузка файла чата |
| `GET` | `/courses/{courseId}/chat/messages/{messageId}/attachments/{attachmentId}/content` | Содержимое вложения |
| `DELETE` | `/courses/{courseId}/chat/messages/{messageId}` | Удалить сообщение, при необходимости асинхронно |
| `POST` | `/internal/v1/materials/{fileId}/references` | Зарегистрировать ссылку Assignment |
| `DELETE` | `/internal/v1/materials/{fileId}/references/{referenceId}` | Освободить ссылку Assignment |

## 2. Курсы

### 2.1. `POST /api/v1/courses`

**Кто:** `TEACHER` (владелец определяется из JWT), `ADMIN` (может указать преподавателя). Название обязательно, не обязано быть уникальным. У курса нет описания и отдельного предмета. Интеграции Телемоста пока нет; `meetingUrl` по умолчанию `null`.

```json
{ "title": "Математика 9 класс" }
```

`ADMIN` при создании курса за преподавателя указывает `teacherId`; учитель этого делать не может. Успех `201 Created` с `Location: /api/v1/courses/{id}`:

```json
{
  "id": "86b6ceef-50b3-4ab0-8997-1304129ebc61",
  "teacherId": "00c0e7d8-e972-4f28-a79d-24c69f6509b4",
  "title": "Математика 9 класс",
  "meetingUrl": null,
  "chatMode": "NORMAL",
  "createdAt": "2026-11-20T12:40:00Z"
}
```

### 2.2. `GET /api/v1/courses?scope=OWNED|ENROLLED|ALL&limit=30&cursor=...`

- `OWNED`: курсы, которыми владеет преподаватель (только преподаватель или админ с явным фильтром `teacherId`).
- `ENROLLED`: курсы текущего ученика; нет доступа к курсам других учеников без административной привилегии.
- `ALL`: все курсы, только администратору; можно указать `teacherId` как фильтр.
- `scope` делаем **обязательным**, поскольку у пользователя могут быть сразу несколько ролей Identity.
- Только курсы `ACTIVE`, сортировка `(createdAt DESC, id DESC)`. Ответ — `items` из `CourseDto`, `nextCursor`, `hasMore`.

`GET /courses/{courseId}` возвращает `CourseDto` при наличии права: владелец, администратор или текущий ученик. `404`, если курс удаляется, удалён или не должен быть виден.

### 2.3. `PATCH /api/v1/courses/{courseId}`

**Кто:** владелец/админ. В MVP разрешено изменение только названия:

```json
{ "title": "Подготовка к ОГЭ — математика" }
```

**Успех:** `200` и обновлённый `CourseDto`. `meetingUrl` не редактируем через публичный контракт до интеграции с Телемостом.

### 2.4. `DELETE /api/v1/courses/{courseId}`

**Кто:** владелец/админ. Включено в текущую реализацию. В транзакции переводим курс в `DELETING` и регистрируем cleanup-задачу; после коммита ответ:

```http
HTTP/1.1 202 Accepted
Content-Type: application/json
```

```json
{
  "resourceId": "86b6ceef-50b3-4ab0-8997-1304129ebc61",
  "deletionState": "DELETING",
  "cleanupJobId": "52e50789-dbf7-4861-832e-63e88c10a065"
}
```

Повторное удаление при `DELETING` возвращает тот же незавершённый `cleanupJobId`; при уже физически удалённом курсе — `404` (нет дополнительных побочных эффектов). Worker удаляет чат и его файлы из MinIO, расписание, актуальные зачисления, доступ курса к материалам и сам курс. **Учебная библиотека преподавателя не удаляется.** Семантика связанных домашних заданий Assignment пока **открыта**.

### 2.5. Состав курса

`GET /api/v1/courses/{courseId}/students?limit=30&cursor=...` — только владелец/админ. Возвращает текущие членства, например:

```json
{
  "items": [
    {
      "studentId": "dcae20c0-7e42-430a-a1bd-6a5cc8c2bf09",
      "displayName": "Иван Иванов",
      "blocked": false,
      "canSendMessages": true,
      "enrolledAt": "2026-11-20T12:43:00Z"
    }
  ],
  "nextCursor": null,
  "hasMore": false
}
```

Имя и актуальный `blocked` — данные Identity Service (не дублируются в Education как источник истины).

**Зачисление:** `POST /api/v1/courses/{courseId}/students`, только владелец/админ:

```json
{
  "studentId": "dcae20c0-7e42-430a-a1bd-6a5cc8c2bf09",
  "confirmConflicts": false
}
```

Проверяем роль STUDENT, существование, отсутствие блокировки, отсутствие текущего членства. Дубликат: `409 ALREADY_ENROLLED`. Предупреждения о конфликте расписания выдаются через `409 SCHEDULE_CONFLICTS` (формат ниже). После подтверждения — `201 Created`, `Location: /api/v1/courses/{courseId}/students/{studentId}` и объект участника. История зачисления не сохраняется.

**Исключение:** `DELETE /api/v1/courses/{courseId}/students/{studentId}` — `204 No Content`, удаляем строку членства. При повторном зачислении индивидуальный запрет чата сброшен. Сообщения автора в истории чата сохраняются.

**Локальный запрет писать:** `PATCH /api/v1/courses/{courseId}/students/{studentId}/chat-permissions` (только владелец/админ):

```json
{ "canSendMessages": false }
```

`200` и `{ "studentId": "...", "canSendMessages": false }`. Чтение чата и вложений не ограничивается; в режиме объявлений ученики всё равно не пишут независимо от флага.

## 3. Расписание

### 3.1. Представление занятий

`GET /api/v1/courses/{courseId}/schedule?month=2026-12` — владелец, админ, текущий ученик. `GET /api/v1/me/schedule?month=2026-12` — совокупное расписание текущего пользователя (по его собственным курсам как преподавателя и/или по зачислениям как ученика). Админ может смотреть расписание конкретного курса; глобального календаря всех курсов для админа в MVP нет.

`month` обязателен, формат `YYYY-MM`; календарь считается по Europe/Moscow, содержит виртуальные вхождения правил с учётом отмен и переносов, а также разовые занятия. **Для остановленной серии отсеиваются вхождения с `originalDate >= stopFromDate`, но более ранние перенесённые вхождения сохраняются и попадают в календарь по фактическому `startsAt`, даже если оно после даты остановки.** Сортировка по действительному `startsAt`:

```json
{
  "month": "2026-12",
  "timeZone": "Europe/Moscow",
  "lessons": [
    {
      "courseId": "86b6ceef-50b3-4ab0-8997-1304129ebc61",
      "kind": "RECURRING",
      "singleLessonId": null,
      "seriesId": "e772ebee-51e4-46f8-9f46-debb6ab6c9f3",
      "originalDate": "2026-12-07",
      "startsAt": "2026-12-07T14:00:00Z",
      "endsAt": "2026-12-07T15:30:00Z",
      "rescheduled": false
    }
  ]
}
```

`kind`: `SINGLE` либо `RECURRING`. Для разового занятия `singleLessonId != null`, `seriesId` и `originalDate` равны `null`. Для регулярного `seriesId + originalDate` — **стабильный идентификатор вхождения**, даже если дата фактического начала перенесена. Отменённые занятия **не выводим** в `lessons`, поскольку отдельных статусов занятий нет. Это решение касается текущего календаря, не запрета хранить исключения `CANCELLED`.

### 3.2. Конфликты как предупреждение

Во всех командах, создающих/переносящих занятие, и при зачислении проверяем конфликты. Для новой серии достаточно проверить **30 дней**. Первый запрос с `confirmConflicts=false` при конфликтах не изменяет БД и возвращает:

```http
HTTP/1.1 409 Conflict
Content-Type: application/problem+json
```

```json
{
  "type": "about:blank",
  "title": "Schedule conflicts require confirmation",
  "status": 409,
  "code": "SCHEDULE_CONFLICTS",
  "detail": "Обнаружены пересечения. Можно подтвердить действие.",
  "conflicts": [
    {
      "subjectType": "TEACHER",
      "subjectId": "00c0e7d8-e972-4f28-a79d-24c69f6509b4",
      "startsAt": "2026-12-07T14:30:00Z",
      "endsAt": "2026-12-07T15:30:00Z"
    }
  ]
}
```

`subjectType`: `TEACHER` либо `STUDENT`. Не отдаём ученикам/преподавателям детали чужих курсов или названия приватных занятий. При `confirmConflicts=true` backend **повторно** выполняет проверку, после чего сохраняет изменение, даже если конфликты остались. Это не атомарная глобальная гарантия отсутствия конфликтов, поскольку пересечения разрешены.

### 3.3. Разовые занятия

`POST /api/v1/courses/{courseId}/lessons` — владелец/админ:

```json
{
  "startsAt": "2026-12-01T14:00:00Z",
  "endsAt": "2026-12-01T15:30:00Z",
  "confirmConflicts": false
}
```

Длительность строго **5–300 минут**. Успех `201 Created` и объект `SINGLE` из календаря с новым `singleLessonId`; отдельный `GET /lessons/{lessonId}` в MVP не обязателен (урок можно получить в календаре).

`PATCH /api/v1/courses/{courseId}/lessons/{lessonId}` — перенос **только одного** разового занятия; тело `{ "startsAt": "...", "endsAt": "...", "reason": null, "confirmConflicts": false }`. Причина переноса необязательна и попадает в Kafka-событие. При успехе `200` с новым временем.

`POST /api/v1/courses/{courseId}/lessons/{lessonId}/cancel` — отменить **один** разовый урок; тело `{ "reason": null }`, успех `204`. После отмены строка `single_lesson` физически удаляется, событие `LessonCancelled` сохраняется в outbox; восстановления нет.

### 3.4. Регулярные занятия

`POST /api/v1/courses/{courseId}/lesson-series`:

```json
{
  "startDate": "2026-12-01",
  "weekdays": [1, 4],
  "startTime": "17:00",
  "durationMinutes": 90,
  "confirmConflicts": false
}
```

`startDate` — локальная дата Москвы; `weekdays` — непустые уникальные ISO-дни 1..7; `durationMinutes` от 5 до 300. Серия бессрочна (`stopFromDate = null`). Для каждого отличающегося времени начала создаётся отдельная серия. Успех `201`:

```json
{
  "id": "e772ebee-51e4-46f8-9f46-debb6ab6c9f3",
  "courseId": "86b6ceef-50b3-4ab0-8997-1304129ebc61",
  "startDate": "2026-12-01",
  "weekdays": [1, 4],
  "startTime": "17:00",
  "durationMinutes": 90,
  "stopFromDate": null,
  "timeZone": "Europe/Moscow"
}
```

`GET /api/v1/courses/{courseId}/lesson-series` — список правил, включая остановленные (для преподавателя/админа; ученику достаточно календаря).

`POST /api/v1/courses/{courseId}/lesson-series/{seriesId}/stop`:

```json
{ "stopFromDate": "2026-12-20" }
```

`200` и обновлённый объект серии; начиная с указанной московской даты **исходные вхождения** с `originalDate >= stopFromDate` больше не формируются. Публикуется `LessonSeriesStopped` через outbox. **Утверждённое правило:** уже перенесённое вхождение с `originalDate < stopFromDate` **сохраняется**, даже если его новое фактическое начало позже даты остановки. Пример: исходное 15 декабря, перенос на 25 декабря, остановка с 20 декабря — 25 декабря остаётся в расписании.

**Перенос конкретного повторения:**

```http
POST /api/v1/courses/{courseId}/lesson-series/{seriesId}/occurrences/2026-12-07/reschedule
Content-Type: application/json
```

```json
{
  "startsAt": "2026-12-08T15:00:00Z",
  "endsAt": "2026-12-08T16:30:00Z",
  "reason": "Праздник",
  "confirmConflicts": false
}
```

Успех `200` и объект вхождения с тем же `seriesId + originalDate`, `rescheduled: true`. Создаём/обновляем `lesson_exception.kind = RESCHEDULED`, публикуем `LessonRescheduled` в Kafka через outbox. Повторный перенос **того же** вхождения меняет существующее исключение, а не создаёт ещё один урок.

**Отмена конкретного повторения:** `POST /api/v1/courses/{courseId}/lesson-series/{seriesId}/occurrences/{originalDate}/cancel`, тело `{ "reason": null }`; успех `204`, создаём `lesson_exception.kind = CANCELLED`, публикуем `LessonCancelled`. Восстановить отменённое занятие нельзя; попытка перенести уже отменённое — `409 LESSON_CANCELLED`.

**Ограничения:** при остановленной серии нельзя создавать/изменять вхождения с `originalDate >= stopFromDate`. Массового редактирования всех занятий/будущих вхождений нет.

## 4. Учебная библиотека преподавателя

### 4.1. Чтение структуры

`GET /api/v1/materials?parentId=root` — список дочерних папок и файлов в корне библиотеки текущего преподавателя. `parentId={folderId}` — содержимое указанной папки. Администратор может передать `teacherId` для выбора библиотеки. Для обычного преподавателя `teacherId` из запроса не принимается: используем идентификатор токена.

```json
{
  "parentId": null,
  "folders": [
    {
      "id": "231c8e23-762d-4a69-8ed0-e4ec530700ee",
      "name": "Алгебра",
      "parentId": null,
      "createdAt": "2026-11-20T13:00:00Z"
    }
  ],
  "files": [
    {
      "id": "e10fc02b-d062-46c8-b9cb-c24e4b9f26f4",
      "name": "Теория.pdf",
      "folderId": null,
      "contentType": "application/pdf",
      "sizeBytes": 562800,
      "createdAt": "2026-11-20T13:01:00Z"
    }
  ]
}
```

В MVP возвращаем один уровень дерева. Это не публичный просмотр для ученика. При большом количестве объектов позднее можно добавить курсорную пагинацию, не меняя правил доступа.

### 4.2. Создание и изменение папок

- `POST /api/v1/materials/folders` (владелец/админ): `{ "name": "Алгебра", "parentId": null }`, успех `201` и `FolderDto`. Для админа при создании от лица преподавателя дополнительно требуется `teacherId`.
- `PATCH /api/v1/materials/folders/{folderId}`: `{ "name": "Алгебра — теория" }`, успех `200`. **Перемещение папок** не входит в минимальный контракт: утверждено перемещение файлов; перенос дерева папок можно добавить отдельно.
- `DELETE /api/v1/materials/folders/{folderId}`: в одной транзакции проверяем отсутствие использования **любого** дочернего файла в Assignment; если есть — `409 MATERIAL_IN_USE`, **никакая** часть дерева не начинает удаляться. Иначе помечаем всё дерево `DELETING` и создаём `cleanup_job`: `202` с `{resourceId, deletionState, cleanupJobId}`. После начала удаления в дерево нельзя перемещать или добавлять файлы.

### 4.3. Загрузка и операции с файлами

`POST /api/v1/materials/files` — `multipart/form-data`, поля `file` (обязательно), `folderId` (необязательно, `null` = корень), `teacherId` (только админ). Файл потоково передаётся backend в приватный MinIO, потом публикуется в `material_file` после повторной проверки доступа и активности папки.

**Успех:** `201 Created`, `MaterialFileDto` как в примере раздела 4.1. **Важно:** лимит 20 МБ и MIME whitelist согласован **для вложений чата**; автоматически распространять его на библиотеку без нового решения нельзя.

`PATCH /api/v1/materials/files/{fileId}` — изменение имени и/или перемещение, например:

```json
{ "name": "Теория-1.pdf", "folderId": "231c8e23-762d-4a69-8ed0-e4ec530700ee" }
```

`folderId: null` означает перемещение в корень, отсутствие поля — не менять папку. Перемещать можно только в папку **того же владельца**. После перемещения переоцениваются наследуемые права, явные правила самого файла продолжают действовать. Успех `200`.

`DELETE /api/v1/materials/files/{fileId}` — сначала атомарно проверяем ссылки Assignment. При существующей ссылке возвращаем `409 MATERIAL_IN_USE`, **не переводим** объект в `DELETING`. Иначе `202` и `cleanupJobId`, worker удаляет объект MinIO, затем запись БД.

`GET /api/v1/materials/files/{fileId}/content` — скачать/просмотреть файл, только владелец/администратор; backend проверяет права, `ACTIVE`, затем потоково отдаёт содержимое из MinIO.

### 4.4. ACL: папки и файлы

**Права папки** (владелец/админ):

- `GET /api/v1/materials/folders/{folderId}/course-access` — список ID курсов, которым дан прямой грант на эту папку (не все эффективные права потомков).
- `PUT /api/v1/materials/folders/{folderId}/course-access/{courseId}` — выдать грант чтения; `204 No Content`, повторный PUT идемпотентен.
- `DELETE /api/v1/materials/folders/{folderId}/course-access/{courseId}` — отозвать прямой грант; `204`. При наличии гранта предка доступ к потомкам может сохраниться.

**Права отдельного файла** (владелец/админ):

- `GET /api/v1/materials/files/{fileId}/course-access` — список явных правил `{courseId, allowRead}`.
- `PUT /api/v1/materials/files/{fileId}/course-access/{courseId}` с телом `{ "allowRead": false }` или `{ "allowRead": true }` — явный override для одного курса; `204`.
- `DELETE /api/v1/materials/files/{fileId}/course-access/{courseId}` — удалить override; снова действуют права папки/предков; `204`.

Преподаватель может давать доступ к материалам своим курсам; администратор управляет любыми. Ученик может читать файл, если хотя бы один его текущий курс предоставляет доступ, и для выбранного курса нет явного запрета у файла. Вложенные папки наследуют грант родительской папки, в том числе для файлов, появившихся позже.

### 4.5. Файлы, доступные курсу

`GET /api/v1/courses/{courseId}/materials?limit=30&cursor=...` — плоский список файлов, к которым есть доступ **через этот курс**; доступен текущему ученику курса, владельцу и админу. Файлы с явным запретом для курса в ответ не попадают. Возвращает стандартный `{items,nextCursor,hasMore}`, каждый элемент — `MaterialFileDto` и `downloadUrl` вида `/api/v1/courses/{courseId}/materials/files/{fileId}/content`.

`GET /api/v1/courses/{courseId}/materials/files/{fileId}/content` — потоковое скачивание через backend после проверки **текущего** членства, доступа по ACL, активности курса и файла. Отсутствие доступа через данный курс нельзя обойти знанием UUID; если файл открыт через другой курс, использовать путь **другого** курса.

## 5. Групповой чат (REST-часть)

### 5.1. Режим

- `GET /api/v1/courses/{courseId}/chat/settings` — доступен любому читателю чата, `200`:

```json
{ "mode": "NORMAL" }
```

- `PATCH /api/v1/courses/{courseId}/chat/settings` — владелец/админ, тело `{ "mode": "ANNOUNCEMENTS" }` либо `{ "mode": "NORMAL" }`, успех `200`. При `ANNOUNCEMENTS` ученики только читают, **владелец и администратор могут писать** — это утверждённое правило.

### 5.2. Получение истории

`GET /api/v1/courses/{courseId}/chat/messages?limit=50&before=...` — владелец, админ, текущий ученик. Подробный **ранее описанный** контракт — `chat_history_api_contract.md`. Важные поля и правила:

```json
{
  "messages": [
    {
      "id": "8227f58b-c47a-40b9-a9ea-18eac57bb0ea",
      "author": {
        "id": "dcae20c0-7e42-430a-a1bd-6a5cc8c2bf09",
        "displayName": "Иван Иванов",
        "role": "STUDENT"
      },
      "text": "Вот решение",
      "createdAt": "2026-11-20T13:10:00Z",
      "attachments": []
    }
  ],
  "nextCursor": null,
  "hasMore": false
}
```

В истории только `ACTIVE`, без заглушек; от новых к старым, сортировка `(created_at DESC, id DESC)`, `before` — непрозрачный курсор. При reconnect клиент заново подписывается на WebSocket и **заменяет** старую локальную историю актуальной из REST (не делает только append новых сообщений).

### 5.3. Загрузка вложения до отправки сообщения

`POST /api/v1/courses/{courseId}/chat/attachments` — `multipart/form-data` с одним полем `file`. **Кто:** владелец/админ/текущий ученик с правом писать при `NORMAL` (в `ANNOUNCEMENTS` ученик не может загрузить для отправки).

- До **20 000 000 байт** на файл; до 5 вложений на итоговое сообщение, суммарно до **50 000 000 байт** (последние два ограничения окончательно проверяются при STOMP SEND).
- Разрешённые типы: JPG/JPEG, PNG, WEBP, PDF, DOCX, XLSX, TXT; проверяем и расширение, и MIME, и фактическую структуру где возможно.
- Backend создаёт временное вложение `UPLOADING`, потоково записывает объект MinIO, после успешного завершения и повторной проверки права переводит его в `READY`.

**Успех `201 Created`:**

```json
{
  "attachmentId": "e15ffac6-7388-49c5-bb35-e468f3d8395e",
  "fileName": "photo.png",
  "contentType": "image/png",
  "sizeBytes": 2097152,
  "state": "READY",
  "expiresAt": "2026-11-21T13:11:00Z"
}
```

Временные вложения не видны другим пользователям, пока не привязаны к сохранённому сообщению. TTL **24 часа** с момента создания. Привязка выполняется через STOMP SEND с `attachmentIds`; один файл нельзя прикреплять к двум сообщениям. Перед прикреплением проверяем авторство, курс, состояние `READY`, число и размер. Бинарные файлы не отправляем через WebSocket.

**Для минимальной версии:** отдельный DELETE для ещё не прикреплённого вложения не вводим. Неиспользованный файл автоматически удаляется по TTL 24 часа. Прикреплённое вложение удаляется только вместе с сообщением. Если позже понадобится кнопка «отменить загрузку», введём отдельный маршрут и тип cleanup-задачи `CHAT_ATTACHMENT`.

### 5.4. Получение вложения из истории

`GET /api/v1/courses/{courseId}/chat/messages/{messageId}/attachments/{attachmentId}/content` — владелец, админ, текущий ученик; проверяем, что курс и сообщение `ACTIVE`, вложение `ATTACHED`, `messageId/courseId` совпадают. Возвращаем поток через backend из приватного MinIO, с `Content-Type`, безопасным `Content-Disposition`, `X-Content-Type-Options: nosniff` и `Cache-Control: private, no-store`.

### 5.5. Удаление сообщения

`DELETE /api/v1/courses/{courseId}/chat/messages/{messageId}` — преподаватель удаляет **свои и сообщения учеников своего курса**, админ — любые; ученик не удаляет даже свои.

- **Есть вложения:** после коммита переводим сообщение в `DELETING`, закрываем доступ, создаём cleanup-задачу; `202 Accepted` с `{resourceId, deletionState, cleanupJobId}`. Worker удаляет объекты MinIO и физически удаляет записи.
- **Только текст:** можем физически удалить сообщение в одной транзакции, ответ `204 No Content`.
- `MESSAGE_DELETED` публикуется в WebSocket после коммита скрытия или удаления. Технический `chat_send_receipt` остаётся, чтобы тот же `clientMessageId` нельзя было повторно использовать для создания уже удалённого сообщения.

Отдельный REST `POST /chat/messages` **не вводим**. Новый текст отправляется через `/app/courses/{courseId}/chat.send`; подробности — в `chat_websocket_contract.md`.

## 6. Внутренний API для Assignment Service

**Только сервис-сервис авторизация**, пользовательский JWT без полномочий Assignment не подходит. Префикс **предлагается** `/internal/v1` и должен быть закрыт от браузера и внешних клиентов.

### 6.1. Регистрация использования файла

`POST /internal/v1/materials/{fileId}/references`

```json
{
  "referenceId": "da7f40f8-79de-4a72-a6b9-fdd6b9f16de8",
  "consumerService": "ASSIGNMENT",
  "consumerEntityId": "b6ec5601-5c41-4b58-8d03-c706f46db4ee"
}
```

Education в одной транзакции блокирует `material_file`, убеждается в `ACTIVE` и регистрирует связь идемпотентно (по `referenceId` / уникальности комбинации файла и потребителя). `201 Created` при создании; `200 OK` при повторе идентичной команды. Повтор с другим содержимым и тем же `referenceId` — `409 REFERENCE_ID_CONFLICT`. Файл в `DELETING` — `409 MATERIAL_DELETING`.

```json
{
  "referenceId": "da7f40f8-79de-4a72-a6b9-fdd6b9f16de8",
  "fileId": "e10fc02b-d062-46c8-b9cb-c24e4b9f26f4",
  "registered": true
}
```

**Важное ограничение:** регистрация ссылки сама по себе не даёт конечному пользователю права на скачивание; права для заданий определятся при проектировании Assignment Service.

### 6.2. Освобождение ссылки

`DELETE /internal/v1/materials/{fileId}/references/{referenceId}` — после того, как Assignment зафиксировал у себя отвязку материала от задания; `204 No Content`, повторный DELETE идемпотентен. При недоступности Education Assignment повторяет освобождение через свой outbox/worker. Пока ссылка зарегистрирована, удаление файла и содержащей его папки запрещено.

**Межсервисная гонка:** начало удаления файла и регистрация использования сериализуются по одной строке `material_file`; для удаления папки блокируем удаляемую структуру и проверяем все дочерние ссылки. Простого вопроса «используется ли файл?» перед удалением недостаточно.

## 7. Асинхронные удаления и Kafka

- `DELETE` курса, папки, учебного файла и сообщения с вложениями создаёт `cleanup_job` **в той же транзакции**, где ресурс становится `DELETING`. В MVP **не вводим `GET /cleanup-jobs/{id}`**: клиент получает `202 Accepted` и `cleanupJobId`, ресурс сразу скрывается. Отслеживание worker — внутри сервиса (логи/метрики).
- Клиент получает `202`, ресурс сразу недоступен. Worker удаляет объекты MinIO **до** удаления соответствующих метаданных PostgreSQL, при сбоях использует повторные попытки; `cleanupJobId` остаётся стабильным во время повтора DELETE.
- Для **удаления курса** cleanup включает чат/вложения, расписание, членства и ACL курса; **не** включает библиотечные файлы преподавателя или `material_reference`.
- Подробности захвата через `FOR UPDATE SKIP LOCKED`, lease и защиты от гонок загрузки — в требованиях и документе даталогической модели.
- `LessonRescheduled`, `LessonCancelled`, `LessonSeriesStopped` должны попадать в Kafka через `outbox_event`. **Ниже лишь предварительный состав payload, не согласованная схема Kafka**:

```json
{
  "eventId": "6f1fe21c-080f-4915-bdfc-9e4af41682e8",
  "type": "LessonRescheduled",
  "courseId": "86b6ceef-50b3-4ab0-8997-1304129ebc61",
  "lesson": {
    "kind": "RECURRING",
    "seriesId": "e772ebee-51e4-46f8-9f46-debb6ab6c9f3",
    "originalDate": "2026-12-07"
  },
  "oldStartsAt": "2026-12-07T14:00:00Z",
  "oldEndsAt": "2026-12-07T15:30:00Z",
  "newStartsAt": "2026-12-08T15:00:00Z",
  "newEndsAt": "2026-12-08T16:30:00Z",
  "reason": "Праздник"
}
```

Отдельный Kafka-контракт можно оформить, когда будем реализовывать сервис уведомлений. Доставка outbox — at-least-once; получатели должны учитывать `eventId` для дедупликации.

## 8. Ошибки и ограничения: сводка

| Код приложения | HTTP | Когда |
|---|---|---|
| `ALREADY_ENROLLED` | 409 | Ученик уже состоит в курсе |
| `STUDENT_BLOCKED` | 409 | Identity сообщает, что ученик заблокирован |
| `SCHEDULE_CONFLICTS` | 409 | Нужна явная отправка с `confirmConflicts=true` |
| `LESSON_CANCELLED` | 409 | Попытка перенести отменённое вхождение |
| `MATERIAL_IN_USE` | 409 | Файл/папка используется Assignment |
| `MATERIAL_DELETING` | 409 | Файл уже удаляется |
| `COURSE_DELETING` | 409 | Попытка изменять удаляемый курс |
| `CHAT_SEND_FORBIDDEN` | 403 | Режим объявлений или персональный запрет |
| `INVALID_MESSAGE` | 422 | Пустой текст и нет файлов, >4000 символов и т. п. |
| `INVALID_ATTACHMENT` | 422 | Файл не READY, не принадлежит автору/курсу; >5 файлов или >50 МБ |
| `CLIENT_MESSAGE_ID_CONFLICT` | WS-ответ | Тот же ID с другим payload (см. WebSocket-контракт) |
| `FILE_TOO_LARGE` | 413 | Превышение лимита upload |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | Запрещённый формат вложения |

**Для вложений чата согласовано:** 1 МБ = 1 000 000 байт; лимиты **20 000 000 байт** на вложение и **50 000 000 байт** суммарно на сообщение. Форматы: JPG/JPEG (`image/jpeg`), PNG (`image/png`), WEBP (`image/webp`), PDF (`application/pdf`), DOCX (`application/vnd.openxmlformats-officedocument.wordprocessingml.document`), XLSX (`application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`), TXT (`text/plain`). Проверка не ограничивается доверением MIME из заголовка. Максимум 5 вложений и 50 МБ суммарно, до 20 МБ каждое, текст до 4000 символов, временные вложения 24 часа. Отдельного редактирования сообщений/Reply нет.

## 9. Итог согласования API-контракта и отложенные темы

**Утверждено для MVP (20.11.2026):**

1. **Дата остановки повторений:** сравниваем `originalDate` с `stopFromDate`; если ранее перенесённое вхождение имеет исходную дату до остановки, оно остаётся на новом времени, даже когда новая дата после остановки.
2. **Маршруты, DTO, статусы:** данный документ принят как **базовый REST API-контракт**. Конфликты расписания показываем через `409 SCHEDULE_CONFLICTS`, подтверждение — `confirmConflicts=true`; курсорные списки, `scope` и маршруты отмены применяем по описанию выше.
3. **Удаление курса:** в Education реализуем сейчас. **Только** судьбу домашних заданий, решений, оценок и межсервисную координацию обсудим при проектировании Assignment Service.
4. **Интерфейс cleanup:** отдельного публичного `GET /cleanup-jobs/{id}` в MVP нет, несмотря на наличие `cleanupJobId` в ответе `202`.
5. **Создающие POST:** дополнительный обязательный `Idempotency-Key` не вводим. При сетевом повторе создания курса/занятия дубли возможны; frontend должен сверять состояние перед повтором. Для новых сообщений применяется отдельный `clientMessageId`.
6. **Чат:** REST для истории и файлов, WebSocket/STOMP для отправки; согласованный минимальный STOMP-контракт см. `chat_websocket_contract.md`. Авторское имя — снимок; администратор пишет также в режиме `ANNOUNCEMENTS`.
7. **Вложения:** 1 МБ = 1 000 000 байт, 20 000 000 байт на файл, 50 000 000 суммарно, максимум 5 вложений и до 4000 символов; временные вложения — 24 часа.

**Отложено или зависит от реализации:** конкретные Kafka event payload для сервиса уведомлений, детали оркестрации удаления домашних заданий Assignment при удалении курса, параметры lease/backoff cleanup worker, автоматическая ссылка Телемоста. Эти пункты не меняют согласованный REST API MVP.

## 10. Рекомендуемый порядок реализации API

1. Курсы, зачисления, режим чата и права студента.
2. Разовые занятия, серии, календарь месяца, конфликт `409` + подтверждение.
3. Библиотека (структура, загрузка, ACL), выдача файлов через курс.
4. История чата, временные вложения REST; отправка/подтверждение по отдельному WebSocket/STOMP-контракту.
5. `cleanup_job` + worker, физическое удаление курсов/папок/файлов/сообщений и outbox для Kafka.
6. Регистрацию ссылок Assignment оставить внутренним контрактом для будущей интеграции, с уже утверждённым запретом удалять используемые файлы.
