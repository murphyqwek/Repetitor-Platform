# Проверка этапов 1–2 Assignment Service

Дата проверки — 28 ноября 2026 года, Europe/Minsk.

## Реализовано

Этап 1 завершён. Сервис Java 17 / Boot 4.1.1 / Gradle Kotlin DSL запускается через Gradle и multi-stage Dockerfile JDK17/JRE17. Имеются отдельная PostgreSQL 17 с persistent volume, приватный bucket и отдельные credentials приложения. Bootstrap проверен дважды. S3 SDK скрыт за ObjectStorage, потоковый round-trip проверяет точные байты, conditional PUT отклоняет повторную запись, удаление идемпотентно. Доступ анонимного клиента и учётной записи Assignment к Education bucket запрещён.

Этап 2 реализован. Пользовательские RS256 JWT проверяются по подписи, issuer, audience, exp, iat и ролям. Каждый запрос сверяется с Identity без кэша, полномочия ограничиваются пересечением ролей. Реализованы UserDto и batch с проверкой полной классификации запрошенных ID, Education resolve и membership с согласованным снимком ACTIVE-курсов. Добавлены ProblemDetail, обработка malformed JSON/UUID и валидации, cursor с привязкой к actor/resource/sort/filter, Clock и безопасный streaming response helper.

В Identity минимально добавлены provider users GET/batch, отдельная security chain и audience выдаваемых JWT. В Education добавлены provider resolve/membership и отдельная security chain. Миграции обоих провайдеров сохраняются. Новые доменные таблицы Assignment относятся к следующим этапам.

## Принятые решения

- Assignment принимает Bearer header по фактическому соглашению Education. Identity продолжает использовать cookie и CSRF на пользовательских маршрутах. Старому токену Identity потребуется перевыпуск с audience.
- Внутренние JWT подписываются HS256, каждый получатель имеет отдельный 32-байтовый ключ, issuer rp-assignment, subject assignment-service, tokenType SERVICE, один audience и scope, TTL 300 с. Это реализация разрешённой контрактом отдельной сервисной подписи. Материальные references используют существующую авторизацию Education до этапа 6.
- HTTP connect timeout 1 с, общий timeout 3 с, автоматических повторов нет. Некорректные DTO и отказ провайдера дают 503.
- Readiness включает БД. Общий health дополнительно проверяет bucket, liveness проверяет процесс.
- Существующий том содержал историю прежней отменённой реализации в схеме assignment. Явно настроена public, соответствующая закоммиченной V1 текущей ветки. Историческая схема сохранена. SQL repair и удаление томов не выполнялись.
- В игнорируемый .env добавлены три независимых случайных ключа, их значения в артефакты не включены.

## Выполненные команды

Из services/assignment-service через Git Bash выполнена команда `./gradlew test bootJar`. Итоговый результат — BUILD SUCCESSFUL, 8 тестов, 0 failures и 0 skipped.

В services/identity-service выполнена `./gradlew.bat test` с TEST_DATABASE_URL, указывающим на одноразовую PostgreSQL identity_test на автоматически выбранном порту. Итог — BUILD SUCCESSFUL, 44 теста.

В services/education-service выполнена `./gradlew test --tests tutor.educationservice.EducationContextTest`. Все 7 тестов прошли. Дополнительно запущен полный регрессионный набор Education, итог сохранён в stages-1-2-tests.log.

Из корня репозитория выполнены команды:

```powershell
$c = @('--env-file', '.env', '-f', 'Docker/local/compose.yml', '--profile', 'assignment')
docker compose @c config --quiet
docker compose @c up -d --build assignment education
docker compose @c run --rm assignment-minio-init
docker compose @c ps -a assignment assignment-postgres assignment-minio-init education
Invoke-RestMethod http://127.0.0.1:8082/actuator/health/readiness
Invoke-RestMethod http://127.0.0.1:8082/actuator/health
```

Compose config и build/up завершились с кодом 0. Assignment и Education healthy, bootstrap Exited (0), обе HTTP-пробы Assignment — UP.

Из каталога сервиса выполнена `./gradlew.bat bootRun --args='--spring.profiles.active=local --server.port=18082'`. Readiness на 18082 вернула UP, после проверки процесс остановлен Ctrl+C. Проверка `git diff --check` прошла.

## Исправленные провалы

| Наблюдение | Исправление и результат |
| --- | --- |
| DockerDesktopLinuxEngine pipe отсутствовал | Запущен Docker Desktop, контейнеры доступны. |
| Первый Gradle запуск совпал с незавершённым редактированием security | После завершения файлов выполнен повторный полный build/test. |
| Тестовый controller не попал в контекст, HTTP 404 | Явный Import только в тестовом контексте, HTTP-проверки прошли. |
| Identity использовал общий roles converter для service JWT, HTTP 403 | В service chain явно задан converter scope, provider tests прошли. |
| Фикстура exp предшествовала iat, encoder отклонял токен | Исправлена фикстура, сервер вернул ожидаемый 401. |
| Существующий том содержал другую V1 | Указана public, Flyway применил текущую V1 без изменения старой истории. |
| В образе mc отсутствует grep | Возвращён действующий bootstrap с повторным policy attach, повторный запуск прошёл. Старое сообщение сохранено в startup.log. |
| Порт 55439 запрещён Windows | PostgreSQL тестов запущена с динамическим портом. |

## Артефакты и границы проверки

Полный перечень изменённых файлов — stages-1-2-files.log. Итоги JUnit — stages-1-2-tests.log. Логи контейнера — startup.log. Команды повторения и env — services/assignment-service/README.md.

T01.09 и storage smoke T04.11/12 проверены в границах этапа 1. T01.03–05/07/08 и T05.01–04 проверены в границах security/client/provider этапа 2. Проверки ownership будущих task/submission из T01.06 будут выполняться вместе с соответствующим бизнес-API. Provider tests используют реальную БД и подписанные JWT, клиентские тесты используют HTTP-заглушки. Сквозной запуск всех трёх приложений с пользовательским workflow относится к следующим этапам.

Коммиты и push в этой работе не выполнялись.
