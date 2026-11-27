# ADR-003 — внутренние Schedule Events v1 и публикация outbox

Дата: 21.11.2026. Статус: принято как внутренняя схема Education в рамках разрешённого этапа 7.
Это новая внутренняя v1, а не ранее согласованный контракт уведомлений. Внешний consumer не реализован.

Topic: education.schedule.v1 (EDUCATION_SCHEDULE_TOPIC). Kafka key: courseId в canonical UUID string.
Payload UTF-8 JSON; все абсолютные времена ISO UTC, даты originalDate/stopFromDate — Moscow local date.

Общие поля:

| Поле | Тип / правило |
|---|---|
| eventId | UUID, совпадает с outbox_event.id, стабилен при любой повторной доставке |
| schemaVersion | integer 1 |
| type | LessonRescheduled, LessonCancelled или LessonSeriesStopped |
| occurredAt | UTC timestamp, момент записи изменения в PostgreSQL |
| courseId | UUID агрегата, совпадает с outbox aggregate_id и Kafka key |
| lesson | {kind,singleLessonId,seriesId,originalDate} |
| oldStartsAt, oldEndsAt | UTC timestamps текущего вхождения до изменения; null для остановки всей серии |
| newStartsAt, newEndsAt | UTC timestamps после переноса; null для отмены/остановки |
| stopFromDate | Moscow local date только для LessonSeriesStopped, иначе null |
| reason | необязательная строка причины переноса/отмены, иначе null |

SINGLE identity: kind=SINGLE, singleLessonId задан, seriesId/originalDate=null.
RECURRING occurrence: kind=RECURRING, singleLessonId=null, seriesId и originalDate заданы.
Остановка всей серии: kind=RECURRING, seriesId задан, singleLessonId/originalDate=null.
Перенос после предыдущего переноса использует действительные старые времена, сохраняя исходную identity.
Отмена ранее перенесённого вхождения также содержит действительные старые времена.
Повтор успешного cancel/stop без изменения не создаёт новое событие. Каждый успешный PATCH/reschedule
является командой переноса и создаёт новое событие, даже при совпадающем времени; Idempotency-Key не требуется.
Создание урока/серии и зачисление не создают событий этих трёх типов.

Обновление расписания и insert outbox атомарны в одной PostgreSQL transaction. FK к удаляемому уроку/курсу
у outbox нет: событие остаётся после физической отмены/будущей очистки агрегата.
Publisher захватывает одну pending строку FOR UPDATE SKIP LOCKED, посылает payload с acks=all,
ожидает bounded ACK и только затем выставляет published_at. Отказ откатывает изменение строки.
Второй publisher пропускает захваченную строку; глобальный порядок между разными publisher не обещается.
Падение после ACK до PostgreSQL commit приводит к повторной доставке с тем же eventId.
Kafka producer idempotence не заменяет consumer deduplication между перезапусками: доставка at least once.

Topic для local создаётся Kafka при первой публикации (существующий broker разрешает auto-create).
Для окружений с запретом auto-create topic нужно создать инфраструктурой до публикации.
Отказ Kafka не откатывает уже зафиксированное HTTP изменение: событие остаётся pending.
Readiness PostgreSQL/S3 не обещает готовности Kafka; publisher восстанавливается повторными попытками.
Контракт не содержит ФИО, JWT/credentials, названий чужих курсов или списка учеников.

Миграция схемы Kafka для внешней интеграции требует нового согласования/версии. Notification consumer,
контракты Assignment и гарантия exactly once этим ADR не вводятся.
