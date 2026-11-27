# Этап 3 — курсы: создание, чтение, список, изменение

Разрешение: «Давай тогда третий этап». Выполнить только этап 3, затем отчёт и остановка.
Identity не менять; существующий lookup использовать для admin create. Не добавлять зачисления,
удаление курса, расписание, материалы или чат. Стиль ponytail/caveman сохраняется.

## Подзадачи и тесты

- [x] Flyway V2: course, states/defaults/checks, UTC/timestamptz, индексы, без FK Identity и unique title.
  Проверить реальную PostgreSQL, Hibernate validate, чистую миграцию и upgrade с V1.
- [x] POST /api/v1/courses: JWT owner, ADMIN teacherId через реальный Identity lookup,
  только существующий TEACHER; 201+Location, без обязательного Idempotency-Key, одинаковые title разрешены.
  Тесты teacher/student/admin/multiple roles, bad teacherId, Identity 503, пустой title, отсутствие частичной записи.
- [x] GET/PATCH /courses/{id}: только active и owner/admin, чужие/DELETING → 404,
  student PATCH → 403, PATCH меняет только title. Reject unknown fields для request DTO.
  Проверить read-only поля, отсутствие информации о чужом ресурсе и гонку PATCH с переходом DELETING.
- [x] Список: scope обязателен, OWNED/ALL permissions и teacherId filter, ACTIVE only,
  limit 30/max100, keyset createdAt DESC/id DESC, opaque scope-bound cursor, limit+1.
  Проверить равные timestamps, страницы без дублей, удалённый anchor, разные пользователи/filters/scope.
- [x] Явно пометить зависимость ENROLLED и student reads от этапа 4: ENROLLED → 503 ENROLLMENT_UNAVAILABLE,
  а не фиктивный пустой список; курс без текущего членства не раскрывается ученику.
- [x] Полный test/bootJar, image/Compose upgrade/readiness, README, карта файлов, логи и DoD.

Итог: 81 тест Education прошёл; jar/image/config/up/readiness успешны. На существующем контейнере/volume применена V2.
Этап 3 завершён. Этап 4 не начинать без разрешения пользователя.

## Технические решения в рамках контракта

- ADMIN+TEACHER без teacherId создаёт свой курс как TEACHER. ADMIN без TEACHER обязан указать teacherId (422).
- Обычный TEACHER не передаёт teacherId ни для create, ни для выбора чужого OWNED; такие попытки → 403.
- ADMIN OWNED с чужим owner требует teacherId; ALL принимает необязательный teacherId.
- Чужой GET/PATCH владельца скрывается 404; роли без права изменения получают 403 независимо от существования UUID.
- Admin target проверяется на существование и роль TEACHER; запрет создавать для blocked target контрактом не установлен,
  поэтому не вводится. Блокировка вызывающего по-прежнему закрывает доступ через IdentityAccessFilter.
- Identity HTTP lookup проходит до SQL transaction; PATCH берёт row lock и повторно проверяет ACTIVE под ним.
- createdAt обрезается до микросекунд PostgreSQL, чтобы POST/GET/cursors видели одинаковый timestamp.

После сжатия перечитать file_map.md, этот план, progress.md и актуальные артефакты.
