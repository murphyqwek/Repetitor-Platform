# Education Service

Java 17, Spring Boot 4.1.1, Gradle 9.8.2 / Kotlin DSL. Работают инфраструктурный каркас,
PostgreSQL/Flyway/JPA, Actuator и потоковый MinIO-адаптер `ObjectStorage`.
Подключены JWT Resource Server, проверка текущего профиля Identity, права и единые HTTP-ошибки.
Работают курсы, текущие зачисления, список учеников и локальный флаг запрета отправки сообщений.
Работают создание/перенос/отмена разовых занятий и месячные календари курса/пользователя.
Работают бессрочные серии с переносами/отменами вхождений и остановкой по московской дате.
Библиотека преподавателя поддерживает папки, файлы и правила доступа через курс. Чат использует
REST для истории и вложений и WebSocket/STOMP для сообщений. Удаление курсов, файлов и папок
выполняется через очередь очистки.
Проверки `/actuator/health`, `/actuator/health/liveness` и `/actuator/health/readiness` публичны.
`GET /ws` открывает транспорт, STOMP `CONNECT` требует Bearer JWT.
`/api/v1/**` требует Bearer JWT и актуальный доступ в Identity.
Внутренний API ссылок на материалы требует credential Assignment. Lookup пользователя по UUID подключён
к согласованному расширению каталога Identity, см. ADR-002.

## Курсы

| Метод | Маршрут | Реализованное поведение |
|---|---|---|
| POST | `/api/v1/courses` | Создание, 201 + Location; TEACHER создаёт свой, ADMIN может выбрать teacherId |
| GET | `/api/v1/courses/{courseId}` | ACTIVE курс владельцу/администратору/текущему ученику; недоступный — 404 |
| PATCH | `/api/v1/courses/{courseId}` | Только title; owner/admin, 200; UUID владельца и другие поля неизменяемы |
| GET | `/api/v1/courses?scope=OWNED\|ENROLLED\|ALL` | ACTIVE only, keyset createdAt DESC/id DESC, limit 30/max100 |

Название обязательно и может повторяться. Не вводится произвольное ограничение в 255 символов.
Пробелы по краям удаляются. POST не требует Idempotency-Key; повтор может создать другой курс.
CourseDto содержит ровно id, teacherId, title, meetingUrl, chatMode, createdAt. У нового курса
meetingUrl=null и chatMode=NORMAL. createdAt в UTC, с точностью PostgreSQL до микросекунд.
Внешний teacherId не является SQL FK к Identity. Схему course создаёт Flyway V2, Hibernate её только проверяет.

Обычный преподаватель не может передавать teacherId при создании или выборе OWNED (403).
ADMIN без TEACHER обязан выбрать teacherId для POST; получатель должен существовать и иметь роль TEACHER.
Проверка Identity выполняется до SQL write transaction; timeout/отказ зависимости даёт 503 без записи курса.
Отсутствующий пользователь даёт 404, роль не TEACHER — 422. Блокировка вызывающего по-прежнему запрещает API;
дополнительный запрет администратору создать курс для заблокированного владельца контрактом не установлен.
ADMIN+TEACHER без teacherId действует как преподаватель и создаёт свой курс.

Для OWNED преподаватель получает только свои курсы. ADMIN без TEACHER обязан передать teacherId;
ADMIN+TEACHER может запросить свои без фильтра или чужие с явным фильтром. ALL доступен только ADMIN,
teacherId в ALL необязателен. Scope обязателен и выбирается явно, включая пользователей с несколькими ролями.
Курсор привязан к пользователю, scope, фильтру владельца и порядку; перенести его в другой запрос нельзя (400).
Удаление опорной строки не ломает продолжение; limit+1 определяет hasMore без подсчёта всех строк.

PATCH отклоняет teacherId, meetingUrl, chatMode, deletionState, id и createdAt как неизвестные поля (400).
Для входных JSON включён fail-on-unknown-properties; DTO Identity явно разрешают лишние upstream поля.
Изменение title берёт row lock и проверяет ACTIVE: PATCH, ожидавший перехода в DELETING, не коммитит переименование.
Ученику PATCH запрещён 403 независимо от существования UUID. Чужой преподаватель получает 404.

`scope=ENROLLED` требует STUDENT и возвращает ACTIVE курсы с текущим членством пользователя.
teacherId для этого scope не принимается. После исключения новые чтения курса и ENROLLED сразу закрывают доступ.
Членство TEACHER+STUDENT в чужом курсе разрешает чтение, но не управление курсом или списком учеников.
`DELETE /api/v1/courses/{courseId}` возвращает `202`. Курс сразу скрывается, фоновая очистка
удаляет чат, расписание, зачисления и гранты курса на материалы. Файлы библиотеки сохраняются.

Примеры PowerShell с JWT Identity в локальной `$env:EDUCATION_ACCESS_TOKEN` (токен не коммитить):

```powershell
$headers = @{ Authorization = "Bearer $env:EDUCATION_ACCESS_TOKEN" }
$body = @{ title = 'Математика' } | ConvertTo-Json
$course = Invoke-RestMethod -Method Post -Uri 'http://localhost:8081/api/v1/courses' -Headers $headers -ContentType 'application/json' -Body $body
Invoke-RestMethod -Uri "http://localhost:8081/api/v1/courses/$($course.id)" -Headers $headers
Invoke-RestMethod -Uri 'http://localhost:8081/api/v1/courses?scope=OWNED&limit=30' -Headers $headers
$rename = @{ title = 'Алгебра' } | ConvertTo-Json
Invoke-RestMethod -Method Patch -Uri "http://localhost:8081/api/v1/courses/$($course.id)" -Headers $headers -ContentType 'application/json' -Body $rename
```

Эти команды создают курс в local volume. Его может удалить владелец или администратор.

## Зачисления

Все четыре маршрута доступны владельцу курса и ADMIN. Ученику список участников недоступен.

| Метод | Маршрут относительно `/api/v1/courses/{courseId}` | Результат |
|---|---|---|
| GET | `/students?limit=30&cursor=...` | items/nextCursor/hasMore, порядок enrolledAt DESC/studentId DESC, максимум 100 |
| POST | `/students` | `{studentId,confirmConflicts?}`, 201 + Location и объект участника |
| DELETE | `/students/{studentId}` | 204, физическое удаление связи; повтор в доступном ACTIVE курсе также 204 |
| PATCH | `/students/{studentId}/chat-permissions` | обязательный Boolean canSendMessages, 200 `{studentId,canSendMessages}` |

Объект участника: studentId, displayName, blocked, canSendMessages, enrolledAt. Имя и blocked каждый раз
получаются из Identity, копии в БД не сохраняются. Если профиль существующего участника недоступен или исчез,
список возвращает 503 без удаления членства. Курсор привязан к courseId, вызывающему и порядку выборки.

При зачислении Identity проверяет существование (404 USER_NOT_FOUND), STUDENT (422 INVALID_STUDENT)
и blocked (409 STUDENT_BLOCKED). Дубликат — 409 ALREADY_ENROLLED, отказ Identity — 503 IDENTITY_UNAVAILABLE.
Два конкурентных POST сохраняют одну связь. Внешний studentId без Identity FK; Flyway V3 создаёт course_student.
Identity lookup проходит до write transaction; запись берёт lock курса и повторно проверяет ACTIVE.

Блокировка в Identity запрещает доступ ученика, но оставляет его в составе курса с актуальным blocked=true.
canSendMessages=false не ограничивает чтение курса. PATCH отсутствующего членства — 404.
Повторное зачисление создаёт новый enrolledAt и сбрасывает canSendMessages в true. DELETE удаляет только членство.
Право отправки влияет на STOMP SEND и загрузку вложений. Режим ANNOUNCEMENTS закрывает их для учеников.

При зачислении сервис проверяет будущие занятия ученика во всех активных курсах. При пересечении повторите команду с confirmConflicts=true после просмотра предупреждения.

```powershell
# После создания $course, с Bearer JWT владельца/администратора в $headers:
$studentId = Read-Host 'UUID существующего пользователя с ролью STUDENT'
$members = "http://localhost:8081/api/v1/courses/$($course.id)/students"
$enrollment = @{ studentId = $studentId; confirmConflicts = $false } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri $members -Headers $headers -ContentType 'application/json' -Body $enrollment
Invoke-RestMethod -Uri "$($members)?limit=30" -Headers $headers
Invoke-RestMethod -Method Patch -Uri "$members/$studentId/chat-permissions" -Headers $headers -ContentType 'application/json' -Body '{"canSendMessages":false}'
Invoke-RestMethod -Method Delete -Uri "$members/$studentId" -Headers $headers
# С Bearer JWT ученика в $studentHeaders:
Invoke-RestMethod -Uri 'http://localhost:8081/api/v1/courses?scope=ENROLLED' -Headers $studentHeaders
```

## Разовые занятия и календарь

| Метод | Маршрут | Результат |
|---|---|---|
| POST | `/api/v1/courses/{courseId}/lessons` | owner/admin; startsAt/endsAt, optional confirmConflicts; 201 + SINGLE DTO/Location |
| PATCH | `/api/v1/courses/{courseId}/lessons/{lessonId}` | owner/admin; оба новых времени, optional reason/confirmConflicts; 200 |
| POST | `/api/v1/courses/{courseId}/lessons/{lessonId}/cancel` | owner/admin; `{reason?}`; физическое удаление, 204 |
| GET | `/api/v1/courses/{courseId}/schedule?month=2026-12` | владелец/admin/текущий ученик; календарь одного московского месяца |
| GET | `/api/v1/me/schedule?month=2026-12` | собственные курсы TEACHER и текущие зачисления STUDENT без дублей |

Тело создания/переноса принимает ISO timestamps с явным offset или Z. Длительность строго 5–300 минут,
сравнивается точно, без округления к минутам; вне диапазона — 422 INVALID_LESSON_TIME.
В ответе время UTC с точностью PostgreSQL до микросекунд. Отдельных статусов PLANNED/COMPLETED нет.
SINGLE DTO: courseId, kind=SINGLE, singleLessonId, seriesId=null, originalDate=null, startsAt, endsAt,
rescheduled=false. Перенос меняет время существующего singleLessonId, история в этой таблице не ведётся.

month обязателен, строго YYYY-MM (год 0001–9999); неверный месяц — 400 INVALID_MONTH.
Ответ: `{month,timeZone:"Europe/Moscow",lessons:[...]}`, сортировка startsAt ASC/id ASC.
Границы вычисляются через Europe/Moscow, независимо от timezone JVM. Выбираются все занятия,
пересекающие полуоткрытый месяц: startsAt < monthEnd и endsAt > monthStart. Пересекающее границу
занятие возвращается с полным временем, без обрезки. Прошедшие занятия также видны в запрошенном месяце.

У ADMIN нет глобального me/schedule: персональный календарь определяется его ролями TEACHER/STUDENT
и собственными курсами/членствами; чистый ADMIN получает пустой lessons. Календарь конкретного курса ему доступен.
Снятие членства закрывает календарь ученика на следующем запросе. canSendMessages не влияет на чтение.
Чужой lessonId даже в другом собственном курсе — 404; ACTIVE и права mutations проверяются под lock курса.
Отменённый урок физически удалён: повтор cancel и дальнейший PATCH — 404, восстановления нет.
Idempotency-Key необязателен и не дедуплицирует создание урока.

При пересечении интервалов у преподавателя или ученика API возвращает 409 SCHEDULE_CONFLICTS.
В ответе видны только участник и время пересечения. Смежные интервалы допустимы. Повтор с
`confirmConflicts=true` заново проверяет расписание и сохраняет изменение.

Изменение расписания и запись события фиксируются одной транзакцией PostgreSQL. Из outbox
события доставляются в Kafka с гарантией at least once. При сбое после ACK возможен повтор
с тем же eventId. В Compose адрес брокера `kafka:29092`, на хосте `localhost:9092`.
Настройки — `EDUCATION_KAFKA_BOOTSTRAP_SERVERS`, `EDUCATION_SCHEDULE_TOPIC` (по умолчанию
`education.schedule.v1`) и `EDUCATION_OUTBOX_ENABLED`. Формат событий описан в [ADR-003](../../docs/артефакты/education-service/ADR-003-schedule-events-v1.md).

Из каталога `services/education-service` можно отдельно запустить проверки этого этапа
с Docker и Gradle wrapper:

```sh
./gradlew test --tests '*ConflictHttpTest' --tests '*OutboxKafkaTest'
```

```powershell
# $course и $headers — курс и Bearer JWT его владельца/ADMIN из примера выше.
$lessons = "http://localhost:8081/api/v1/courses/$($course.id)/lessons"
$body = @{ startsAt = '2026-12-01T17:00:00+03:00'; endsAt = '2026-12-01T18:30:00+03:00'; confirmConflicts = $false } | ConvertTo-Json
$lesson = Invoke-RestMethod -Method Post -Uri $lessons -Headers $headers -ContentType 'application/json' -Body $body
Invoke-RestMethod -Uri "http://localhost:8081/api/v1/courses/$($course.id)/schedule?month=2026-12" -Headers $headers
Invoke-RestMethod -Uri 'http://localhost:8081/api/v1/me/schedule?month=2026-12' -Headers $headers
$move = @{ startsAt = '2026-12-02T14:00:00Z'; endsAt = '2026-12-02T15:30:00Z'; reason = 'Перенос' } | ConvertTo-Json
Invoke-RestMethod -Method Patch -Uri "$lessons/$($lesson.singleLessonId)" -Headers $headers -ContentType 'application/json' -Body $move
Invoke-RestMethod -Method Post -Uri "$lessons/$($lesson.singleLessonId)/cancel" -Headers $headers -ContentType 'application/json' -Body '{}'
```

## Бессрочные серии

Все команды и список правил доступны владельцу курса/ADMIN. Ученик получает вхождения в календаре.

| Метод | Маршрут относительно `/api/v1/courses/{courseId}/lesson-series` | Результат |
|---|---|---|
| POST | корень | startDate, weekdays, startTime, durationMinutes, optional confirmConflicts; 201 + SeriesDto/Location |
| GET | `?limit=30&cursor=...` | items/nextCursor/hasMore, включая остановленные; максимум 100 |
| POST | `/{seriesId}/stop` | `{stopFromDate}`, 200 SeriesDto |
| POST | `/{seriesId}/occurrences/{originalDate}/reschedule` | startsAt/endsAt, optional reason/confirmConflicts; 200 RECURRING DTO |
| POST | `/{seriesId}/occurrences/{originalDate}/cancel` | `{reason?}`, 204; постоянное исключение CANCELLED |

SeriesDto: id, courseId, startDate, weekdays, startTime, durationMinutes, stopFromDate, timeZone=Europe/Moscow.
weekdays — непустой список уникальных ISO-дней 1..7, возвращается отсортированным; durationMinutes — 5–300.
startDate/startTime локальные для Москвы, stopFromDate изначально null. Даты окончания/конечного горизонта нет.
Список правил использует keyset createdAt DESC/id DESC; cursor привязан к courseId/actor/sort и работает после удаления anchor.

Вхождения вычисляются для запрошенного месяца и не создают строк single_lesson. В общем календаре
RECURRING имеет singleLessonId=null и стабильную пару seriesId+originalDate. У обычного вхождения
rescheduled=false; перенос сохраняет исходную пару, возвращает rescheduled=true и реальные UTC startsAt/endsAt.
Порядок смешанного календаря — startsAt, затем UUID урока/серии и originalDate для совпадающего времени.
Перенос в другой месяц исключает исходное вхождение и появляется в целевом месяце без дубля;
допускается перенос будущего вхождения в месяц до startDate. Reason хранится в lesson_exception.

Для originalDate проверяются начало правила и weekday (иначе 404 NOT_FOUND). Повторные переносы
обновляют одну exception с UNIQUE(series_id,original_date), а не создают новые уроки.
Отмена создаёт/обновляет CANCELLED, повтор cancel даёт 204; дальнейший перенос — 409 LESSON_CANCELLED.
Нового API восстановления или массового редактирования серии нет.

Остановка inclusive по **originalDate**: дата >= stopFromDate не возникает/не изменяется (409 SERIES_STOPPED).
Перенос с originalDate < stopFromDate остаётся даже при фактическом времени после остановки.
Например, 15→25 декабря и stopFromDate=20 декабря: 25-е остаётся. Ранее перенесённое исходное 22-е
после такого stop не показывается, даже если фактический перенос был на 19-е.
Повтор stop с той же датой даёт 200, с другой — 409 SERIES_ALREADY_STOPPED; возобновление не реализовано.
Остановка до startDate допустима и не создаёт вхождений.

Генерация ограничена месяцем и предыдущим днём для пересекающих границу занятий (максимум 5 часов).
Exception query дополнительно выбирает перенесённые интервалы из других месяцев и остановленных серий.
Все mutations сериализуются lock курса и проверяют ACTIVE. Weekdays/интервалы дополнительно защищены SQL CHECK.
При создании серии проверяются 30 московских календарных дней от более поздней из дат начала серии и сегодняшнего дня.

```powershell
# $course, $headers — существующий курс и Bearer JWT владельца/ADMIN.
$seriesUrl = "http://localhost:8081/api/v1/courses/$($course.id)/lesson-series"
$body = @{ startDate = '2026-12-01'; weekdays = @(2, 4); startTime = '17:00'; durationMinutes = 90 } | ConvertTo-Json
$rule = Invoke-RestMethod -Method Post -Uri $seriesUrl -Headers $headers -ContentType 'application/json' -Body $body
Invoke-RestMethod -Uri "$($seriesUrl)?limit=30" -Headers $headers
$move = @{ startsAt = '2026-12-25T14:00:00Z'; endsAt = '2026-12-25T15:30:00Z'; reason = 'Перенос' } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "$seriesUrl/$($rule.id)/occurrences/2026-12-15/reschedule" -Headers $headers -ContentType 'application/json' -Body $move
Invoke-RestMethod -Method Post -Uri "$seriesUrl/$($rule.id)/occurrences/2026-12-17/cancel" -Headers $headers -ContentType 'application/json' -Body '{}'
Invoke-RestMethod -Method Post -Uri "$seriesUrl/$($rule.id)/stop" -Headers $headers -ContentType 'application/json' -Body '{"stopFromDate":"2026-12-20"}'
Invoke-RestMethod -Uri "http://localhost:8081/api/v1/courses/$($course.id)/schedule?month=2026-12" -Headers $headers
```

## Layout и адреса

Сервис находится в `services/education-service`, общий Compose — `Docker/local/compose.yml`
(заглавная `D`). Корневой пустой `docker-compose.yml` не используется.
Gradle Wrapper скопирован из Identity, версия и SHA-256 закреплены. Identity расширен только согласованным UUID lookup.
Compose project: `tutor-platform-local`, сеть: `tutor-platform-local_default`.
Существующие PostgreSQL/Redis/Kafka сохраняют настройки. Education использует отдельный PostgreSQL,
чтобы Flyway не работал со схемой Identity и не требовал пересоздания существующего volume.

| Зависимость | С хоста | В сети Compose |
|---|---|---|
| Education | `http://localhost:8081` | `http://education:8080` |
| PostgreSQL Education | `jdbc:postgresql://localhost:5433/education` | `jdbc:postgresql://education-postgres:5432/education` |
| MinIO S3 | `http://localhost:9000` | `http://minio:9000` |
| MinIO Console | `http://localhost:9001` | `http://minio:9001` |
| Kafka, события расписания | `localhost:9092` | `kafka:29092` |
| Identity, запускается отдельно | `http://localhost:8080` | `http://host.docker.internal:8080` |

Kafka нужна для публикации событий расписания. Nginx по-прежнему
направлен на существующий `API_UPSTREAM`; Education доступен отдельно на 8081.
Все новые хостовые порты привязаны к `127.0.0.1`.

## Выбор локального S3-хранилища

**Используется Silo — отдельный community-форк MinIO, а не upstream MinIO.** Замена явно
зафиксирована в `../../docs/артефакты/education-service/ADR-001-local-object-storage.md`.
Образы `pgsty/silo` и `pgsty/mc` RELEASE.2026-11-12T00-00-00Z фактически скачаны и закреплены
по digest в Compose и тестах. Server и client одного релиза. Это выбор для local development;
production-хранилище и миграция существующего MinIO-кластера не входят в этап.
См. [Testcontainers MinIO/Silo](https://java.testcontainers.org/modules/minio/).

**При проверке 20.11.2026 оба Quay pull вернули `401 UNAUTHORIZED`.**
Архивный upstream MinIO — устаревшая, не обновляемая сборка; даже доступное зеркало нельзя
безусловно рекомендовать для production. Docker Hub `minio/minio` / `minio/mc` не используется.
История неудачных Quay-проверок сохранена отдельно. Непроверенные локальные образы не используются.
Результаты реальных S3-тестов на выбранном Silo приведены в отчёте этапа.

## Переменные и профили

Multipart использует технические лимиты `EDUCATION_MULTIPART_MAX_FILE_SIZE` (250 МБ) и
`EDUCATION_MULTIPART_MAX_REQUEST_SIZE` (252 МБ). При загрузке в библиотеку временный и целевой ключи
до обращения к S3 записываются в техническую таблицу `pending_upload`. Очередь очистки удаляет
временный объект и, если публикация не состоялась, целевой объект.

Внутренние маршруты `/internal/v1/materials/**` используют отдельный Bearer-токен Assignment
(`EDUCATION_ASSIGNMENT_TOKEN`, случайное значение от 32 байт). Без него маршруты закрыты.
Публичный прокси не должен передавать `/internal/` во внутреннюю сеть.

В корне есть `.env.example`; старый `.env.exmaple` сохранён. Перенесите недостающие значения
в игнорируемый `.env`, сохранив существующие настройки Identity. Не коммитьте `.env`.
При работе Codex недостающие Education credentials генерируются локально без вывода значений.

- `EDUCATION_DB_USER`, `EDUCATION_DB_PASSWORD`: отдельный пользователь PostgreSQL Education.
- `MINIO_ROOT_USER`, `MINIO_ROOT_PASSWORD`: только MinIO и bootstrap, не приложение.
- `EDUCATION_S3_ACCESS_KEY`, `EDUCATION_S3_SECRET_KEY`: отдельный пользователь S3.
- `EDUCATION_S3_BUCKET`: по умолчанию `education-private` в local.
- `EDUCATION_DB_URL`, `EDUCATION_S3_ENDPOINT`, `EDUCATION_PORT`: адреса хоста;
  Compose явно задаёт контейнерные адреса и внутренний порт 8080.
- `SPRING_PROFILES_ACTIVE=local`: хостовая разработка или local Compose.
- `JAVA_TOOL_OPTIONS`: JVM-параметры; в образе задан `-Duser.timezone=UTC`.
- `EDUCATION_JWT_PUBLIC_KEY_LOCATION`: X.509 RSA public PEM, для хоста по умолчанию
  `file:../../dev_secrets/jwt_public.pem`; Compose монтирует только public key read-only в `/run/keys/identity-public.pem`.
- `EDUCATION_JWT_ISSUER`: `rp-identity`, соответствует существующему Identity.
- `EDUCATION_IDENTITY_BASE_URL`: адрес Identity для host run; local default `http://localhost:8080`.
- `EDUCATION_IDENTITY_DOCKER_URL`: адрес Identity с точки зрения контейнера, local default
  `http://host.docker.internal:8080`. Identity не включён в этот Compose и запускается отдельно.
- `EDUCATION_CURSOR_KEY`: Base64 32 случайных байт, хранить постоянно между перезапусками.
  Смена ключа инвалидирует ранее выданные курсоры. Пустое значение запрещает запуск.

Генерация ключа курсоров в PowerShell (вывод только строки для локального `.env`, не для Git):

```powershell
[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
```

## JWT, Identity и ошибки

Education принимает `Authorization: Bearer <JWT>`, cookie-аутентификация не поддерживается.
Проверяются RS256, issuer, exp/nbf/iat, UUID subject и массив известных ролей. Session не создаётся;
CSRF выключен для этого API с токеном в заголовке. Политика Identity с cookie/CSRF не менялась.
Нельзя подставлять приватный ключ в Education или копировать его в Docker image.

После проверки подписи каждый `/api/v1/**` запрос получает свежий профиль через реальный
`GET /api/v1/users/me`. Identity сейчас понимает только cookie, поэтому клиент передаёт ему
только проверенный JWT как `Cookie: access_token=...`. Другие cookies не пересылаются, redirects
не выполняются. Connect timeout — 2 секунды, read timeout — 3 секунды; профиль не кешируется.
Эффективные роли — пересечение JWT и свежего профиля: изменение профиля не добавляет неподписанные
полномочия, а отзыв роли начинает действовать со следующего запроса. UUID профиля должен совпасть с `sub`.

Блокировка закрывает следующий запрос с тем же JWT; `blocked=true` и Identity 403 дают отказ 403.
Identity 401 даёт 401, отсутствие собственного аккаунта — 403. Timeout/недоступность/5xx/невалидный
профиль — 503 `IDENTITY_UNAVAILABLE`. Преподаватель с незаполненным профилем получает 403,
согласно существующим правилам Identity. Роль TEACHER дополнительно требует совпадения владельца;
само наличие роли не доказывает доступ к чужому курсу. Отдельный PARENT не даёт прав Education.

Security и MVC возвращают `application/problem+json` с `code`. Неверные UUID/limit/cursor/JSON — 400;
DTO validation — 422; также поддержаны 401/403/404/409/413/415/503. В ошибки не попадают токены,
upstream bodies, SDK exceptions и значения отклонённых полей.

Общий курсор AES-256-GCM скрывает `createdAt` и UUID, защищает целостность и привязывается к серверному
scope: пользователь, курс, фильтры и порядок должны входить в scope при подключении будущего маршрута.
Курсор действует 24 часа; удаление опорной строки его не ломает. Общий limit: default 30, max 100;
для истории чата используется default 50. Список курсов использует этот курсор.

`IdentityClient.user(accessToken, id)` вызывает `GET /api/v1/directory/users/{id}` в обновлённом Identity.
Доступ к каталогу — TEACHER/ADMIN; ответ содержит только UUID, ФИО, роли и blocked. Для несуществующего
целевого UUID возвращается 404 `USER_NOT_FOUND`, для timeout/недоступности/плохого ответа — 503.
При зачислении Education проверяет роль STUDENT и статус блокировки по этому ответу. Логины, пароли и прочие поля через этот lookup не возвращаются.
Если Identity был запущен ранее, перезапустите его из обновлённых исходников/нового jar.
Решение: `../../docs/артефакты/education-service/ADR-002-identity-integration.md`.

Базовый профиль не имеет секретов и требует явных адресов/credentials. Профиль `test`
получает настройки через Testcontainers, без локальных или production секретов.
Hibernate использует `validate`, Flyway — единственный источник DDL. Миграция V1 отмечает
схему как управляемую Flyway; V2 создаёт course, не затрагивая данные V1. JDBC-сессии и сериализация настроены на UTC;
локальные правила календаря используют Europe/Moscow.

## Сборка и полный запуск

Команды Compose ниже выполняются из корня репозитория. Нужны Docker Desktop / Linux Engine
и Compose v2. Для хостовой сборки нужен JDK 17, глобальный Gradle не требуется.

```powershell
java -version
docker version
docker compose version
docker pull pgsty/silo:RELEASE.2026-11-12T00-00-00Z@sha256:c20d112390b9a9ce8c11b8af95ad35bcaa62f4759663c09c936cd8d2b511f329
docker pull pgsty/mc:RELEASE.2026-11-12T00-00-00Z@sha256:b532278b7a3747dbab6fe45eebb40a653efa704e6758e24e9d9ec54fc96c1800
docker compose --env-file .env -f Docker/local/compose.yml config --quiet
docker compose --env-file .env -f Docker/local/compose.yml up -d --build
docker compose --env-file .env -f Docker/local/compose.yml ps -a
curl.exe --fail http://localhost:8081/actuator/health/liveness
curl.exe --fail http://localhost:8081/actuator/health/readiness
docker compose --env-file .env -f Docker/local/compose.yml logs --tail 100 education minio-init
```

`config --quiet` проверяет конфигурацию без печати подставленных секретов.
Bootstrap ограниченно ждёт MinIO, создаёт bucket повторяемо, снимает anonymous policy и создаёт
пользователя с `Get/Put/Delete` только в `materials/`, `chat/`, `tmp/` этого bucket.
Разрешены также bucket HEAD/list и операции завершения/очистки multipart. Приложение не имеет
прав администрирования, создания bucket или доступа к объектам вне своих префиксов.
Пустые папки в S3 не создаются: префиксы появляются при записи объекта.
После изменения bucket или credentials перезапустите bootstrap явно:

```powershell
docker compose --env-file .env -f Docker/local/compose.yml run --rm minio-init
docker compose --env-file .env -f Docker/local/compose.yml up -d --force-recreate education
```

Повторный `up` безопасен, но Compose может повторно использовать завершённый init-контейнер;
`run --rm` явно исполняет bootstrap заново. Приложение ожидает успешного завершения bootstrap
и готовности PostgreSQL. Readiness дополнительно проверяет БД и аутентифицированный доступ к bucket;
liveness не зависит от доступности внешних сервисов. Неправильные S3 credentials или недоступный
MinIO дают readiness HTTP 503; детали ошибок и секреты в HTTP не раскрываются.

## Запуск с хоста

Из корня запустите зависимости и bootstrap:

```powershell
docker compose --env-file .env -f Docker/local/compose.yml up -d education-postgres minio
docker compose --env-file .env -f Docker/local/compose.yml run --rm minio-init
# Если Education уже запущен в Docker, освободите только его порт:
docker compose --env-file .env -f Docker/local/compose.yml stop education
Set-Location services/education-service
Get-Content ../../.env | ForEach-Object {
    if ($_ -match '^(EDUCATION_[A-Z_]+)=(.*)$') {
        Set-Item -Path "Env:$($Matches[1])" -Value $Matches[2].Trim().Trim('"').Trim("'")
    }
}
$env:SPRING_PROFILES_ACTIVE = 'local'
$env:JAVA_TOOL_OPTIONS = '-Duser.timezone=UTC'
.\gradlew.bat bootRun
```

Этот загрузчик покрывает простые однострочные значения из `.env.example`, без shell expansion.
В IDE задайте те же переменные через Run Configuration. Ctrl+C завершает приложение корректно.

## Чат и материалы

Маршруты и права описаны в [REST-контракте](../../docs/архитектура/education-service/education_service_rest_api_contract.md)
и [STOMP-контракте](../../docs/архитектура/education-service/chat_websocket_contract.md).
При исключении ученика открытая подписка на курс закрывается. Блокировка в Identity проверяется
при подписке, отправке и перед доставкой очередного события. Broker работает в одном экземпляре
Education, после переподключения события сверяются с REST-историей.

| API | Доступ | Проверка |
| --- | --- | --- |
| Курсы, зачисления и расписание | Владелец/admin меняют, участники читают доступные данные | `CourseHttpTest`, `ScheduleHttpTest`, `SeriesHttpTest`, `ConflictHttpTest` |
| Библиотека, гранты, скачивание | Владелец/admin управляют библиотекой. Ученик читает только по гранту своего курса | `MaterialHttpTest`, `EducationInfrastructureTest` |
| Ссылки Assignment | Отдельный служебный Bearer-токен | `MaterialHttpTest`, `SecurityHttpTest` |
| История, вложения, STOMP | Доступ к активному курсу. Отправка учитывает режим и персональный запрет | `CourseHttpTest`, `EducationInfrastructureTest` |
| Удаление курса и модерация | Владелец или admin с проверкой автора сообщения | `CourseHttpTest`, `EducationInfrastructureTest`, `CleanupJobsTest` |

Метрики Micrometer содержат задержки HTTP, ошибки Identity/S3, возраст событий outbox, состояние очереди
очистки и активные WebSocket-соединения. Для локального просмотра метрик через JMX задайте
`EDUCATION_JMX_ENABLED=true`. Ответы API содержат `X-Request-Id` для поиска ошибки в логах.

## Тесты

Из `services/education-service`:

```bash
./gradlew --version
./gradlew clean test
./gradlew bootJar
```

В Windows PowerShell эквиваленты: `.\gradlew.bat --version`, `.\gradlew.bat clean test`,
`.\gradlew.bat bootJar`. Git Bash поддерживает исходные команды `./gradlew`.
Docker-сборка действительно выполняет Linux `./gradlew --no-daemon bootJar` внутри JDK17 stage.

- `StorageUnitTest`: генератор ключей, уникальность, метаданные, конфигурация без секретов,
  отсутствие утечки других credentials в ошибке запуска, закрытие upload stream.
- `EducationContextTest`: реальная изолированная PostgreSQL 17, Flyway, UTC, HTTP health,
  закрытые пути, readiness 503, повторная миграция без потери данных и keyset с равными timestamps/удалённым anchor.
  Только S3-клиент здесь замокан для независимости проверки контекста.
- `EducationLocalProfileTest`: те же проверки с изолированным профилем local.
- `SecurityHttpTest`: настоящий HTTP Resource Server с generated RSA test keys, подписанными JWT,
  PostgreSQL и локальным HTTP Identity stub. Проверяются плохая подпись/issuer/время/claims,
  несколько ролей, owner/admin/student, блокировка без кеша, отзыв ролей, Identity 403/503/timeout,
  невалидные профили, Problem JSON, DTO/UUID/limit/cursor/multipart и закрытые internal paths.
  Тестовые контроллеры существуют только в src/test и не попадают в jar/image.
- `CursorCodecTest`: round-trip, уникальность nonce, scope, tampering, expiry, ключи и лимиты.
- `IdentityLookupTest`: реальный HTTP lookup, точный UUID/cookie, blocked/роли, 404 vs 503,
  redirects, плохие ответы, timeout и ошибка соединения.
- `CourseHttpTest`: настоящий HTTP, подписанные JWT, PostgreSQL и HTTP Identity stub;
  CRUD/Location/DTO/roles/owner/admin, строгий PATCH, scopes/filters/limits, keyset с равными timestamps,
  удалённый anchor, scope-bound cursor, concurrent create и PATCH против DELETING с реальным SQL lock,
  DB constraints и upgrade V1 → V2 → V3 без потери существующих данных; зачисление/исключение/повторное
  зачисление, текущие blocked/name, ошибки Identity, права участников с несколькими ролями, ENROLLED,
  roster pagination, конкурентный POST и гонки enrollment/DELETE/permissions/roster с DELETING.
- `EducationInfrastructureTest`: настоящие Silo + mc + PostgreSQL через Testcontainers;
  PUT/GET/STAT/DELETE бинарных данных, Content-Type/size, unknown-length multipart,
  повторное удаление, missing object, anonymous 403, ограничение префиксов,
  повторный bootstrap, неверные credentials, downtime/readiness и сохранение объекта после restart.
  Недоступность Docker или registry завершает этот suite ошибкой, а не успешным skip.
- `ScheduleHttpTest`: настоящий HTTP/JWT/PostgreSQL и общий с курсами Identity HTTP fixture;
  create/move/cancel, offset/UTC, точные 5–300 минут, месячные границы/overlap, teacher/student/admin/multiple roles,
  me union без дублей, исключение, DELETING и реальные SQL lock гонки, upgrade V3 → V4, optional Idempotency-Key.
- `CalendarTimeTest`: московские границы и 28/29 дней февраля под UTC/Los_Angeles/Tokyo timezone JVM.
- `SeriesHttpTest`: реальные HTTP/JWT/PostgreSQL, бессрочные правила/weekdays/переносы/cancel/stop,
  15→25 декабря/stop20, cross-month и будущий originalDate, права/current membership,
  concurrent upsert/cancel, SQL lock/DELETING, keyset правил, upgrade V4 → V5/arrays/exception constraints.
- `RecurringCalendarTest`: виртуальные/перенесённые вхождения в UTC/Los_Angeles/Tokyo; дополнительно
  HTTP/PostgreSQL suite серий запускался с JVM America/Los_Angeles.

Для отдельной диагностики контекста и unit-тестов (не заменяет полный S3 suite):

```powershell
.\gradlew.bat test --tests '*StorageUnitTest' --tests '*EducationContextTest'
```

HTML-отчёт: `build/reports/tests/test/index.html`, XML: `build/test-results/test`.
Журнал этапа 1: `../../docs/артефакты/education-service/verification.md`;
этапа 2: `../../docs/артефакты/education-service/stage-2-verification.md`;
этапа 3: `../../docs/артефакты/education-service/stage-3-verification.md`;
этапа 4: `../../docs/артефакты/education-service/stage-4-verification.md`;
этапа 5: `../../docs/артефакты/education-service/stage-5-verification.md`;
этапа 6: `../../docs/артефакты/education-service/stage-6-verification.md`.

Тестовый JVM heap ограничен 64–384 MiB, Mockito использует self-attach без дополнительного JVM-процесса.
При недостатке памяти Windows тесты завершаются ошибкой; suite не пропускается и проверки не ослабляются.

`put` принимает владение входным потоком и закрывает его; поток из `get` закрывает вызывающий код.
`size >= 0` задаёт известный размер, `-1` использует multipart с блоком 10 MiB.
Содержимое не загружается целиком в память адаптера. SDK-ошибки преобразуются в `NOT_FOUND` /
`UNAVAILABLE` без исходных сообщений, object keys и secrets. Материалы не получают ограничения
20 МБ/whitelist чата; эти правила будут реализованы только на соответствующем этапе.

## Docker-образ и данные

```powershell
# Из корня
docker build -t tutor-education:local services/education-service
docker image inspect tutor-education:local --format '{{.Config.User}}'
```

Multi-stage build: закреплённые digest Eclipse Temurin JDK17 / JRE17; wrapper с SHA-256;
runtime `10001:10001`; exec ENTRYPOINT; jar `education-service.jar`.
Build context ограничен каталогом сервиса; `.env`, ключи, dev_secrets, build/cache и тесты исключены.
Проверено наличие `curl` в закреплённом runtime image для Compose healthcheck.
Новая общая trace-инфраструктура не добавлялась: в существующем проекте её нет.

Volumes `education_minio_data` и `education_postgres_data` сохраняются при restart и обычном down/up:

```powershell
docker compose --env-file .env -f Docker/local/compose.yml restart minio
docker compose --env-file .env -f Docker/local/compose.yml restart education
# Остановить весь local stack, сохранив данные:
docker compose --env-file .env -f Docker/local/compose.yml down
docker compose --env-file .env -f Docker/local/compose.yml up -d --build
```

**`docker compose ... down -v` разрушительно удаляет volumes, включая данные существующих сервисов.**
Для обычного перезапуска не используйте `-v`.

Если порт PostgreSQL Education на хосте занят, задайте в корневом .env свободный
`EDUCATION_DB_HOST_PORT` (по умолчанию 5433) и такой же порт в `EDUCATION_DB_URL`
для запуска Java на хосте. Например, при 15433 используйте
`jdbc:postgresql://localhost:15433/education`. Затем выполните обычный Compose up.
