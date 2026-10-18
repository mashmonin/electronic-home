## 1. БД

- [x] 1.1 Создать `backend/src/main/resources/db/changelog/changes/006-add-meter-verification-date.yaml`: колонка `verification_date date` (nullable) в `water_meters`
- [x] 1.2 В том же файле добавить changeSet с `context: dev`: `UPDATE` дат проверки тестовых счётчиков («Тест 1» — 2028-01-01, «Тест 2» — 2026-10-17, только `is_test`), с `rollback`; подключить файл в `db.changelog-master.yaml`

## 2. Бэкенд

- [x] 2.1 `WaterMeterEntity`: поле `verificationDate` (`verification_date`) и геттер
- [x] 2.2 `AccountResponse.Meter`: поле `verification_date` (`LocalDate`, `@JsonProperty`); `AccountService` заполняет его из сущности

## 3. Бэкенд: тесты

- [x] 3.1 `MeterSchemaIT`: дата проверки сохраняется без изменений, счётчик без даты допустим
- [x] 3.2 `MeterTestDataIT`: у «Тест 1» дата 2028-01-01, у «Тест 2» — 2026-10-17
- [x] 3.3 `AccountApiIT`: `verification_date` у тестовых счётчиков, `null` у счётчика без даты, имя поля в `snake_case`
- [x] 3.4 `MeterVerificationUpgradeIT` (Testcontainers, Liquibase API): применить первые 15 changeSet'ов до `005`, затем остальные; у уже созданных тестовых счётчиков появились даты

## 4. Фронтенд

- [x] 4.1 Добавить `frontend/src/readings/verification.ts`: `isExpired(date, today)` (строго раньше локальной сегодняшней даты) и `formatVerificationDate` (`ГГГГ-ММ-ДД` → `ДД.ММ.ГГГГ`)
- [x] 4.2 Расширить `AccountMeter` полем `verification_date`, `Meter` — полями `verificationDate` и `expired`; `buildRisers(meters, today)` заполняет их
- [x] 4.3 `MeterReadingsPage` передаёт свой `today` в `buildRisers`
- [x] 4.4 `ReadingField`: строка «Дата проверки: ДД.ММ.ГГГГ» (или «—») и красная метка «Просрочено» при истёкшем сроке; ввод и отправка не блокируются

## 5. Фронтенд: тесты

- [x] 5.1 `verification.test.ts`: раньше, в день даты, позже, смена месяца и года, поздний вечер локального времени, отсутствие даты, формат даты
- [x] 5.2 `risers.test.ts`: флаги `expired` и `verificationDate`, счётчик без даты
- [x] 5.3 Тесты страницы (`today = 18.10.2026`): «Тест 1» без метки, «Тест 2» с меткой «Просрочено», день окончания срока, нет даты, поле без счётчика, просрочка не мешает отправке
- [x] 5.4 Обновить существующие фикстуры и тесты под новые поля

## 6. Проверка

- [x] 6.1 Прогнать `./gradlew test`, `npm run test` и `npm run build`
- [x] 6.2 Запустить бэкенд в `dev` на БД, созданной до этого изменения (или на чистой), и фронтенд на отдельном порту; через запросы убедиться, что `GET /api/accounts/000000000001` отдаёт `verification_date` обоих счётчиков, а в экране у «Тест 2» видна метка «Просрочено»
