# Этап 3 — справочники, структуры и шкалы

Дата проверки — 28 ноября 2026 года, Europe/Minsk. Этап завершён.

## Изменённые файлы

В services/assignment-service добавлены:

- src/main/resources/db/migration/V2__exam_catalog.sql
- src/main/java/tutor/assignmentservice/exam/CatalogDtos.java
- src/main/java/tutor/assignmentservice/exam/ExamCatalog.java
- src/main/java/tutor/assignmentservice/exam/ExamCatalogController.java
- src/main/java/tutor/assignmentservice/exam/ExamScoreConverter.java
- src/main/java/tutor/assignmentservice/exam/CompatiblePositions.java

Обновлены README.md, common/pagination/CursorCodec.java, common/error/ApiExceptionHandler.java и storage/AssignmentInfrastructureTest.java в соответствующих src/main/java и src/test/java. Все изменения предыдущих этапов сохранены в рабочем дереве.

## Выполненные критерии

- V2 создаёт десять таблиц, FK, составные ограничения принадлежности текущей версии, ограничения диапазонов и индексы. SQL guards проверяют принадлежность структуры шкалы экзамену и совпадение предметов позиции и темы.
- Seed содержит только математику и EGE_PROFILE/EGE_BASE/OGE.
- Реализованы все маршруты §3 публичного REST. ADMIN создаёт и изменяет справочники, разрешённые роли читают. Темы переименовываются и деактивируются.
- Структуры создаются атомарно, содержат последовательные позиции и активные темы того же предмета. Изменение создаёт новую версию с expectedStructureVersionId.
- Шкалы покрывают 0..max, проверяют монотонность и подходящий тип результата. Изменение создаёт новую версию с expectedVersionId. Несовместимая структура отклоняется.
- История доступна, конвертер использует конкретную версию. После изменения шкалы старый результат конвертации сохраняется.
- Каталог использует keyset pagination с непрозрачными AES-GCM курсорами, привязанными к пользователю, ресурсу, сортировке и фильтрам.
- CompatiblePositions готовит отображение совместимых позиций. Перенос задач предусмотрен этапом 5.

## Команды и результаты

Из services/assignment-service через Git Bash выполнена команда:

```bash
./gradlew test bootJar
```

Итог — BUILD SUCCESSFUL, 12 tests, 0 failures, 0 skipped. Проверены T02.05/06/08/09 и T01.01/02 в границах этого этапа, а также предыдущие тесты Assignment.

Проверки используют реальные PostgreSQL 17 и S3 Testcontainers и подписанные JWT. HTTP-тесты проверяют CRUD, деактивацию, права, историю, курсоры, неверные структуры и шкалы. SQL-тесты проверяют fresh+V1 upgrade, сохранность данных, повторный Flyway, FK и constraints. Два потока с одинаковым expectedStructureVersionId получают один успех и один 409.

Из корня выполнены:

```powershell
$c = @('--env-file', '.env', '-f', 'Docker/local/compose.yml', '--profile', 'assignment')
docker compose @c config --quiet
docker compose @c up -d --build assignment
Invoke-RestMethod http://127.0.0.1:8082/actuator/health/readiness
git diff --check
```

Compose config/build/up завершились с кодом 0, readiness вернула UP. Сервис обновился на существующем томе. Полный JUnit-отчёт расположен в services/assignment-service/build/reports/tests/test/index.html. Итоги и лог запуска приложены к этому отчёту.

## Исправленные провалы

Первая компиляция дополнительных тестов выявила private-доступ к контейнеру при попытке наследования тестового класса. Проверки перенесены в существующий интеграционный класс с общим жизненным циклом контейнеров и HTTP-заглушек.

Проверка следующей страницы получила 400, поскольку при единственной записи nextCursor отсутствовал. Добавлена вторая независимая запись в fixture. Проверка продолжения страницы и несовпадающего scope прошла.

## Принятые решения и границы

Названия тем и шкал ограничены 200 символами. SQL-схема использует public, согласованную на этапе 2. Никакие официальные структуры или шкалы не подставлялись. Сквозное закрепление шкалы в назначенной работе проверяется на этапе 9. Изменения Identity и Education в этом этапе отсутствуют. Коммиты и push не выполнялись.
