# Assignment Service

Java 17, Spring Boot 4.1.1, Gradle Kotlin DSL. Этапы 1–4 дают локальный стенд, адаптер приватного хранилища, проверку пользователя и внутренние клиенты. Доступны пробы Actuator, REST API справочников, экзаменационных структур и шкал, а также временных изображений задач.

## Локальный запуск

Нужны JDK 17, работающий Docker Desktop / Docker Engine с Linux-контейнерами и Compose v2. Все команды PowerShell 7 ниже начинаются в корне репозитория. Для новой установки скопируйте `.env.example` в `.env`. В существующем окружении добавьте недостающие строки, сохраняя значения уже инициализированных БД и MinIO. Compose также использует переменные Education и общей инфраструктуры из корневого примера.

Заполните `ASSIGNMENT_DB_PASSWORD` и `ASSIGNMENT_S3_SECRET_KEY` разными случайными паролями. `ASSIGNMENT_S3_ACCESS_KEY` должен отличаться от пользователей root и Education. Для каждого из `ASSIGNMENT_CURSOR_KEY`, `ASSIGNMENT_IDENTITY_SIGNING_KEY` и `ASSIGNMENT_EDUCATION_SIGNING_KEY` отдельно выполните команду и сохраните результат в локальном хранилище секретов или игнорируемом `.env`:

```powershell
[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
```

```powershell
$c = @('--env-file', '.env', '-f', 'Docker/local/compose.yml', '--profile', 'assignment')
docker compose @c config --quiet
docker compose @c up -d --build assignment education
docker compose @c ps -a assignment assignment-postgres assignment-minio-init minio education
docker compose @c logs --tail 40 assignment assignment-minio-init
Invoke-RestMethod http://127.0.0.1:8082/actuator/health/readiness
Invoke-RestMethod http://127.0.0.1:8082/actuator/health
docker compose @c run --rm assignment-minio-init
```

Дождитесь статуса `healthy` у приложения. Init-контейнер завершается с кодом 0. Повторный bootstrap сохраняет файлы и применяет политику доступа к `files/*` внутри приватного bucket. Локальный сервер S3 — используемый Education форк MinIO Silo. У сервисов отдельные учётные записи и buckets. Том `education_minio_data` хранит объекты, `assignment_postgres_data` хранит отдельную PostgreSQL 17. Flyway использует схему `public` БД `assignment`, Hibernate проверяет схему при старте, JDBC работает в UTC.

### Gradle, тесты и IDE

```powershell
Push-Location services/assignment-service
.\gradlew.bat test
.\gradlew.bat bootJar
.\gradlew.bat bootRun --args='--spring.profiles.active=local --server.port=18082'
```

Из второго терминала выполните `Invoke-RestMethod http://127.0.0.1:18082/actuator/health/readiness`. Остановите `bootRun` через Ctrl+C. Для Bash из каталога сервиса:

```bash
chmod +x gradlew
./gradlew test
./gradlew bootJar
./gradlew bootRun --args='--spring.profiles.active=local --server.port=18082'
```

IDE использует JDK 17, класс `tutor.assignmentservice.AssignmentServiceApplication`, профиль `local` и рабочий каталог `services/assignment-service`. Local читает корневой `.env`. На хосте по умолчанию используются порты 5434 (БД), 9000 (S3), 8080 (Identity), 8081 (Education), 8082 (Assignment). В контейнере внутренние адреса задаёт Compose. Профиль `test` получает адреса из Testcontainers и HTTP-заглушек. При обычном production-запуске базовый `application.yaml` требует явные переменные подключения к БД, S3, Identity и Education, ключи и `ASSIGNMENT_JWT_PUBLIC_KEY_LOCATION`; для внутренних HTTP-вызовов используйте TLS или защищённую сеть. Исполняемый JAR — `build/libs/assignment-service.jar`.

| Проба | Смысл |
| --- | --- |
| `/actuator/health/liveness` | Состояние процесса. |
| `/actuator/health/readiness` | Готовность принимать запросы и доступность БД. |
| `/actuator/health` | Также проверяет доступ учётной записи к bucket. |

Отказ S3 виден в общей пробе. Readiness остаётся доступной для текстовых операций. Kafka не участвует в пробах. Тайм-аут S3 на соединение — 2 с, на чтение — 5 с, на запись — 30 с, на вызов — 60 с. Multipart принимает 20 MiB на файл и 22 MiB на запрос. `ObjectStorage.put` закрывает входной поток, `get` передаёт поток вызывающему коду. Случайный ключ резервируется один раз на файл.

## JWT и межсервисный контракт

Assignment следует соглашению Education для публичного API: `Authorization: Bearer <access JWT>`. Cookie Identity и заголовок `X-User-Id` не предоставляют доступ. Cookie-аутентификация и CSRF в самом Identity сохраняются. Identity включает `assignment-service` в audience новых токенов RS256. После обновления провайдера для Assignment нужен вновь выданный токен. Закрытый ключ пользователя остаётся в Identity.

Assignment подписывает внутренние JWT HS256, задаёт issuer `rp-assignment`, subject `assignment-service`, тип `SERVICE`, одного получателя, один scope и срок 300 с. Для каждой пары сервисов используется отдельный ключ. Выдайте Identity только `ASSIGNMENT_IDENTITY_SIGNING_KEY`, Education только `ASSIGNMENT_EDUCATION_SIGNING_KEY`. Для запущенного на хосте Identity переменную нужно передать в окружение приложения; локальный Compose передаёт ключ Education автоматически. Отсутствующий ключ провайдера закрывает его внутренние маршруты. При ротации обновите соответствующую пару сервисов.

| Получатель | Scope | Внутренние маршруты |
| --- | --- | --- |
| Identity | `users:read` | GET `/internal/v1/users/{id}`, POST `/internal/v1/users/batch` |
| Education | `assignment:recipients:resolve` | POST `/internal/v1/assignment-recipients/resolve`, GET `/internal/v1/teachers/{teacherId}/students/{studentId}/membership` |

Каждый защищённый пользовательский запрос сверяется с Identity. Действующие полномочия — пересечение ролей пользователя в JWT и в БД Identity. Блокировка и незавершённый профиль дают 403, исчезнувший пользователь — 401, сбой провайдера или нарушение формата ответа — 503. Вызовы ограничены тайм-аутом соединения 1 с и запроса 3 с. Resolve использует согласованный снимок ACTIVE-курсов, а membership читает актуальный состав. Локальный nginx проксирует только `/api/`. Прежний маршрут Education для material references использует действующую схему доступа до этапа 6.

JUnit-отчёт Assignment доступен в `build/reports/tests/test/index.html`. Тесты поднимают реальные PostgreSQL и S3, дважды запускают тот же bootstrap, проверяют точные байты, запреты доступа, обработку ошибок, JWT и ответы HTTP-провайдеров. Для проверки самих провайдеров служат `EducationContextTest` и `IdentityServiceApplicationTests`. Последний использует отдельную PostgreSQL с БД и пользователем `identity_test` и принимает JDBC URL через `TEST_DATABASE_URL`.


## Справочники и экзамены, этап 3

Миграция V2 добавляет десять таблиц и начальные записи математики и трёх видов экзамена. Структуры и шкалы создаёт ADMIN. Чтение доступно ADMIN, TEACHER и STUDENT после проверки в Identity. Темы деактивируются через DELETE, их исторические ссылки сохраняются.

Для вызовов API используйте новый access JWT Identity с нужной ролью, audience assignment-service и заполненным профилем. Пример PowerShell с токеном, переданным через локальное окружение:

```powershell
$headers = @{ Authorization = "Bearer $env:ASSIGNMENT_ACCESS_TOKEN" }
Invoke-RestMethod http://127.0.0.1:8082/api/v1/subjects -Headers $headers
Invoke-RestMethod http://127.0.0.1:8082/api/v1/exam-types -Headers $headers
Invoke-RestMethod 'http://127.0.0.1:8082/api/v1/topics?subjectId=1&active=true&limit=30' -Headers $headers
$topic = @{ subjectId = 1; name = 'Линейные уравнения' } | ConvertTo-Json
Invoke-RestMethod http://127.0.0.1:8082/api/v1/topics -Method Post -Headers $headers -ContentType application/json -Body $topic
```

Создание экзамена включает structure, создание последующей структуры — expectedStructureVersionId и полную structure. Новая версия шкалы требует expectedVersionId, structureVersionId и полную карту от 0 до суммы баллов структуры. Для EGE_PROFILE передаётся testScore 0..100, для EGE_BASE и OGE — schoolMark 2..5. Обе шкалы монотонные. Примеры полного JSON приведены в REST-контракте.

Структуры и шкалы имеют историю и защищены от устаревших записей блокировкой агрегата. ExamScoreConverter принимает конкретный scaleVersionId. CompatiblePositions вычисляет кандидатов переноса при совпадении номера позиции, максимума, способа проверки и набора тем. Сам перенос задач относится к этапу 5. Шкалы заполняет администратор по проверенным данным.


## Временные изображения, этап 4

Для TEACHER и ADMIN доступны multipart POST `/api/v1/tasks/images`, GET `/api/v1/tasks/images/{id}/content` и DELETE `/api/v1/tasks/images/{id}`. Преподаватель работает со своими временными изображениями, ADMIN может управлять временными изображениями других пользователей. Привязанный файл возвращает 409 на DELETE.

Принимаются JPEG, PNG и WEBP с совпадающими сигнатурой и MIME, до 10 000 000 байт. Сервис проверяет фактический поток и SHA-256 через ограниченный временный файл на диске, затем отправляет эти же байты в приватный S3. Ответ содержит `markdownReference=attachment:<UUID>`, `state=READY` и `expiresAt`. Полный пример PowerShell с access JWT в локальном окружении:

```powershell
$headers = @{ Authorization = "Bearer $env:ASSIGNMENT_ACCESS_TOKEN" }
$upload = Invoke-RestMethod http://127.0.0.1:8082/api/v1/tasks/images -Headers $headers -Method Post -Form @{
    file = Get-Item 'C:/temp/image.png'
}
Invoke-WebRequest "http://127.0.0.1:8082/api/v1/tasks/images/$($upload.id)/content" -Headers $headers -OutFile 'C:/temp/preview.png'
Invoke-WebRequest "http://127.0.0.1:8082/api/v1/tasks/images/$($upload.id)" -Headers $headers -Method Delete
```

Загрузка резервирует метаданные UPLOADING и пятиминутную аренду до PUT. Завершение сверяет token, состояние и сроки. TTL непривязанного файла равен 24 часам. После истечения TTL предпросмотр возвращает 404 даже до прохода worker. DELETE возвращает 204 после перевода в DELETING, физическая очистка идёт асинхронно.

Очистка запускается каждые 10 секунд. Один проход отмечает до 100 истёкших файлов и обрабатывает до 10 задач. Аренда cleanup job равна 120 секундам. Ошибки S3 повторяются с backoff до 300 секунд. SQL-транзакции завершаются до обращений к S3. Финализация проверяет lease token. Незавершённые multipart по точному ключу отменяются перед удалением объекта.

Раз в минуту reconciliation сканирует до 100 объектов под `files/`, сохраняя курсор до следующего прохода. При транспортном сбое чтения страницы выполняется один повтор. Живые метаданные защищают загрузки и готовые файлы. Объекты без метаданных повторно ставятся на удаление. После завершённой очистки сохраняется cleanup job с object_key — долговечная запись об удалении для поздних PUT. Если исход загрузки остаётся неизвестным после сбоя, метаданные остаются DELETING и объект периодически удаляется до подтверждения завершения. Эти записи сохраняются в MVP.

Локальный S3 требует точный ключ для перечисления multipart и возвращает минимальный XML. Адаптер использует SDK для подписи запроса и совместимую проекцию XML. Штатная серверная очистка неучтённых multipart в текущем стенде настроена на срок 24 часа и интервал 6 часов. Для production проверьте эквивалентную политику в выбранном S3.

Планировщик можно отключить аргументом `--assignment.files.cleanup-enabled=false`. Профиль test отключает расписание и вызывает те же worker-методы вручную. Интервалы задаются свойствами `assignment.files.cleanup-delay-ms` и `assignment.files.reconciliation-delay-ms`.
