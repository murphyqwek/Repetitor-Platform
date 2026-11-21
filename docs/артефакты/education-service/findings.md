# Результаты аудита

- Фактический путь — `Docker/local/compose.yml`, регистр важен для Linux.
- Java хоста 17.0.12; существующий Gradle Wrapper 9.8.2 реально запускается на Java 17.
- В sandbox Gradle пытается писать в `C:\.gradle`, доступ запрещён. С повышенными правами wrapper работает с пользовательским кешем.
- Docker Desktop первоначально был выключен; запущен скрыто. Docker Engine 28.5.2, Compose 2.40.3.
- Существующие контейнеры postgres/redis/kafka запущены; их конфигурацию не менять.
- Порты существующей инфраструктуры: nginx 8090, postgres 5432, redis 6379, kafka 9092. Для Education выбрать 8081; MinIO 9000/9001 только loopback.
- Docker pull Quay MinIO RELEASE.2025-09-07T16-13-09Z и mc RELEASE.2025-08-13T08-35-41Z: HTTP 401 UNAUTHORIZED.
- Проверены также Quay MinIO RELEASE.2025-04-22T22-12-26Z и mc RELEASE.2025-04-16T18-13-26Z: тот же 401.
- Нельзя считать эти образы доступными. Silo и локальные minio:local/is-lab3-check-minio:local не подставлять молча.
- eclipse-temurin:17-jdk и :17-jre успешно скачаны. Digests соответственно sha256:fbe9d94debbe24e529acfe235b98f065edea050a2dc00129b7d86261ce3248c8 и sha256:fb2cec8da84e3f086b59e29bf432d372e396fe0c247adcfb89303a28c2e313c0.
- Архивный MinIO допускается только для local; production выбор остаётся отдельным решением.

## Разрешение блокера registry

В соответствии с явным допуском пользователя на Silo с фиксацией решения принят ADR-001.
Оба образа `pgsty/silo` и `pgsty/mc` RELEASE.2026-11-12T00-00-00Z успешно скачаны,
версии CLI и digests проверены. Compose и тесты используют одинаковые digest-pinned образы.
Silo обозначен как отдельный форк, без заявления о production-готовности. См. ADR-001 и verification.md.

## Проверенная внешняя документация

- https://docs.spring.io/spring-boot/4.1/gradle-plugin/index.html — Gradle 8.14+ / 9.x.
- https://java.testcontainers.org/modules/minio/ — исчезновение MinIO Docker Hub, Silo является отдельным форком.
- https://github.com/minio/minio-java/blob/8.6.0/api/src/main/java/io/minio/MinioClient.java — API SDK 8.6.0.

## Этап 2

Проверенные исходники и реальный Identity контракт описаны в ADR-002. Identity принимает cookie access_token,
не Bearer header. GET /users/me доступен для своего профиля, blocked actor получает 403 ещё до контроллера.
До расширения общего UUID lookup не было. Directory требовал type+fio, /students/{id} ограничен parent-child связью.
Нет канала событий блокировки; Education проверяет текущий профиль на каждом HTTP API запросе без кеша.
Первичный прогон: 53 теста прошли, включая production JWT decoder и реальный HTTP client к локальному stub.
Проверен Compose с public-key mount, новая БД не создавалась: сохранён education_postgres_data и существующий V1.
Ранее отслеживаемый dev_secrets/jwt_private.pem не добавлялся и не использовался для тестовых подписей.

После согласования ADR-002 добавлен точечный lookup в каталоге Identity. Полные окончательные suites:
Education 64, Identity 43, оба bootJar успешны. Identity lookup проверен через настоящий HTTP с подписанным
cookie JWT и реальной изолированной PostgreSQL; Education — через настоящий HTTP client и контрактный stub.
Изначальное ограничение закрыто без новых бизнес-маршрутов Education.

## Этап 3

Контракт: course + POST/GET/PATCH/GET list; ENROLLED и student membership появляются на этапе 4,
DELETE — на этапе 16. Нельзя создавать course_student заранее или подменять отсутствие реализации пустой страницей.
Существующие CursorCodec, CurrentUser, PermissionService и IdentityClient повторно использованы.
PostgreSQL UUID ordering отличается от Java UUID.compareTo на части значений: тесты сравнивают с SQL ORDER BY,
а не с Java sort. createdAt обрезан до PostgreSQL microseconds; cursor продолжает работу после удаления anchor.
PATCH использует PESSIMISTIC_WRITE; реальный тест ждёт pg_stat_activity Lock перед commit DELETING и получает 404.
Native query явно CAST nullable UUID/timestamptz, чтобы PostgreSQL не терял тип параметра на первой странице.
Все 81 тест Education прошли; actual Compose upgrade сохранил PostgreSQL container ID и volume, probes 200.
Identity не менялся в этапе 3. Логи и ограничения — stage-3-verification.md.

Документация реализации:
- https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html
- https://docs.spring.io/spring-framework/reference/integration/rest-clients.html

## Этап 4

В Jackson 3 отсутствующий primitive record field confirmConflicts вызвал 400; необязательное поле сделано Boolean.
Обязательный canSendMessages остаётся @NotNull Boolean. Старые Flyway V1→V2 тесты закреплены target=2;
отдельный upgrade V2→V3 проверяет сохранность course и constraints/defaults/indexes членства.
Не расширять visible() для mutations: право читать по membership не означает право менять чужой курс,
включая пользователя с ролями TEACHER+STUDENT. Поэтому managed/managedForUpdate отделены от visible.
Row lock parent course сериализует mutations и защищает от DELETING; HTTP lookup не удерживает SQL write transaction.
95 тестов прошли, V3 применена к тому же local PostgreSQL container/volume, probes 200 UP.
Проверка конфликтов расписания остаётся этапом 7. Результаты — stage-4-verification.md.

## Этап 5

Месяц — обязательный YYYY-MM, без неявного now из timezone JVM. CalendarService строит оба края через
Europe/Moscow. Overlap, а не startsAt в пределах месяца: начавшийся до полуночи урок должен присутствовать
в следующем месяце, если заканчивается позже его начала. Концы полуоткрытые; касание границы не пересечение.
Длительность проверяется Duration.compareTo до нормализации микросекунд; дробные секунды не округляются к минутам.
SQL CHECK дублирует критичную границу 5–300 минут для целостности БД.
Personal calendar использует OR + EXISTS, а не join membership, поэтому own+enrolled не дублирует урок.
ADMIN не является флагом глобального персонального календаря. Mutations переиспользуют parent course lock.
Identity HTTP fixture выделен из CourseHttpTest в отдельный test-only класс и повторно использован расписанием.
47 целевых и 111 полных тестов прошли с первого запуска; V4 на том же PG volume и probes подтверждены.
reason/confirmConflicts — только согласованные поля на этапе 5; outbox/checker не готовы до этапа 7.
Официальные источники: https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/time/YearMonth.html
и https://www.postgresql.org/docs/17/datatype-datetime.html. Итог — stage-5-verification.md.

## Этап 6

Нельзя фильтровать rules только по start/stop относительно месяца: перенос может прийти из будущей
или уже остановленной серии. Scope SQL выбирает доступные ACTIVE course rules, exceptions batch —
original_date window OR actual new interval. Генератор обходит только month (+previous day),
подавляет исходный ключ любой exception, отдельно добавляет допустимые RESCHEDULED по actual overlap.
Stop проверяется только по originalDate; CANCELLED хранится постоянно; UNIQUE + parent lock не дают duplicate.
SQL predicate проверяет уникальность/размерность/диапазон weekday массива; Hibernate Short[] реально прошёл JPA/PG roundtrip.
Расчёт в UTC/LA/Tokyo и настоящий HTTP/PG suite с JVM America/Los_Angeles прошли без смены Moscow результатов.
GET rules следует общей cursor pagination REST, включая stopped, limits/actor/course binding и deleted anchor.
128 tests, bootJar/Compose/config/probes успешны, V5 на прежнем PG volume. Итог stage-6-verification.md.
Документация: https://docs.hibernate.org/orm/7.4/userguide/html_single/#basic-array
и https://www.postgresql.org/docs/17/functions-array.html. Checker/outbox остаются этапу 7.

## Этап 7

Два HTTP-теста с серией, записанной напрямую в PostgreSQL, выявили сдвиг SQL TIME при чтении
на JVM в часовом поясе UTC+3. Hibernate теперь использует прямой JDBC LocalTime. SQL 17:00
московского правила даёт 14:00Z и конфликт на 14:00Z. Тесты проверяют это на реальных строках БД.
Границы проверки при зачислении описаны в stage-7-plan.md. При отказе Kafka SQL-транзакция
publisher откатывается. Повтор после ACK до commit реально доставляет два сообщения с одним eventId.
