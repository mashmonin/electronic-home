## 1. Репозиторий и бэкенд

- [x] 1.1 Удалить заглушку `src/main/java/org/example/Main.java`, перенести Gradle-проект (wrapper, `settings.gradle.kts`) в `backend/`
- [x] 1.2 Настроить `backend/build.gradle.kts`: Java 21, Spring Boot, Web, Data JPA, Actuator, Liquibase, PostgreSQL, тесты + Testcontainers
- [x] 1.3 Создать приложение (пакет `com.example.electronichome`) и пустой мастер-changelog Liquibase
- [x] 1.4 Добавить `application.yml`, `application-dev.yml`, `application-prod.yml` и проверку обязательности профиля
- [x] 1.5 Настроить health-эндпоинт: статус БД, минимальный вывод в prod
- [x] 1.6 Тесты: старт без профиля падает, prod без пароля БД падает, health UP с Testcontainers

## 2. Фронтенд

- [x] 2.1 Создать `frontend/` (Vite + React + TypeScript) и подключить antd
- [x] 2.2 Стартовая страница с индикатором состояния бэкенда (antd), обработка ошибки связи
- [x] 2.3 Тест компонента стартовой страницы (UP / ошибка)

## 3. Docker и nginx

- [x] 3.1 Dockerfile бэкенда (multi-stage, сборка Gradle внутри контейнера)
- [x] 3.2 Dockerfile фронтенда/nginx (multi-stage: node → nginx со статикой)
- [x] 3.3 `infra/nginx`: конфиг с TLS, редиректом 80→443, `/api`-прокси, SPA fallback, HSTS и nosniff
- [x] 3.4 `docker-compose.yml` (общее) + `docker-compose.dev.yml` + `docker-compose.prod.yml`, именованные тома, внутренняя сеть
- [x] 3.5 Генерация самоподписанного сертификата в dev через контейнер; каталог prod-сертификатов в `.gitignore`
- [x] 3.6 `.env.example`, `.gitignore` для секретов, ключей и артефактов сборки

## 4. Проверка и документация

- [x] 4.1 Поднять dev-окружение командой из README, проверить `https://localhost` и `/api` health
- [x] 4.2 Поднять prod-конфигурацию с тестовыми сертификатами, проверить закрытый порт БД и редирект HTTP→HTTPS
- [x] 4.3 README: команды запуска, остановки и очистки, требования (только Docker)
- [x] 4.4 `openspec validate bootstrap-platform` без ошибок
