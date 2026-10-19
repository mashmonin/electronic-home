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

## Период передачи показаний

Показания сдаются раз в период. Период идёт с 26-го числа по 25-е включительно и называется по месяцу, в котором
заканчивается (26 сентября — 25 октября — «октябрь»). Период и блокировку определяет бэкенд по своей дате: после
сдачи поле счётчика закрыто до начала следующего периода, повторная отправка отклоняется (`ALREADY_SUBMITTED`).

| Параметр | По умолчанию | Описание |
|---|---|---|
| `readings.period.closing-day` | `25` | Последний день периода (1–28). |
| `readings.period.enforced` | `true` (во всех профилях, включая `dev`) | При `false` повторная подача за период разрешена (для тестирования), экран показывает «Тестовый режим». |

Любой параметр можно переопределить переменной окружения: `READINGS_PERIOD_ENFORCED=false`,
`READINGS_PERIOD_CLOSING_DAY=25`. Чтобы отключить блокировку при тестировании, запустите бэкенд с `READINGS_PERIOD_ENFORCED=false`
(в docker compose — через `environment` сервиса `backend`).

## Квитанции

PDF-квитанции лежат в каталоге на томе Docker `receipts_data` (в контейнере `/data/receipts`), а записи о них —
в таблице `receipts` (счёт, месяц, ключ файла, размер, SHA-256). Файл хранится под случайным именем, имя от клиента
не используется. Для каждого счёта и месяца — одна квитанция; повторная публикация заменяет файл.

Опубликовать (токен — `RECEIPTS_ADMIN_TOKEN`; в `dev` это `dev-admin-token`; без токена публикация выключена, `403`):

```bash
curl -X PUT https://localhost/api/accounts/000000000001/receipts/2026-10 \
  -H 'X-Admin-Token: dev-admin-token' -H 'Content-Type: application/pdf' \
  --data-binary @receipt.pdf
```

Суммы квитанции — капитальный ремонт и коммунальные услуги в рублях — пока передаются при публикации необязательными
параметрами `capital_repair` и `utilities` (оба или ни одного, число с точкой, не более двух знаков после запятой):

```bash
curl -X PUT 'https://localhost/api/accounts/000000000001/receipts/2026-10?capital_repair=1842.50&utilities=6318.27' \\
  -H 'X-Admin-Token: dev-admin-token' -H 'Content-Type: application/pdf' --data-binary @receipt.pdf
```

Без параметров суммы не заданы (на вкладке — прочерк); замена файла без параметров сбрасывает прежние суммы. В БД суммы лежат
с источником (`MANUAL`) и временем записи: так их позже заполнит автоматический разбор PDF при загрузке
(источник `PARSED`) — он пока не реализован.

Список и скачивание: `GET /api/accounts/{номер}/receipts` и `GET /api/accounts/{номер}/receipts/{ГГГГ-ММ}`.
На вкладке «Квитанции» месяцы показаны аккордеоном, внутри панели — две суммы и ссылка «Скачать PDF».

| Параметр | По умолчанию | Описание |
|---|---|---|
| `receipts.storage.path` (`RECEIPTS_STORAGE_PATH`) | `./data/receipts`; в Docker `/data/receipts` | Каталог файлов. |
| `receipts.max-size` (`RECEIPTS_MAX_SIZE`) | `5MB` | Максимальный размер файла; nginx пропускает тело до 6 МБ (`client_max_body_size`), при изменении лимита поправьте и его. |
| `receipts.admin-token` (`RECEIPTS_ADMIN_TOKEN`) | нет (`dev`: `dev-admin-token`) | Токен публикации. |

**Резервное копирование.** Том `receipts_data` и БД нужно сохранять вместе: запись без файла отдаёт `404` и ошибку в
журнале. **Безопасность.** Авторизации жильцов пока нет: любой, кто знает номер счёта, может скачать его квитанцию.
Не публикуйте реальные квитанции, пока скачивание не защищено.

## Вход и роли

Вход по логину и паролю: `POST /api/auth/login` возвращает JWT (`Authorization: Bearer ...`), `GET /api/auth/me` — логин, роль и номера доступных счетов. Токен живёт 8 часов (`auth.jwt.ttl`), на фронтенде хранится в `sessionStorage` вкладки.

Роли и полномочия:

| Действие | ADMIN | OWNER | RESIDENT |
|---|---|---|---|
| Просмотр счёта, подача показаний | любой счёт | свои счета | свои счета |
| Список и скачивание квитанций | любой счёт | свои счета | запрещено |

«Свои счета» — строки таблицы `user_accounts`. Публикация квитанций по-прежнему идёт по `X-Admin-Token` без JWT.

Тестовая учётная запись есть только в `dev`: логин `owner`, пароль `Owner-dev-123`, роль `OWNER`, счёт `000000000001`.

Секрет подписи токенов — `JWT_SECRET` (не короче 32 байт). В `dev` задан по умолчанию, в `prod` обязателен: без него приложение не стартует (`docker-compose.prod.yml` тоже требует переменную).

В `prod` пользователей по умолчанию нет. Первые записи создаются вручную (хэш — BCrypt, например `htpasswd -nbBC 10 x 'пароль' | cut -d: -f2`):

```sql
INSERT INTO users (login, password_hash, role) VALUES ('admin', '$2a$10$...', 'ADMIN');
-- OWNER и RESIDENT нужно привязать к счёту:
INSERT INTO user_accounts (user_id, account_id)
SELECT u.id, a.id FROM users u, accounts a WHERE u.login = 'ivanov' AND a.number = '000000000001';
```

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
