## 1. JPA и репозитории

- [x] 1.1 `MeterReadingEntity`: `@GeneratedValue(IDENTITY)`, поле `isTest` (`is_test`), фабричный метод/конструктор для создания показания; `WaterMeterEntity`: чтение `isTest`
- [x] 1.2 Добавить `MeterReadingRepository` с `findFirstByMeterOrderByReadingDateDescIdDesc` и сохранением
- [x] 1.3 В `WaterMeterRepository` добавить запрос счётчиков квартиры по списку номеров с `PESSIMISTIC_WRITE` и `ORDER BY id`
- [x] 1.4 Добавить бин `Clock` (`Clock.systemDefaultZone()`) в конфигурацию

## 2. Сервисная логика

- [x] 2.1 Добавить `ReadingValidation` (значение обязательно, `>= 0`, не более 3 знаков, не более 9 целых разрядов, не меньше предыдущего)
- [x] 2.2 Добавить `InvalidReadingsException` и `ReadingError` (код и номер счётчика)
- [x] 2.3 Добавить `ReadingService.save(accountNumber, request)`: `404` для неизвестного счёта; проверки `EMPTY_REQUEST`, `DUPLICATE_METER`, `METER_NOT_FOUND`; блокировка счётчиков; проверка значений; сбор всех ошибок и откат при любой из них
- [x] 2.4 В `save` создавать показания с датой `LocalDate.now(clock)`, предыдущим — последним показанием счётчика и `is_test` счётчика; вернуть результат в порядке запроса

## 3. API

- [x] 3.1 Добавить DTO `ReadingsRequest`, `ReadingsResponse` (`snake_case`, лишние поля запроса игнорируются)
- [x] 3.2 Добавить `ReadingController` с `POST /api/accounts/{number}/readings` (`201`)
- [x] 3.3 Добавить обработку ошибок в контроллере: `InvalidReadingsException` → `400` с `errors`, `DataIntegrityViolationException` → `409` с записью причины в лог

## 4. Тесты

- [x] 4.1 `ReadingValidationTest` (юнит): допустимые и недопустимые значения, границы разрядов, равное предыдущему
- [x] 4.2 `ReadingsApiIT` (Testcontainers, `dev`, фиксированный `Clock`): одно показание и пакет, `201`, дата сервера, `previous`, поле `reading_date` из запроса игнорируется
- [x] 4.3 Тесты цепочки: показание поверх последнего, первое показание нового счётчика (`previous = null`), чтение `GET` после записи показывает новое первым
- [x] 4.4 Тесты ошибок: значение меньше предыдущего, `INVALID_VALUE`, `EMPTY_REQUEST`, `METER_NOT_FOUND`, `DUPLICATE_METER`, несколько ошибок списком, неизвестный счёт (`404`)
- [x] 4.5 Тесты атомарности (при ошибке в одном показании ничего не сохранено) и наследования `is_test`
- [x] 4.6 Тест параллельных запросов по одному счётчику: все завершаются без `5xx`, цепочка линейна
- [x] 4.7 Убедиться, что тесты убирают за собой созданные записи и не ломают остальные интеграционные тесты

## 5. Проверка

- [x] 5.1 Прогнать `./gradlew test` (весь набор бэкенда)
- [x] 5.2 Запустить бэкенд в `dev` на чистой БД и вызвать `POST /api/accounts/000000000001/readings` через curl: `201`, затем `GET` показывает новое показание, повторный вызов с меньшим значением даёт `400`
