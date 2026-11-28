# Online Lesson Service — комплект спецификаций Tutor Platform

Редакция 28.11.2026. Все девять блоков согласованы пользователем. Документы предназначены для реализации с Codex, не являются отчётом о готовом коде. Сложные технические defaults уточнены при сборке и явно отмечены ниже/в требованиях.

## Порядок чтения
1. [Требования](online_lesson_service_requirements.md)
2. [Архитектура](online_lesson_service_architecture.md)
3. [Модель данных](online_lesson_service_db_design.md)
4. [Публичный REST](online_lesson_service_rest_api_contract.md)
5. [WebSocket/STOMP и доска](online_lesson_service_websocket_contract.md)
6. [LiveKit](online_lesson_service_livekit_contract.md)
7. [Внутренние API](online_lesson_service_internal_api_contract.md)
8. [Kafka](online_lesson_service_kafka_contract.md)
9. [Тест-план](online_lesson_service_test_plan.md)
10. [Пошаговая реализация для Codex](online_lesson_service_codex_implementation_plan.md)

Поместить комплект в `docs/online-lesson/`; приложить существующие спецификации Identity, Education, Assignment. Перед каждым этапом Codex читает весь комплект и актуальный код, выполняет один этап, тесты и отчёт. Источники: исходный `требования(5).md` и последующие согласованные решения; Education requirements/db/rest/chat, Assignment internal/Kafka/test/implementation contracts из приложенных файлов. Последние согласованные решения имеют приоритет над первоначальным черновиком (например ручная ссылка Телемоста, отдельный чат и временная доска). Не переписывать чужие документы без явного описания изменений.

## Изменения смежных сервисов
Education: `meeting_url` уже есть; расширить PATCH, убрать требование автоинтеграции Яндекса; cap8 студентов под lock; resolve/membership API; execution guard + release tombstone; outbox removal/deletion, согласовать cancel/reschedule/stop. Identity: reuse single/batch, service scopes, transactional block/unblock events. Assignment напрямую не меняем. Notification/Metrics будущие потребители.

## Технические уточнения сборки и ограничения
- 11 SQL таблиц Online Lesson, 3 Mongo коллекции, 28 публичных маршрутов. Provider tables отдельно. START_FAILED требует завершённой компенсации до повторного запуска; guard tombstone, no auto TTL. Activation recheck end window. Смена поколения при отзыве media прав с закрытием старой комнаты до выдачи новой; при отказе LiveKit нет гарантии физического отключения, UI fallback.
- История назначений сохраняется даже при замене учеников, одновременно eligible ≤8. Неизвестное присутствие хранится отдельно, метрики только наблюдаемого присутствия платформы, не Телемоста. При удалении курса закрываем урок и сохраняем историю. Технические интервалы/квоты, schema DTO, retention tombstones, source revisions, admin slot expiry, cap receipts500k, revision heartbeat, safe ID/board metadata CAS и handling of unknown commits — предложенные инженерные defaults для проверяемой реализации, не новые пользовательские функции.
- Код репозитория не проверен, интеграционные тесты не запускались. Версии SDK/Docker/React wrapper, Excalidraw math/geometry/undo, LiveKit ICE/TURN/old-token behavior, Mongo transactions, provider auth/real routes и end-to-end p95 нужно проверить в этапе 0/последующих этапах. Не считать 500ms гарантией для всех сетей. Обязательное функциональное ТЗ не урезать молча при проблеме PoC. Бэкенд и фронтенд реализуются отдельно, синхронные контракты обязательны.
- Документация внешних компонентов для проверки: https://docs.livekit.io/frontends/reference/tokens-grants/ ; https://docs.livekit.io/reference/other/roomservice-api/ ; https://docs.livekit.io/intro/basics/rooms-participants-tracks/webhooks-events/ ; https://docs.excalidraw.com/ ; https://mozilla.github.io/pdf.js/ ; https://www.mongodb.com/docs/manual/core/transactions/ . Ограничение self-hosted LiveKit на отзыв существующего токена подтверждено документацией; реальные конфиги и SDK проверять на закреплённой версии.

Не входят в MVP: отдельные микросервисы чата/доски, Redis для Online Lesson, горизонтальное масштабирование, CRDT/offline, запись видео, хранение/экспорт доски после урока, интеграция API Телемоста, Notification/Metrics реализация.
