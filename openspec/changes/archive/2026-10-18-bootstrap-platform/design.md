## Context

Проект стартует с нуля: в каталоге только пустой Gradle-скелет от IntelliJ. Разработчик работает на macOS; на машине есть Docker, но инфраструктуру ставить локально нельзя. Мотивация — см. proposal.md. Поведение — см. specs/.

## Goals / Non-Goals

**Goals:**
- Один воспроизводимый способ запустить весь стек в dev и в prod через Docker.
- Сборка бэкенда и фронтенда внутри контейнеров (multi-stage), чтобы не требовать JDK/Node на хосте.
- Минимальный «вертикальный срез»: фронт → nginx → бэкенд → БД.

**Non-Goals:**
- Доменная модель ЖКУ, авторизация, CI/CD, автоматический выпуск сертификатов.

## Decisions

1. **Структура репозитория:** `backend/`, `frontend/`, `infra/nginx/`, `docker-compose.yml` (база) + `docker-compose.dev.yml` + `docker-compose.prod.yml` (overrides). Альтернатива — монолит с фронтом внутри Spring static: отвергнута, т.к. требуется отдельный nginx и независимая сборка фронта.
2. **Бэкенд:** Java 21, Spring Boot (версия — как в соседнем `furniture_calculator`, уточняется на apply), Gradle Kotlin DSL (соответствует существующему скелету), Spring Web, Data JPA, Actuator, Liquibase, драйвер PostgreSQL. Gradle wrapper переезжает в `backend/`.
3. **Профили:** `application.yml` (общее), `application-dev.yml`, `application-prod.yml`. Профиль обязателен: `spring.profiles.active=${SPRING_PROFILES_ACTIVE}` без значения по умолчанию, плюс проверка при старте. В prod пароль БД читается из переменной окружения без default (`${DB_PASSWORD}`) — отсутствие приводит к падению старта.
4. **БД:** образ `postgres:16`, именованный том; dev публикует порт на `127.0.0.1` только для отладки (нестандартный порт, чтобы не конфликтовать), prod порт не публикует. Миграции — Liquibase, `ddl-auto=validate`.
5. **Фронтенд:** React + TypeScript + Vite + antd. Сборка в multi-stage Dockerfile (node → статика), статика копируется в образ nginx. В dev — nginx проксирует и на Vite dev server (HMR) через `docker-compose.dev.yml`, чтобы разработка шла без пересборки; в prod — только собранная статика.
6. **nginx:** TLS 1.2+, редирект 80→443, `/api` → `backend:8080`, SPA fallback `try_files ... /index.html`, заголовки HSTS и nosniff. Конфиг через шаблон/volume.
7. **SSL:** dev — одноразовый контейнер (`alpine/openssl`) генерирует самоподписанный сертификат в именованный том при первом запуске; prod — каталог `infra/certs/` монтируется read-only, в `.gitignore`. Альтернатива mkcert отвергнута, так как требует установки на хост.
8. **Секреты:** `.env.example` в репозитории, реальные `.env`/`.env.prod` в `.gitignore`.
9. **Тесты:** тест контекста + интеграционный тест health на Testcontainers (Postgres в контейнере, не локально); тест профилей.

## Risks / Trade-offs

- [Самоподписанный сертификат вызывает предупреждение браузера в dev] → задокументировать; приемлемо для локальной разработки.
- [Testcontainers требует доступ к Docker-сокету] → Docker и так обязателен; описать в README.
- [Сборка в контейнере медленнее локальной] → кэш Gradle/npm в именованных томах/BuildKit cache mounts.
- [Версия Spring Boot может отличаться от доступной на момент apply] → проверить актуальную стабильную версию в начале реализации.

## Open Questions

- Домен и способ выпуска prod-сертификатов (Let's Encrypt/certbot или собственный) — решается отдельным change, не влияет на текущий состав задач, т.к. prod принимает сертификаты с хоста.
