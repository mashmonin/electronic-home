# Электронный дом

Веб-сервис передачи показаний ЖКУ. Бэкенд — Java 21 + Spring Boot + Gradle, БД — PostgreSQL,
фронтенд — React + TypeScript + Ant Design, веб-сервер — nginx с SSL. Разработка ведётся через
[OpenSpec](openspec/) (`/opsx:propose` → `/opsx:apply` → `/opsx:archive`).

## Требования

Только **Docker** (с Compose). JDK, Node.js, PostgreSQL и nginx на машине не нужны —
всё собирается и запускается в контейнерах.

## Dev (локальная разработка)

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build
```

- Приложение: https://localhost (самоподписанный сертификат — браузер предупредит, это нормально).
- Фронтенд работает через Vite с HMR: правки в `frontend/` подхватываются без пересборки.
- Бэкенд работает в профиле `dev`; после правок в `backend/`: `... up -d --build backend`.
- PostgreSQL доступен только с localhost: `127.0.0.1:5435` (БД/пользователь/пароль: `electronic_home`).

Остановка и полная очистка (контейнеры, тома, сертификат):

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml down -v
```

## Dev: бэкенд из IDE, фронтенд из терминала, БД в Docker

Для отладки: в Docker только PostgreSQL, остальное запускается на хосте (нужны JDK 21 и Node.js 22).

1. БД: `docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d postgres`
2. Бэкенд: открыть `backend/` как Gradle-проект в IntelliJ, запустить `ElectronicHomeApplication`
   с переменной окружения `SPRING_PROFILES_ACTIVE=dev` (без профиля приложение не стартует).
   Подключение к БД по умолчанию — `localhost:5435`.
3. Фронтенд: `cd frontend && npm install && npm run dev` → http://localhost:5173
   (запросы `/api` проксируются на `localhost:8080`).

Проверка: на странице должно быть «Сервис работает»; `curl localhost:8080/api/actuator/health` → `UP`.
В этом режиме nginx и HTTPS не участвуют — они проверяются полным dev-окружением выше.

## Prod (промышленная эксплуатация)

1. `cp .env.example .env.prod` и заполнить `DB_USER`, `DB_PASSWORD` (значений по умолчанию нет).
2. Положить сертификат и ключ в `infra/certs/` как `tls.crt` и `tls.key` (в git не попадают).
3. Запуск:

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml --env-file .env.prod up -d --build
```

Бэкенд работает в профиле `prod`; порт PostgreSQL наружу не публикуется, бэкенд снаружи недоступен —
весь трафик идёт через nginx (80 → редирект на 443).

## Тесты (тоже в контейнерах)

```bash
# backend (Testcontainers использует docker.sock)
docker run --rm -v "$PWD/backend":/app -w /app -v eh-gradle-cache:/home/gradle/.gradle \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -e TESTCONTAINERS_RYUK_DISABLED=true \
  gradle:9.5.1-jdk21 gradle test --no-daemon

# frontend
docker run --rm -v "$PWD/frontend":/app -w /app -v eh-npm-cache:/root/.npm \
  node:22-alpine sh -c 'npm ci && npm test'
```

## Структура

| Путь | Назначение |
|---|---|
| `backend/` | Spring Boot (профили `dev`/`prod`, Liquibase-миграции) |
| `frontend/` | React + antd |
| `infra/nginx/` | конфиги nginx и prod-образ; `infra/certs/` — prod-сертификаты |
| `openspec/` | спецификации и changes |
