# Assignment Service — внутренние REST-контракты

Редакция 27.11.2026. Публичный REST отдельно. Проверены проектные документы `endpoints(1).md`, `education_service_rest_api_contract(2).md`; наличие новых маршрутов в коде не подтверждено. Согласованные POST/DELETE references Education сохраняются; ниже минимальные расширения. Identity и Education — владельцы своих данных, SQL cross-service FK/чтение чужих БД запрещены.

## 1. Авторизация, время и ошибки

`/internal/v1/**` закрыт от браузера и внешнего gateway/Nginx. Пользовательский JWT не даёт доступ к этим маршрутам. HTTPS либо защищённая внутренняя сеть с TLS в production; короткоживущие подписанные service JWT, issuer, audience конкретного сервиса, sub идентификатор вызывающего сервиса, tokenType=SERVICE, scopes, iat/exp, проверка подписи и ограниченного алгоритма, ключи/credentials отдельно от пользователя. Пример claims (не рабочий токен):

```json
{"iss":"tutor-platform","sub":"assignment-service","aud":"education-service","tokenType":"SERVICE","scope":["assignment:recipients:resolve","materials:references:write","materials:references:read"],"iat":1795780800,"exp":1795781100}
```

Предлагаемый TTL 5 min; точный механизм выпуска/ротации сверить с Identity: отдельная сервисная подпись или внутренний issuer с аутентификацией сервисов, без нового Keycloak. Не доверять произвольному X-User-Id. Если используется actorId в запросе, это утверждение аутентифицированного Assignment, сформированное из проверенного JWT, не passthrough клиентского поля. Education дополнительно проверяет право владельца/администратора. Пользовательская авторизация на публичном API — фактический стандарт существующего проекта, включая CSRF при cookie auth. Никаких credentials в git/логах.

Connect timeout 1 s, read timeout 3 s для обычных JSON; не более одного быстрого retry для безопасных идемпотентных запросов; мутации Saga повторяет durable worker с теми же ID. Отдельные bounded timeouts для файлов, потоковый transfer, корректное закрытие stream. Никаких длительных SQL locks во время обычных сетевых вызовов. Timeout/5xx/ошибки проверки ответа зависимости -> 503 `IDENTITY_UNAVAILABLE`/`EDUCATION_UNAVAILABLE`; не трактовать timeout как пустой список или false. Ошибки application/problem+json; 401 неверная service auth, 403 scope, 400 malformed, 404 отсутствует, 409 бизнес-конфликт. Circuit breaker не обязателен для MVP.

## 2. Identity API (новые либо адаптация существующих)

| Method | Path | Scope |
|---|---|---|
| GET | `/internal/v1/users/{userId}` | users:read |
| POST | `/internal/v1/users/batch` | users:read |

UserDto:
```json
{"id":"user-uuid","firstName":"Иван","surname":"Иванов","patronymic":null,"roles":["STUDENT"],"blocked":false,"profileCompleted":true}
```

GET 200, неизвестный 404. Batch `{userIds:["user-uuid-1","user-uuid-2"]}` максимум 500 distinct IDs (duplicate dedup); 200 `{users:[UserDto],missingUserIds:["user-uuid-2"]}`; все запрошенные ID представлены ровно в одной категории, лишних нет. Запрещено возвращать login/password/email/refresh/access token. Identity формирует profileCompleted по фактическим правилам. Нужен только displayName, роли и право доступа. Batch для списков и публикации, не 500 HTTP requests. Снимок актуален на момент ответа Identity.

На каждом защищённом пользовательском запросе Assignment проверяет JWT (issuer/expiry/signature/aud, роли) и синхронно запрашивает актуальную Identity запись. blocked -> 403 USER_BLOCKED, incomplete -> 403 PROFILE_INCOMPLETE, missing -> 401; при недоступности fail closed 503. Роли из Identity ограничивают JWT, не повышаем полномочия неподходящего токена. Redis-кэш блокировки не вводим. Для actor и target разные проверки: учитель может проверить старую работу заблокированного ученика, worker завершает её без Identity. Списки отображают current names из batch; missing исторический пользователь — id + displayName null (не терять историю). Ошибка всей зависимости -> 503, не тихая подмена. Блокировка после успешной проверки и до локального commit возможна: нет распределённой транзакции, следующий запрос проверяется заново. Не удерживать SQL locks при проверке Identity.

## 3. Education: снимок получателей

`POST /internal/v1/assignment-recipients/resolve`, scope assignment:recipients:resolve.

```json
{"teacherId":"teacher-uuid","courseIds":["course-uuid-1","course-uuid-2"],"studentIds":["student-uuid-1"]}
```

200:
```json
{"resolvedAt":"2026-11-27T12:00:00Z","courses":[{"id":"course-uuid-1","title":"Математика"},{"id":"course-uuid-2","title":"Подготовка"}],"recipients":[{"studentId":"student-uuid-1","courseIds":["course-uuid-1"],"selectedIndividually":true}],"invalidCourseIds":[],"ineligibleStudentIds":[]}
```

TeacherId — владелец шаблона, не непроверенное поле клиента. Education в согласованном read snapshot получает ACTIVE courses, проверяет owner, current membership, исключает DELETING. Выбранные отдельно students должны состоять хотя бы в одном активном курсе teacher, не обязательно из списка selected courses. Recipients — union без дубликатов; courseIds только явно выбранные курсы, через которые этот получатель вошёл в назначение; индивидуальный выбор без выбранных курсов -> []. Невалидные курсы/ученики отдельно, Assignment не публикует при их наличии. Состав возвращается полностью, не пагинировать; >20 courseIds, >500 studentIds или >500 unique recipients — 422, не усекать молча. Названия курса снимок, resolvedAt UTC.

Publication workflow: authenticate actor -> Education resolve -> Identity batch -> validate blocked/missing/roles/profile -> локальная транзакция: recheck template revision, task versions, конфликт повторного назначения, deadline, idempotency; сохранить snapshot, course links, submissions, reminders, outbox. Preview ничего не резервирует; publish всегда новый resolve. Гонка с исключением/удалением курса после snapshot допустима, нет распределённой атомарности. После назначения доступ не зависит от текущих курсов; новые зачисления не получают старые работы. Никаких каскадных команд по удалению course.

`GET /internal/v1/teachers/{teacherId}/students/{studentId}/membership`, scope assignment:recipients:resolve; 200 `{enrolled:true|false}` — хотя бы один ACTIVE owned course. Для аналитики/подбора проверяется при каждом запросе. Нет реплики членств, Inbox или Kafka-подписки на Education для MVP.

## 4. Education: регистрация и выдача материалов

### 4.1. POST (существующий маршрут, расширение DTO)

`POST /internal/v1/materials/{fileId}/references`, scope materials:references:write, только Assignment.
```json
{"referenceId":"reference-uuid","consumerService":"ASSIGNMENT","consumerEntityId":"task-version-uuid","teacherId":"owner-teacher-uuid","actorId":"actor-uuid"}
```

Education под общей блокировкой material_file/дерева проверяет ACTIVE, право owner материала = teacherId, actor = teacherId и TEACHER либо актуальный ADMIN с правом управления библиотекой владельца; не разрешать arbitrary чужой файл даже ADMIN при редактировании приватной задачи другого учителя (владельцем остаётся учитель). Права ACL курса не равны праву прикрепления. Для imported task без teacherId библиотечные материалы не используются (техническое ограничение). На repeat идентичного ранее зарегистрированного referenceId вернуть 200 даже при потерянном исходном ответе; убедиться, что не release tombstone. Для новой ссылки 201. `consumerEntityId` = неизменяемая версия задачи, unique(fileId,consumerService,consumerEntityId). Изменение payload при том же ID -> 409 REFERENCE_ID_CONFLICT, иной ID для уже связанной тройки -> 409 (не возвращать чужой ID как успех). Файл отсутствует 404, DELETING 409 MATERIAL_DELETING, не владеет 403, reference уже освобождён 409 REFERENCE_RELEASED.

```json
{"referenceId":"reference-uuid","fileId":"file-uuid","registered":true,"fileName":"theory.pdf","mimeType":"application/pdf","sizeBytes":100000}
```

Метаданные нужны для DTO, objectKey не выдаём. Регистрация и начало удаления файла/папки сериализуются на стороне Education; пока существует хотя бы одна ссылка, delete file/folder -> 409 MATERIAL_IN_USE даже ADMIN. Ссылка не даёт конечному пользователю право скачивания автоматически.

### 4.2. DELETE (существующий маршрут + защита от позднего POST)

`DELETE /internal/v1/materials/{fileId}/references/{referenceId}`, scope materials:references:write; 204, повтор 204 даже если ссылки или файла уже нет. Assignment вызывает только после запрета новых локальных использований/отмены версии.

**Делегированное техническое решение:** добавить в Education durable tombstone освобождённых referenceId (`reference_id PK`, `file_id`, `consumer_service`, `released_at`). DELETE в одной транзакции удаляет существующую ссылку и записывает tombstone, в том числе для неизвестного ID; чужой consumer или несовпадающий fileId при известной записи -> 409/403. POST и DELETE одного ID сериализуются по одному transaction-level advisory lock (namespace + referenceId; collision только сериализует), далее file lock в общем порядке. POST проверяет tombstone прежде создания ссылки. Tombstone не удалять в MVP. Иначе возможен поздний POST, завершившийся после компенсирующего DELETE, и навсегда заблокировавший файл. Сервисные credentials только у доверенного Assignment; логировать конфликт. Это дополнительная таблица Education, не меняющая число 36 таблиц Assignment.

### 4.3. GET содержимого и metadata (новые)

`GET /internal/v1/materials/{fileId}/references/{referenceId}/content`, scope materials:references:read; 200 binary stream с корректными Content-Type, Content-Length (если известно), Content-Disposition; `GET .../metadata` -> `{id,fileName,mimeType,sizeBytes}`. Только зарегистрированная активная ссылка данного consumer и соответствующий ACTIVE file; released/not found -> 404. Не проверять membership/ACL конкретного ученика в Education: право на выданную работу проверяет Assignment, иначе удаление курса ломает историю. Assignment проверяет user auth, ownership/role, submission-item-version, active link и reveal/started restrictions; делает internal GET и стримит ответ пользователю. Для teacher preview аналогичный контекст version. Metadata допускается кешировать локально как presentation snapshot, но не как право скачивания. Ссылки materialIds — общие материалы условия, не скрытые пояснения (пока отдельного поля назначения материала нет).

## 5. Saga публикации версии задачи

1. Локальная транзакция: lock task, expectedVersionId, ownership, validate images/config; не более одной PENDING/ABORTING версии; сохранить immutable content + PENDING + stable referenceIds + actorId/owner + ссылки файлов. Предыдущая ACTIVE остаётся current. Если ссылок нет — ACTIVE сразу.
2. Durable worker получает PENDING с lease/token, выполняет POST по одному reference вне SQL. Записывает ACTIVE на ссылке только если lease и версия по-прежнему актуальны. Timeout — unknown outcome, retry тот же ID. При частичном успехе оставшиеся продолжаются. Не делать новый UUID после timeout. Auth/permanent conflict -> ABORTING; запрет новых registration operations.
3. При всех ACTIVE в SQL lock task/version, повторно проверить состояние/current expected/deleted, ownership invariant; атомарно выставить ACTIVE и current_version_id. Если task deleted или операция отменена — compensation, не переписывать более новую версию. Для imported manualOverride атомарно с успешной публикацией.
4. Compensation: зафиксировать ABORTING + RELEASE_PENDING и durable cleanup_job для **всех** referenceIds, даже неподтверждённых, затем DELETE до успешного подтверждения. Поздний POST не может восстановить ссылку из-за Education tombstone. Техническая ошибка доступна в GET publication; после очистки удаляем pending версию/новый пустой task при отсутствии истории, освобождаем изображения только при отсутствии иных ссылок.
5. Restart: reconcile PENDING/ABORTING/RELEASE_PENDING, lease expiry, повтор тех же ID. Нельзя помечать failed операция как успешно опубликованную. Не освобождать материалы старой ACTIVE версии при обновлении или soft delete исторической задачи; физическая очистка допустима только когда версия больше не используется и новые ссылки запрещены.

## 6. Контрактные тесты интеграции

- Identity: invalid token/aud/scope/user token denied, блокировка текущего пользователя после выдачи JWT, missing user и incomplete, корректный batch 500, превышение лимита, no PII leakage, timeout fail closed.
- Education resolve: ownership, DELETING, overlapping courses, separate students, invalid arrays, snapshots; membership false после удаления и при чужом учителе.
- References: duplicate POST/DELETE, different payload same ID, delete file/folder vs register (один законный исход), denied чужой файл, service auth, timeout after commit, late POST after DELETE, partial registration rollback, retry after crash; active historical link surviving course deletion/ACL changes.
- Выдача: проверка referenceId/fileId/consumer, отказ после release, чужой submission или item 404, до EXAM start и explanation reveal denied, потоковый ответ без MinIO URL.

Документ — спецификация для интеграции, не отчёт о фактической реализации в Identity/Education. Изменять другие сервисы только минимально необходимыми отдельными коммитами/тестами.
