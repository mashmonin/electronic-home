## REMOVED Requirements

### Requirement: Перенос существующих квитанций
**Reason**: Старые квитанции перенесены в хранилище, одноразовый перенос при старте больше не нужен.
**Migration**: Перед выкладкой убедитесь, что квитанции окружения уже лежат в бакете квитанций; параметр `receipts.migration.source-path` (`RECEIPTS_MIGRATION_SOURCE_PATH`) и том `receipts_data` можно удалить.
