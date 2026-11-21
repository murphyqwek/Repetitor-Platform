# Этап 6 — бессрочные серии, исключения, остановка

Разрешение: «Дальше» после закрытого этапа 5. Выполнить только этап 6 и остановиться перед этапом 7.
Identity/S3/Compose topology не менять; конфликтов/outbox/Kafka пока нет. Стиль ponytail/caveman и обычный Java.

- [x] V5 lesson_series: course FK, Moscow start_date/time, smallint[] уникальных ISO weekdays,
  duration 5–300, nullable stop_from_date, created_at. lesson_exception: FK series, UNIQUE series/original_date,
  CANCELLED/RESCHEDULED checks, nullable new times/reason, UTC created_at и индексы.
- [x] POST/GET series owner/admin: обязательные startDate/weekdays/startTime/durationMinutes,
  strict input, canonical sorted weekdays, 201 DTO/Location, список включая остановленные;
  общий Page items/nextCursor/hasMore, default30/max100, actor/course-bound keyset createdAt DESC/id DESC.
- [x] POST series/stop: дата inclusive по originalDate; повтор той же даты 200.
- [x] POST occurrences/{originalDate}/reschedule: существующая исходная дата правила, stable identity,
  upsert одной exception под course lock, точные интервалы, reason, 200 rescheduled DTO.
- [x] POST occurrences/{originalDate}/cancel: 204, постоянная CANCELLED exception, повтор 204,
  дальнейший reschedule → 409 LESSON_CANCELLED; не восстанавливать.
- [x] Calendar course/me смешивает SINGLE и виртуальные RECURRING, реальные startsAt sorting,
  переносы из других месяцев, исключение оригинала/отменённых, пересечение границ месяца,
  originalDate < stopFromDate остаётся независимо от фактической новой даты.
- [x] Генерация ограничена запрошенным месяцем (+ предыдущий день для crossing interval),
  без генерации строк на каждый урок/сканирования лет. Остановленные серии учитываются для прежних переносов.
- [x] HTTP/PostgreSQL/JWT и timezone tests: weekdays, границы/годы/leap, отмены/повторные переносы,
  inclusive stop, 15→25 декабря/stop20, future original moved backwards, права/current membership,
  parent lock/DELETING/concurrency, V4→V5 без потери данных/SQL constraints.
- [x] Полный test/bootJar, Compose config/up --build, реальный V5/probes с сохранением PG volume;
  README, file_map/task_plan/progress/findings, stage-6-verification и DoD.

Детали в рамках минимального API:
- startDate <= originalDate и ISO weekday должны соответствовать правилу; иначе 404 NOT_FOUND.
- originalDate >= stopFromDate не изменяется: 409 SERIES_STOPPED.
- stop повторно с другой датой — 409 SERIES_ALREADY_STOPPED; отдельного возобновления/изменения серии нет.
- stop до startDate допускается: правило не порождает вхождений. Восстановление отменённых не добавлять.
- Reason сохраняется в exception как предусмотрено DB; публикация событий/конфликтов остаётся этапу 7.
- Месяц обязателен; для SINGLE сохраняются поведение/DTO этапа 5. Для RECURRING stable seriesId+originalDate,
  singleLessonId=null; rescheduled=true только для перенесённых вхождений.

После сжатия перечитать этот план, task_plan/file_map/progress/findings и контракты в docs/архитектура/education-service.

Завершено 21.11.2026: 128 tests без ошибок/пропусков; bootJar/Compose/config/image/V5/probes успешны. Итог stage-6-verification.md. Этап 7 не начинался.
