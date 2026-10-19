## 1. Хранилище учётных записей

- [x] 1.1 Миграция `009-create-users.yaml`: таблицы `users` (CHECK по роли, уникальный индекс по `lower(login)`, `is_test`) и `user_accounts` (уникальная пара, внешние ключи, `is_test`); подключить в `db.changelog-master.yaml`
- [x] 1.2 Сгенерировать BCrypt-хэш пароля тестовой записи и добавить миграцию `010-seed-test-user.yaml` (контекст `dev`: пользователь `owner`, роль `OWNER`, привязка к счёту `000000000001`, откат по `is_test`)
- [x] 1.3 `UserDirectory` на `JdbcClient` вместо JPA-сущностей (поиск по `lower(login)`, номера счетов пользователя и привязка)
- [x] 1.4 `UserSchemaIT`: уникальность логина без учёта регистра, недопустимая роль, отсутствие пароля в открытом виде, отсутствие `is_test`-записей в контексте `prod`

## 2. Аутентификация на бэкенде

- [x] 2.1 Добавить зависимости `spring-boot-starter-security`, `spring-boot-starter-oauth2-resource-server` (и `spring-security-test` для тестов) в `build.gradle.kts`
- [x] 2.2 Свойства `auth.jwt.secret` / `auth.jwt.ttl` / `auth.jwt.issuer` (`AuthProperties`), значения для `dev` в `application-dev.yml`, `JWT_SECRET` без значения по умолчанию в `application-prod.yml`; fail-fast на секрет короче 32 байт
- [x] 2.3 Бины `JwtEncoder`/`JwtDecoder` (HS256) и `TokenService` с выпуском токена с claims `iss`, `sub`, `iat`, `exp`, `roles`
- [x] 2.4 `AuthService` и `AuthController`: `POST /api/auth/login` (BCrypt, сравнение с фиктивным хэшем для неизвестного логина, единый `401`, валидация `400`) и `GET /api/auth/me`
- [x] 2.5 `SecurityConfig`: stateless, csrf off, cors, `permitAll` для login/health/публикации квитанций, остальное `/api/**` — `authenticated`; конвертер claim `roles` → `ROLE_*`; проверка `enabled` при каждом запросе (токен отключённой записи → `401`)

## 3. Авторизация по роли и счёту

- [x] 3.1 Включить `@EnableMethodSecurity`, бин `AccountAccess.canAccess(authentication, number)` (ADMIN — всегда, OWNER/RESIDENT — по `user_accounts`, без обращения к таблице счетов)
- [x] 3.2 `@PreAuthorize` на `AccountController.get`, `ReadingController.save`, `ReceiptController.list/download` (квитанции — только `ADMIN`/`OWNER`); публикацию квитанции не менять
- [x] 3.3 `GET /api/auth/me` для `ADMIN` возвращает все номера счетов по возрастанию
- [x] 3.4 Обновить `DevCorsConfig` (проверить, что `Authorization` проходит CORS в `dev`)

## 4. Тесты бэкенда

- [x] 4.1 Хелпер `TestTokens`/`TestUsers` для создания пользователей и токенов нужной роли в IT-тестах
- [x] 4.2 Перевести `AccountApiIT`, `ReadingsApiIT`, `ReadingPeriod*IT`, `ReceiptsApiIT`, `ReceiptsPublishingDisabledIT` на аутентифицированные запросы
- [x] 4.3 `AuthApiIT`: успешный вход, неверный пароль/логин/отключённая запись (одинаковое тело), регистр логина, `400`, состав claims, просроченный и подделанный токен, `me` для собственника и администратора, отсутствие `Set-Cookie`
- [x] 4.4 `AccessControlIT`: матрица ролей из спеки `authorization`, порядок `401 → 403 → 404`, единый `403` для несуществующего счёта у `OWNER`, отключение записи при действующем токене, публикация квитанции по `X-Admin-Token` без JWT, открытый `/api/actuator/health`
- [x] 4.5 Тест старта `prod` без `JWT_SECRET` и с коротким секретом (по образцу `ProfileGuardTest`)

## 5. Фронтенд: вход и сессия

- [x] 5.1 `auth/session.ts`: хранение токена в `sessionStorage` (с защитой от недоступности хранилища), чтение/сброс
- [x] 5.2 `auth/apiFetch.ts`: добавление `Authorization`, сброс сессии и сообщение «Сессия истекла. Войдите снова» при `401`, различение `403`; перевести `fetchAccount`, `readingsApi`, `receiptsApi` на него
- [x] 5.3 `auth/AuthProvider.tsx` и `authApi.ts` (`login`, `fetchMe`): состояние `anonymous | authenticated`, восстановление сессии после перезагрузки
- [x] 5.4 `auth/LoginPage.tsx` на компонентах antd: поля «Логин»/«Пароль», кнопка «Войти» (недоступна при пустых полях и во время запроса), Enter, сообщения об ошибках из спеки `login-screen`, адаптивная вёрстка
- [x] 5.5 `App.tsx`: условный рендер `LoginPage` / `MeterReadingsPage`; `MeterReadingsPage` определяет счёт по `/api/auth/me` (параметр `account` только для `ADMIN`), сообщение «К вашей учётной записи не привязан лицевой счёт»
- [x] 5.6 `AppHeader`: логин и роль в меню профиля, действие «Выйти», скрытие вкладки «Квитанции» для `RESIDENT`, сообщение «Недостаточно прав» при `403`
- [x] 5.7 `ReceiptsTab`: скачивание через `apiFetch` → blob → временная ссылка вместо `<a href>`
- [x] 5.8 Убрать `VITE_DEFAULT_ACCOUNT_NUMBER` (`.env.development`), `resolveAccountNumber` берёт счёт из `/api/auth/me` (параметр `account` только для `ADMIN`); обновить существующие тесты экрана

## 6. Тесты фронтенда

- [x] 6.1 Тесты `session`, `apiFetch` (заголовок, `401`, `403`) и `authApi`
- [x] 6.2 Тесты `LoginPage`: пустые поля, успех, неверные данные (пароль очищается, логин остаётся), ошибка связи, Enter
- [x] 6.3 Тесты `App`/`AppHeader`: вход → экран показаний, выход, перезагрузка с сохранённой сессией, скрытие вкладки для `RESIDENT`, `ADMIN` с `?account=`
- [x] 6.4 Обновить `ReceiptsTab.test.tsx` под скачивание через blob

## 7. Инфраструктура и документация

- [x] 7.1 Добавить `JWT_SECRET: ${JWT_SECRET:?JWT_SECRET is required}` в `docker-compose.prod.yml`
- [x] 7.2 README: тестовый пользователь (логин/пароль для `dev`), переменная `JWT_SECRET`, ручное создание первых учётных записей в `prod` (SQL с BCrypt-хэшем), матрица ролей
- [x] 7.3 Прогнать `./gradlew test` и `npm test`/`npm run build`, затем проверить вход тестовым пользователем в запущенном `dev`-окружении (проверка 401 без токена, 403 на чужой счёт, выход)
