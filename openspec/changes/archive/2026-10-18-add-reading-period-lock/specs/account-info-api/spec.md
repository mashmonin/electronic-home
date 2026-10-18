## ADDED Requirements

### Requirement: Период передачи показаний в ответе
Ответ `GET /api/accounts/{number}` SHALL содержать поле `reading_period` с полями:
- `start` и `end` — первый и последний день текущего периода передачи (`ГГГГ-ММ-ДД`, определяются по дате сервера по правилам периода из спеки `meter-readings-api`);
- `next_start` — первый день следующего периода;
- `all_submitted` — `true`, если у каждого счётчика квартиры есть показание за текущий период (при отсутствии счётчиков `false`);
- `enforced` — значение параметра `readings.period.enforced`.

Каждый элемент `meters` SHALL содержать:
- `current_period_reading` — показание счётчика, дата которого входит в текущий период (если их несколько, последнее), в виде `{value, reading_date, previous}` той же формы, что элемент `readings`, либо `null`, если за текущий период показаний нет;
- `input_locked` — `true`, если параметр `enforced` включён и `current_period_reading` не `null`; иначе `false`.

Клиент MUST NOT вычислять период и блокировку сам: эти поля определяет сервер.

#### Scenario: Показания за период не сданы
- **WHEN** 18 октября 2026 запрашивается счёт с тестовыми данными (последние показания от 25 сентября)
- **THEN** `reading_period` — `start` `2026-09-26`, `end` `2026-10-25`, `next_start` `2026-10-26`, `all_submitted` `false`; у обоих счётчиков `current_period_reading` равен `null`, `input_locked` ложь

#### Scenario: Показания сданы
- **WHEN** оба счётчика получили показания 18 октября 2026 и параметр `enforced` включён
- **THEN** `all_submitted` `true`, у каждого счётчика `current_period_reading` содержит сданное значение, дату 2026-10-18 и `previous` — прежнее последнее показание, `input_locked` истина

#### Scenario: Сдан только один счётчик
- **WHEN** показание сдано только по «Тест 1»
- **THEN** `all_submitted` `false`, у «Тест 1» `input_locked` истина, у «Тест 2» ложь

#### Scenario: Проверка выключена
- **WHEN** параметр `enforced` выключен, а по счётчику есть показание за текущий период
- **THEN** `current_period_reading` заполнен, `input_locked` ложь, `reading_period.enforced` ложь

#### Scenario: Новый период
- **WHEN** прошёл день закрытия периода (26 октября) и по счётчику нет показаний с 26 октября
- **THEN** `current_period_reading` равен `null`, `input_locked` ложь, `reading_period.start` равен `2026-10-26`

#### Scenario: Квартира без счётчиков
- **WHEN** у квартиры счёта нет счётчиков
- **THEN** `reading_period` присутствует, `all_submitted` ложь, `meters` пуст
