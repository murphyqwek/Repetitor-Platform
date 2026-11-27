# Этап 7 завершён — 21 ноября 2026

## DoD

- API проверяет интервалы преподавателя и учеников при зачислении, создании и переносе. Проверены виртуальные вхождения, переносы из остановленных серий, граница интервалов и исключение редактируемого вхождения. Новая серия проверяется на 30 московских дней.
- При пересечении ответ 409 содержит участника и время. Без подтверждения изменения расписания и outbox откатываются. Подтверждённая команда повторяет проверку и сохраняет изменение.
- V6 сохраняет три вида событий расписания в той же транзакции. Отказ INSERT события откатывает изменение урока. Внутренняя схема, Kafka key и версия закреплены в [ADR-003](ADR-003-schedule-events-v1.md).
- На изолированных Kafka 4.1.1 и PostgreSQL 17 проверены недоступность брокера, pending до восстановления, повтор новым publisher/producer, параллельная обработка двумя publisher и пустой topic после отката INSERT. Откат после настоящего ACK и повтор дали два сообщения с одинаковыми eventId и payload.
- Все 145 тестов прошли, пропусков и ошибок 0. bootJar собран. Compose применил V6. Readiness и liveness вернули HTTP 200 UP.

## Команды

Из services/education-service:

```sh
./gradlew test bootJar --console=plain --max-workers=1
```

Из корня репозитория:

```powershell
docker compose --env-file .env -f Docker/local/compose.yml config --quiet
docker compose --env-file .env -f Docker/local/compose.yml up -d --build
Invoke-RestMethod http://localhost:8081/actuator/health/readiness
Invoke-RestMethod http://localhost:8081/actuator/health/liveness
```

Первый прогон конфликтов выявил ошибку в проверке структуры ответа и сдвиг SQL TIME при чтении времени серии вне UTC. Исправлены assertion и JDBC-маппинг LocalTime. После исправления прошли все 13 HTTP/PostgreSQL тестов конфликтов и 4 интеграционных теста Kafka.

Первый Compose up остановился на занятом is-lab-database порту 5433. В Compose добавлен EDUCATION_DB_HOST_PORT с прежним default 5433. В локальном .env порт Education изменён на 15433. При повторном up Compose пересоздал контейнер PostgreSQL с сохранением тома tutor-platform-local_education_postgres_data. Flyway history содержит V1–V6.

Изменённые компоненты перечислены в [карте файлов](file_map.md). Полный [лог Gradle](stage-7-test.log), [первый неуспешный прогон конфликтов](stage-7-conflict-test.log), [ошибка порта](stage-7-compose.log), [успешный Compose](stage-7-compose-retry.log), [том, миграции и пробы](stage-7-final-state.log).
