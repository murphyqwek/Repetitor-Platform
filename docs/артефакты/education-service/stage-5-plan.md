# Этап 5 — разовые занятия и месячный календарь

Разрешение: «Давай слеедующий этап» после закрытого этапа 4. Выполнить только этап 5, затем остановиться.
Активны ponytail/caveman; Java по образцу AuthController, без искусственных переносов.

- [x] V4 single_lesson: UUID PK, FK course, starts/ends/created timestamptz, SQL CHECK 5–300 минут,
  ends>starts, индекс course/starts; без status/reason/history колонок.
- [x] POST lessons: owner/admin, OffsetDateTime input, UTC output, 201 + SINGLE calendar DTO,
  5/300 включительно, строгая проверка времени, необязательный confirmConflicts.
- [x] PATCH lesson: изменить только starts/ends одного существующего урока данного курса, 200;
  reason/confirmConflicts принимаются, outbox и checker ещё не подключены.
- [x] POST lesson/cancel: физическое удаление, 204; нет восстановления и отдельного статуса.
- [x] Все mutations под existing parent course lock + ACTIVE, другие teacher/member не управляют.
- [x] CalendarService и TimeInterval: обязательный строгий month=YYYY-MM, Europe/Moscow,
  полуоткрытый месяц [start,end), interval overlap starts<monthEnd AND ends>monthStart;
  сортировка startsAt/id, полные времена без обрезки на границе месяца.
- [x] GET course schedule: owner/admin/current student; GET me/schedule: union собственных TEACHER
  и текущих STUDENT memberships, без дублей, без глобального ADMIN доступа в персональном календаре.
- [x] Настоящие HTTP/JWT/PostgreSQL tests: длительности/offset/месяцы/границы/leap year/timezones,
  create/move/cancel/permissions/DELETING/current membership/PK-FK/upgrade V3→V4/idempotency header.
- [x] Полный test/bootJar, Compose config/up --build, V4 в прежнем PG volume, readiness;
  README, карта файлов, прогресс, финальный stage-5-verification.md и DoD.

Решения в рамках этапа:
- Отмена отсутствующего или уже отменённого single lesson — 404; восстановление и tombstone не добавляются.
- PATCH требует оба времени; урок ищется по lessonId+courseId, перенос между курсами невозможен.
- rescheduled=false для SINGLE: хранится только актуальное время, история переносов не вводится.
- month обязателен; нет подстановки месяца из timezone JVM/Clock; поддержан канонический YYYY-MM (год 0001–9999).
- Длительность сравнивается точно, без округления к целым минутам; время хранения нормализуется до микросекунд PostgreSQL.
- reason пока не сохраняется/не публикуется: transactional outbox с прежними значениями подключается в этапе 7.
  Не объявлять события и conflict-checker готовыми. Серии — этап 6, их таблицы не создавать.
- Identity, Compose topology, S3 и чужие staged изменения не менять. Новых dependency не требуется.

После сжатия перечитать task_plan/file_map/progress/findings, этот план и контракты в docs/архитектура/education-service.

Завершено 21.11.2026: 111 tests без ошибок/пропусков; bootJar, Compose/config/image, V4 и probes успешны. Итог: stage-5-verification.md. Этап 6 не начинался.
