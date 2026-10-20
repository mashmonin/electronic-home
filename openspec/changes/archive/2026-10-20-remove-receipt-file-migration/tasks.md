## 1. Код и конфигурация

- [x] 1.1 Удалить `ReceiptFileMigration` и `ReceiptFileMigrationIT`; убрать `Migration` из `ReceiptProperties`
- [x] 1.2 Убрать `receipts.migration.source-path` из `application-dev.yml` и `application-prod.yml`
- [x] 1.3 `docker-compose.yml`: убрать переменную `RECEIPTS_MIGRATION_SOURCE_PATH`, монтирование и том `receipts_data`

## 2. Документация

- [x] 2.1 README: убрать раздел «Перенос старых квитанций» и строку параметра `receipts.migration.source-path`
- [x] 2.2 `openspec validate remove-receipt-file-migration --strict`
