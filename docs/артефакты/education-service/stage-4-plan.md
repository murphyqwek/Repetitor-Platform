# Этап 4 — зачисления и права ученика

Разрешение: «Давай тогда дальше» после завершения этапа 3. Выполнить только этап 4, затем остановка.
Identity, S3, расписание и чат не расширять. Проверка конфликтов расписания относится к этапу 7.

- [x] Flyway V3: course_student, PK course/student, FK только course, default can_send_messages=true,
  UTC enrolled_at и индекс student/course; без истории членства и без FK Identity.
- [x] POST students: owner/admin, реальный Identity lookup, STUDENT/blocked/404/503,
  201 Location+StudentDto, 409 ALREADY_ENROLLED/STUDENT_BLOCKED. Два POST → одна запись.
- [x] DELETE students/{id}: физическое удаление актуальной связи, 204; повтор безопасен.
  Повторное зачисление создаёт новый enrolled_at, can_send_messages=true.
- [x] PATCH chat-permissions: owner/admin, обязательный Boolean; 200 studentId/canSendMessages,
  существующее членство; запрет отправки не закрывает чтение.
- [x] GET students: owner/admin, scope-bound keyset enrolledAt DESC/studentId DESC, 30/max100,
  актуальные ФИО/blocked из Identity без сохранения копий; Identity failure → 503, связь не удаляется.
- [x] ENROLLED и student GET course: текущее членство + ACTIVE; исключение сразу закрывает новые чтения.
  Проверить multi-role Teacher+Student: членство не даёт прав изменять чужой курс или roster.
- [x] Без длительной SQL transaction на HTTP Identity. Короткие mutations берут lock course и повторно
  проверяют ACTIVE; PK/ON CONFLICT защищают duplicate. Race с DELETING не коммитит enrollment.
- [x] PostgreSQL/HTTP/JWT позитивные, негативные и конкурентные тесты; Flyway upgrade V2→V3.
- [x] Полные test/bootJar, image/Compose/config/readiness; README, карта файлов и DoD.

Принятые детали в рамках контракта:
- DELETE отсутствующего члена активного доступного курса — повторяемый 204; PATCH отсутствующего — 404.
- Non-STUDENT target — 422 INVALID_STUDENT. blocked target при новом enrollment — 409 STUDENT_BLOCKED.
- confirmConflicts принимается как согласованное поле; проверка расписания не реализуется до этапа 7.
- GET roster читает SQL страницу, закрывает SQL transaction, затем получает актуальные профили;
  если профиль существующего члена недоступен/исчез — 503, без удаления членства или выдуманных данных.
- Все mutations сериализуются lock родительского course; HTTP profile checks проходят до этого lock.

После сжатия перечитать этот план, file_map.md, progress.md и остальные актуальные артефакты.

Завершено 21.11.2026: 95 tests, bootJar, Compose/config/image, PostgreSQL V3 и probes успешны. Итог: stage-4-verification.md. Этап 5 не начинался.
