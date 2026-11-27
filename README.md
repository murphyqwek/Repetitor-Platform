# Локальный запуск Docker

```powershell
docker compose --env-file .env -f .\Docker\local\compose.yml up -d --build
```

Nginx доступен по адресу http://localhost:8090:

- `/` — статические файлы, пока страница-заглушка из `Docker/nginx/html`.
- `/api/` — запросы к API с сохранением пути и параметров, например `/api/v1/auth/csrf`.
- Остальные пути возвращают `index.html` для маршрутизации будущего SPA.

Identity API нужно запустить отдельно на хосте на порту `8080` с профилем `dev`
(для локального HTTP в нём отключён флаг Secure у cookies).
Другой адрес API можно задать переменной `API_UPSTREAM` в `.env`, например
`API_UPSTREAM=host.docker.internal:8081`. Указывайте `host:port` без `http://`.
После изменения пересоздайте контейнер nginx командой запуска выше.

Когда появится сборка фронтенда, замените в `Dockerfile` источник статики
`Docker/nginx/html/` на каталог с результатом сборки и пересоберите образ.
