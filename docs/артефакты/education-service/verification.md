# Education Service — отчёт этапа 1

Дата: 20.11.2026, Europe/Minsk. **Этап 1 завершён. Этап 2 не начинался.**

## Реализованное поведение

Создан сервис Java 17 / Spring Boot 4.1.1 / Gradle Kotlin DSL. Wrapper 9.8.2 с SHA-256;
profiles default/local/test; PostgreSQL 17, Flyway и Hibernate validate; UTC в JDBC/Jackson;
Actuator liveness/readiness, graceful shutdown. Публичны только health probes.
Бизнес-маршруты, доменные таблицы, JWT/Identity-интеграция и Kafka/WebSocket не реализованы.

`ObjectStorage` использует upstream MinIO Java SDK 8.6.0, потоковую передачу, безопасные ключи,
проверку метаданных и конфигурации. Повторное удаление отсутствующего объекта успешно;
missing GET диагностируется отдельно от отказа зависимости. Ошибки и readiness не раскрывают
ключи объектов, исходные S3 exceptions и секреты.

В существующий `Docker/local/compose.yml` добавлены Education, отдельная PostgreSQL Education,
S3-хранилище, persistent volumes и идемпотентный bootstrap приватного bucket. Клиент приложения
использует отдельного пользователя с доступом к materials/chat/tmp своего bucket; root MinIO credentials
передаются только хранилищу и bootstrap. Существующие сервисы и сеть сохранены.

**Локальное хранилище — Silo, отдельный форк MinIO.** После реальных Quay 401 решение явно
принято и описано в [ADR-001](ADR-001-local-object-storage.md). Никакой скрытой подмены нет.
Server/client RELEASE.2026-11-12T00-00-00Z скачаны и закреплены digest. Архивный Quay MinIO
устарел и не рекомендован для production. Production-хранилище этим этапом не выбирается.

Dockerfile реально собирает jar в JDK17, runtime JRE17 работает от UID/GID 10001:10001.
Образы JDK/JRE закреплены digest, build context ограничен сервисом, секреты исключены.
README содержит команды для PowerShell/Git Bash/Linux, адреса хоста и контейнеров, диагностику,
restart и предупреждение о разрушительном `down -v`.

## Изменённые файлы

Общие файлы:

- `.gitignore`: исключения локальных env/build/cache/IDE, существующие изменения сохранены.
- `.env.example`: стандартный пример, старый `.env.exmaple` сохранён.
- `Docker/local/compose.yml`: только дополнительные сервисы/volumes Education и S3.
- `Docker/local/minio/bootstrap.sh`, `Docker/local/minio/.gitattributes`.
- Игнорируемый `.env`: недостающие credentials сгенерированы локально без вывода значений; не артефакт для Git.

Новый `services/education-service/`:

- `build.gradle.kts`, `settings.gradle.kts`, `gradlew`, `gradlew.bat`;
- `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`;
- `Dockerfile`, `.dockerignore`, `.gitattributes`, `README.md`;
- `src/main/java/tutor/educationservice/EducationServiceApplication.java`;
- `src/main/java/tutor/educationservice/security/SecurityConfiguration.java`;
- `src/main/java/tutor/educationservice/integration/storage/ObjectStorage.java`;
- `src/main/java/tutor/educationservice/integration/storage/MinioObjectStorage.java`;
- `src/main/java/tutor/educationservice/integration/storage/StorageConfiguration.java`;
- `src/main/java/tutor/educationservice/integration/storage/StorageProperties.java`;
- `src/main/java/tutor/educationservice/integration/storage/StorageException.java`;
- `src/main/java/tutor/educationservice/integration/storage/ObjectKeys.java`;
- `src/main/java/tutor/educationservice/integration/storage/ObjectMetadata.java`;
- `src/main/resources/application.yaml`, `application-local.yaml`, `application-test.yaml`;
- `src/main/resources/db/migration/V1__initialize_schema.sql`;
- `src/test/java/tutor/educationservice/EducationContextTest.java`;
- `src/test/java/tutor/educationservice/EducationInfrastructureTest.java`;
- `src/test/java/tutor/educationservice/integration/storage/StorageUnitTest.java`.

Артефакты в текущем каталоге: `task_plan.md`, `file_map.md`, `findings.md`, `progress.md`,
`ADR-001-local-object-storage.md`, этот отчёт и перечисленные ниже журналы.
Identity Service и контракты не изменялись. Ранее существовавшие staged изменения корневых
README/Dockerfile/nginx и пользовательские изменения документов сохранены.

## Выполненные команды и результаты

Команды Compose выполнялись из корня, Gradle — из `services/education-service`.
`./gradlew` действительно запускался через `C:/Program Files/Git/bin/bash.exe -lc`;
Windows `.\gradlew.bat` также проверен. Внутри Docker выполнялся Linux `./gradlew`.

| Команда / проверка | Фактический результат |
|---|---|
| `java -version` | Хост Java 17.0.12 |
| `./gradlew --version` | Gradle 9.8.2 на Java 17 |
| `docker version`, `docker compose version` | Engine 28.5.2, Compose 2.40.3 |
| `docker pull quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z` | Провал: 401 UNAUTHORIZED |
| `docker pull quay.io/minio/mc:RELEASE.2025-08-13T08-35-41Z` | Провал: 401 UNAUTHORIZED |
| `docker pull pgsty/silo:RELEASE.2026-11-12T00-00-00Z` | Успех, digest проверен |
| `docker pull pgsty/mc:RELEASE.2026-11-12T00-00-00Z` | Успех, digest проверен |
| `docker run ... --version` для Silo/mc | Оба CLI подтвердили выбранный релиз |
| `./gradlew test` с Quay | Провал image pull; 8 тестов прошли, S3 initializationError |
| `./gradlew test --tests '*StorageUnitTest' --tests '*EducationContextTest' bootJar` | BUILD SUCCESSFUL, 8 тестов |
| **`./gradlew test bootJar --console=plain` после ADR и исправления restart-теста** | **BUILD SUCCESSFUL, все 15 тестов прошли** |
| `docker build -t tutor-education:local services/education-service` | Успешная настоящая multi-stage сборка |
| `docker compose --env-file .env -f Docker/local/compose.yml config --quiet` | Успех без печати secrets |
| `docker compose --env-file .env -f Docker/local/compose.yml up -d --build` | Успех на зафиксированном Silo |
| `docker compose --env-file .env -f Docker/local/compose.yml run --rm minio-init` | Повторный bootstrap успешен, exit 0 |
| `curl.exe --fail http://localhost:8081/actuator/health/liveness` | HTTP 200, `{"status":"UP"}` |
| `curl.exe --fail http://localhost:8081/actuator/health/readiness` | HTTP 200, `{"status":"UP"}` |
| `docker exec tutor-platform-local-education-1 id` | UID/GID 10001:10001 |
| Linux `sh -n /scripts/bootstrap.sh` | Успех |
| `git diff --check`, `git diff --exit-code -- services/identity-service` | Успех; Identity не изменён |
| Скан реальных локальных паролей в исходниках и артефактах | Утечек нет; `.env`, build, .gradle игнорируются Git |

## Логи успехов и провалов

- [Полный успешный suite и bootJar](test-full.log).
- [Успешная multi-stage сборка](docker-build.log).
- [Полный успешный compose up --build](compose-up.log).
- [Запуск приложения, Flyway, bootstrap](startup.log).
- [Реальная проверка Compose persistent volume](compose-storage-smoke.log).
- [Итоговое состояние Compose и HTTP probes](final-state.log).
- [Первоначальный test с недоступным Quay](test-quay-failure.log).
- [Первоначальный compose up с Quay 401](compose-quay-failure.log).
- [Полный XML первоначальной S3 ошибки загрузки образа](s3-test-failure.xml).

Ключевые строки окончательного прогона:

```text
EducationInfrastructureTest > restartKeepsObjectAndReadinessRecoversAfterOutage() PASSED
EducationInfrastructureTest > streamsBinaryObjectsWithMetadataAndPrivateAccess() PASSED
EducationInfrastructureTest > unknownLengthUploadUsesMultipart() PASSED
EducationInfrastructureTest > bootstrapCanRunTwice() PASSED
BUILD SUCCESSFUL in 20s
```

Сначала исправлены компиляционные несовпадения пакета LocalServerPort, AssertJ и Docker API.
Первый реальный Silo suite выявил смену случайного опубликованного порта Docker после restart;
отдельный probe подтвердил последовательность 32781 → 32782 → 32783. Restart-тест теперь резервирует
свободный фиксированный host-порт для одного прогона, подобно фиксированному 9000 в Compose.
Отказ не скрыт увеличением ожидания или отключением теста; окончательный suite проходит целиком.

Отдельный smoke на volume настоящего Compose использовал сервисные credentials:
`mc pipe` записал тестовый объект, anonymous HTTP GET дал 403, `docker compose ... restart minio`
перезапустил Silo, `mc cat` подтвердил те же байты, `mc rm` удалил объект. Readiness временно
вернул 503 при восстановлении и затем 200 UP. Первый вариант smoke ошибочно ожидал успешного
повторного `mc rm` отсутствующего объекта; CLI возвращает ошибку. Это не контракт ObjectStorage:
его повторный DELETE и `exists=false` отдельно подтверждены SDK integration test.
Все smoke-объекты удалены, диагностический контейнер и Testcontainers-контейнеры убраны.

## DoD этапа 1

- [x] Java 17 + Boot 4.1.1 + Gradle Kotlin DSL + воспроизводимый wrapper.
- [x] Проверены реальные пути, сеть, существующие контейнеры и доступность образов.
- [x] PostgreSQL/Flyway/Actuator/profiles/env, UTC, ddl-auto=validate; V1 применена к чистой БД.
- [x] Приватный S3 bucket, отдельные credentials, persistent volume, идемпотентный bootstrap.
- [x] Silo явно зафиксирован как local-форк, upstream Quay провал и устаревание описаны.
- [x] ObjectStorage + SDK, безопасные ключи/метаданные, потоковая передача и обработка ошибок.
- [x] Реальные S3 PUT/GET/STAT/DELETE, multipart, privacy, missing object, bad credentials, downtime и restart.
- [x] Реальные PostgreSQL/context/HTTP-тесты; негативная конфигурация не раскрывает secrets.
- [x] Все 15 тестов, bootJar, image build, compose config, полный up --build фактически успешны.
- [x] Readiness/liveness UP, graceful shutdown проверен, runtime non-root.
- [x] README, env example, Dockerfile, .dockerignore, карта файлов и журнал актуальны.
- [x] Identity не изменён; бизнес-маршрутов и бизнес-таблиц нет.

## Ограничения

Проверен локальный одноузловой Silo, не upstream MinIO и не production-кластер. Production TLS,
backups, распределённая отказоустойчивость, обновление/миграция существующих S3 данных и этапы 2+
не входят в этот результат. Host bootRun отдельно не запускался; запуск контекста на хосте покрыт
SpringBootTest, полный сервис реально запущен в Docker. Стабильный тестовый порт выбирается из
свободных портов ОС; редкая гонка с чужим процессом проявится явной ошибкой bind.

Local stack оставлен запущенным. Следующий этап — только после разрешения пользователя.
