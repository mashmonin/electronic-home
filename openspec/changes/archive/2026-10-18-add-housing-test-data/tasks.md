## 1. Колонка is_test

- [x] 1.1 Создать `backend/src/main/resources/db/changelog/changes/002-add-is-test-column.yaml`: колонка `is_test boolean NOT NULL DEFAULT false` в `addresses`, `accounts`, `owners`, `address_owners`, `account_owners`
- [x] 1.2 Подключить файл в `db.changelog-master.yaml`

## 2. Тестовые данные

- [x] 2.1 Создать `003-seed-test-data.yaml` с changeSet'ами `context: dev`: два собственника, адреса «ул. Тестовая, д. Тест, кв. 1» и «кв. 2», все с `is_test = true`
- [x] 2.2 В тех же changeSet'ах: счёт `000000000001` на кв. 1, связи собственников с адресами (кв. 1 — оба, кв. 2 — Тестовый Тест Тестович) и совладельцев счёта (оба), связи через подзапросы
- [x] 2.3 Добавить `rollback` (удаление строк с `is_test = true` в порядке связки → счета → адреса и собственники)
- [x] 2.4 Подключить файл в `db.changelog-master.yaml`

## 3. Профили

- [x] 3.1 Задать `spring.liquibase.contexts: dev` в `application-dev.yml` и `prod` в `application-prod.yml`

## 4. Тесты

- [x] 4.1 Интеграционный тест профиля `dev`: после миграций существуют ожидаемые собственники, адреса, счёт и все связи, у всех строк `is_test = true`
- [x] 4.2 Интеграционный тест с `spring.liquibase.contexts=prod`: таблицы пусты, колонка `is_test` есть
- [x] 4.3 Тест значения по умолчанию: запись без `is_test` получает `false` (при необходимости адаптировать `HousingSchemaIT`)

## 5. Проверка

- [x] 5.1 Прогнать `./gradlew test`: все тесты, включая `HealthEndpointIT` и `HousingSchemaIT`, проходят
- [x] 5.2 Запустить приложение в `dev` дважды на чистой БД: данные появились один раз, повторный старт не дублирует их
