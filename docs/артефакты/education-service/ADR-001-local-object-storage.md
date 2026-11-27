# ADR-001 — локальное S3-хранилище Silo вместо недоступного архивного MinIO

Дата: 2026-11-20. Статус: принято для local development в рамках этапа 1.

## Основание

Пользователь и план допускают Silo при явной фиксации замены. Это решение фиксирует замену;
Silo не выдаётся за upstream MinIO. Production-хранилище этим ADR не выбирается.

Реальные `docker pull` upstream Quay MinIO RELEASE.2025-09-07T16-13-09Z и
mc RELEASE.2025-08-13T08-35-41Z завершились HTTP 401 UNAUTHORIZED. Более ранние теги
дали тот же результат. Docker Hub minio/minio не используется.
Архивный upstream MinIO устарел и не обновляется; наличие доступного зеркала само по себе
не сделало бы его безусловно пригодным для production.

## Решение

Для локальной инфраструктуры и Testcontainers используется отдельный форк PGSTY Silo:

- `pgsty/silo:RELEASE.2026-11-12T00-00-00Z@sha256:c20d112390b9a9ce8c11b8af95ad35bcaa62f4759663c09c936cd8d2b511f329`;
- `pgsty/mc:RELEASE.2026-11-12T00-00-00Z@sha256:b532278b7a3747dbab6fe45eebb40a653efa704e6758e24e9d9ec54fc96c1800`.

Оба образа фактически скачаны, digests проверены через Docker. `silo --version` и `mc --version`
подтвердили RELEASE.2026-11-12T00-00-00Z. Это community-форк с AGPLv3, а не образ MinIO Inc.
Используются server и client одного релиза.

Имя Compose-сервиса `minio`, DNS `minio:9000`, переменные `MINIO_ROOT_*` и volume
`education_minio_data` сохранены для совместимости конфигурации. Названия не скрывают реализацию:
образ, README и этот ADR прямо указывают Silo. Приложение использует upstream Java SDK
`io.minio:minio:8.6.0` за `ObjectStorage`. Совместимость проверяется реальными S3-тестами.

## Проверки и последствия

Требуются PUT/GET/STAT/DELETE, multipart, missing object, anonymous 403, restricted credentials,
повторный bootstrap, volume/restart persistence, readiness при отказе и восстановлении.
Результаты перечислены в `verification.md`; первоначальный Quay failure сохранён отдельно.
Это проверка локального одноузлового сценария; совместимость распределённого production-кластера
и миграция существующих MinIO данных не заявляются. Существующих S3-данных в новом volume нет.

## Источники

- [Testcontainers: MinIO/Silo](https://java.testcontainers.org/modules/minio/).
- [Официальная матрица версий PGSTY](https://silo.pgsty.com/compatibility/versions/).
- [Релизы Silo](https://github.com/pgsty/silo/releases).
