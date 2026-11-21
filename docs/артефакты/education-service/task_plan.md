# Education Service — план и состояние

## Границы

Этапы 1–6 завершены. Пользователь разрешил этап 7: «Давай дальше».
Текущий план: `stage-7-plan.md`; последний итог: `stage-6-verification.md`.
Выполнить только конфликты/outbox/Kafka/V6 этапа 7. Identity не менять.
Пользователь сначала добавил этап 8, затем отменил: «Хотя нет, заверши сначала этот этап».
После проверок и отчёта этапа 7 остановиться. Этап 8 не начинать.
Стиль: Java по образцу Identity AuthController, без искусственных переносов каждого аргумента и скобок. Активны caveman и ponytail.
Навык planning-with-files отсутствует в доступном каталоге; используется обычный файловый план.

## План

- [x] Прочитать пять контрактов и план, проверить layout, Java, wrapper, сеть и образы.
- [x] Создать Java 17 / Boot 4.1.1 / Gradle Kotlin DSL приложение, profiles, PostgreSQL/Flyway/Actuator.
- [x] ObjectStorage, MinIO SDK, безопасные ключи и метаданные, конфигурация и readiness.
- [x] Local Compose: Silo (явная замена upstream MinIO по ADR-001), volume, приватный bucket, bootstrap и Education.
- [x] Multi-stage Dockerfile, .dockerignore, env example и README.
- [x] Реальные PostgreSQL/S3 integration tests, PUT/GET/DELETE, privacy, restart; unit/health/config tests.
- [x] Запустить test, bootJar, compose config/up, readiness; записать реальные результаты и DoD.

Итог: 15 тестов проходят; bootJar и полный Compose up успешны, liveness/readiness HTTP 200.
S3 проверен также отдельно на persistent volume настоящего Compose. Подробности: `verification.md`.
Этап 2 завершён полностью: lookup Identity согласован, реализован и подключён.
64 теста Education и 43 Identity прошли; bootJar обоих сервисов и Docker/Compose проверены.
Этап 3 завершён: 81 тест, bootJar/image/Compose/probes, безопасный upgrade V1 → V2.
ENROLLED и student membership reads зависят от этапа 4 и честно помечены частичными.
Этап 4 завершён: 95 тестов без ошибок/пропусков, bootJar, Docker image/Compose/config/probes успешны.
V3 применена к прежнему PostgreSQL container/volume, данные не пересоздавались.
Текущее членство управляет чтением/ENROLLED; управление остаётся только owner/admin.
Конфликты расписания ещё не проверяются (этап 7).
Этап 5 завершён: V4/SINGLE/calendar, 111 тестов, bootJar/Docker/config/Compose/probes успешны.
PostgreSQL container и volume сохранены. Этап 6 завершён: V5/series/exception/calendar,
128 tests/bootJar/Docker/config/Compose/probes успешны. Конфликты/outbox ещё не реализованы.
Этап 7 начат по новому разрешению. Production conflict checker/outbox/config созданы, compileJava прошёл.
Регрессионный suite запущен; новые conflicts/Kafka tests ещё предстоят. Не объявлять этап завершённым.

## Восстановление после сжатия

Перечитать этот файл, `file_map.md`, `findings.md`, `progress.md` и остальные артефакты в этом каталоге.
Проверить `git status`, фоновые процессы и актуальные исходники до продолжения. Не повторять сделанную работу вслепую.

## Завершение этапа 7

145 тестов без пропусков и ошибок, bootJar собран, Compose config/up успешны.
V6 на прежнем томе. Readiness и liveness HTTP 200 UP. Локально Education PG доступен на 15433.
Текущий итог — stage-7-verification.md. Остановка перед этапом 8.
