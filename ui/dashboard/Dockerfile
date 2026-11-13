# HPG dashboard: static files (no build step) served by nginx, which also forwards /api and /oauth2
# to the API gateway. GATEWAY_URL defaults to the Compose service name.
FROM nginx:1.27-alpine
ENV GATEWAY_URL=http://api-gateway:8080
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY index.html favicon.svg /usr/share/nginx/html/
COPY css /usr/share/nginx/html/css
COPY js /usr/share/nginx/html/js
EXPOSE 8090
