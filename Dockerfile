FROM nginx:stable-alpine

ENV API_UPSTREAM=host.docker.internal:8080

COPY Docker/nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY Docker/nginx/html/ /usr/share/nginx/html/
