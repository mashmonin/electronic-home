## Why

Квитанции давно хранятся в S3, и старые файлы с тома уже перенесены (в dev проверено по SHA-256, локальные копии удалены). Одноразовый механизм переноса при старте больше не нужен, а вместе с ним том `receipts_data`, параметр `receipts.migration.source-path` и их документация.

## What Changes

- Удаляется компонент `ReceiptFileMigration` (перенос файлов квитанций с тома в S3 при старте) и его тест.
- Из `ReceiptProperties` убирается вложенная настройка `migration`; из `application-dev.yml` и `application-prod.yml` — параметр `receipts.migration.source-path` (переменная `RECEIPTS_MIGRATION_SOURCE_PATH`).
- Из `docker-compose.yml` убираются переменная `RECEIPTS_MIGRATION_SOURCE_PATH`, монтирование тома `receipts_data` в бэкенд и сам том.
- Из README убираются раздел «Перенос старых квитанций» и строка параметра.
- **BREAKING (только для окружений со старым томом):** если в каком-то окружении перенос ещё не выполнялся, файлы с тома `receipts_data` после этого изменения не попадут в S3. Перед выкладкой убедитесь, что квитанции этого окружения уже в хранилище.

## Capabilities

### New Capabilities

Нет.

### Modified Capabilities
- `object-storage`: удаляется требование «Перенос существующих квитанций».
- `receipts`: из требований «Файловое хранилище» и «Конфигурация квитанций» убирается перенос и параметр `receipts.migration.source-path`.

## Impact

- Backend: удаление `ReceiptFileMigration.java` и `ReceiptFileMigrationIT.java`, правки `ReceiptProperties.java`, `application-dev.yml`, `application-prod.yml`.
- Infra: `docker-compose.yml` (переменная, монтирование, том).
- Документация: `README.md`, основные спеки `object-storage` (в том числе описание назначения) и `receipts`.
- Тесты: тест переноса удаляется; остальные не меняются.
- Вне рамок: удаление самих томов Docker с данными (`docker volume rm …_receipts_data` делается вручную, после проверки), миграции БД, бакеты и ключи S3.
