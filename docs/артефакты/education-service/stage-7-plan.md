# Этап 7 — конфликты и transactional outbox → Kafka

Разрешение «Давай дальше» после завершённого этапа 6. Выполнить только этап 7, затем остановиться перед этапом 8.
Skills ponytail/caveman; Identity не менять. Существующий Kafka apache/kafka:4.1.1 и listeners проверить, не заменять.

- [x] Единый ScheduleConflictService, half-open overlap, teacher + все текущие students, все ACTIVE courses,
  включая weekly/исключения. Ответ Conflict только subjectType/subjectId/startsAt/endsAt, без чужих IDs/названий.
- [x] Подключить confirmConflicts ко всем командам этапов 4–6: enrollment/single create+move/series create+occurrence move.
  false/missing + conflict → 409 SCHEDULE_CONFLICTS и rollback; true вычисляет заново и разрешает запись.
  Редактируемый single/series+originalDate исключается из сравнения с самим собой.
- [x] Новая серия: первые 30 московских дней начиная с max(startDate,today). Зачисление не ограничивать
  произвольными 30 днями: конечный набор окон вокруг weekly boundaries/исключений/фиксированных уроков
  покрывает все будущие weekly-pattern состояния без бесконечной генерации. Возвращать дедуплицированные предупреждения.
- [x] V6 outbox_event: UUID eventId, course aggregate, type, JSONB payload, UTC created/published, partial pending index.
  Запись LessonRescheduled/LessonCancelled/LessonSeriesStopped в той же SQL transaction; причина/старое/новое время.
- [x] ADR-003: внутренний v1 payload/topic/key, явно новая внутренняя схема, не ранее согласованный notification contract.
  Никакого выдуманного consumer. Повторы cancel/stop без нового изменения не создают новые события.
- [x] Отдельный publisher: FOR UPDATE SKIP LOCKED одной pending строки, bounded Kafka send+ACK,
  mark published только после ACK, rollback при отказе; новый процесс повторяет stable eventId.
  Два publisher не портят одну строку. Crash после ACK до commit допускает duplicate с тем же eventId.
- [x] env/profiles/Compose: Kafka bootstrap kafka:29092 в контейнере / localhost:9092 на хосте,
  topic/version/timeouts/poll; test scheduler выключен, реальный Kafka suite не подменять mock.
- [x] Интеграционные HTTP/PG tests conflicts/privacy/confirm/rollback/30day/boundaries/self-edit;
  реальные Kafka/PG tests outage/recovery/restart/2publisher/ACK-gap stable eventId, outbox rollback.
- [x] Обновить прежние tests под действующие confirmations/V6; полный ./gradlew test bootJar,
  Compose config/up --build, readiness, сохранность PG volume, README/артефакты/DoD; остановка перед 8.

Технические решения:
- Конфликт описывает пересекающийся интервал и участника, не идентичность чужого занятия.
- Пересечения разрешены; глобальная сериализация между курсами не обещается. Parent course locks сохраняются.
- Outbox worker может держать lock своей строки на время bounded ACK; HTTP transactions Kafka не вызывают.
- Broker outage не делает PostgreSQL изменение неуспешным: pending событие остаётся и повторяется.
- Временные test scripts только ignored build/. Секреты/JWT не логировать.

После сжатия перечитать этот план, file_map/task_plan/progress/findings и пять контрактов в docs/архитектура/education-service.
