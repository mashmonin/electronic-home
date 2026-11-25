import { UploadOutlined } from '@ant-design/icons'
import { Alert, Button, Card, Flex, Progress, Tag, Typography, Upload } from 'antd'
import type { UploadFile } from 'antd'
import { useCallback, useEffect, useState } from 'react'
import { usePolling } from '../requests/usePolling'
import { type Batch, type BatchDetail, fetchBatch, fetchBatches, uploadStatement } from './batchesApi'
import { formatReceiptMonth } from './receiptFormat'

const POLL_MS = 2000

const STATUS_LABELS: Record<Batch['status'], string> = {
  queued: 'В очереди',
  running: 'Формируется',
  done: 'Готов',
  failed: 'Ошибка',
}
const STATUS_COLORS: Record<Batch['status'], string> = { queued: 'default', running: 'processing', done: 'success', failed: 'error' }
const ITEM_LABELS = { created: 'создана', replaced: 'заменена', skipped: 'пропущена', failed: 'ошибка' } as const

const active = (status: Batch['status']) => status === 'queued' || status === 'running'

// "Создано 137, заменено 0, пропущено 5, ошибок 0"
function summary(batch: Batch): string {
  return `Создано ${batch.created}, заменено ${batch.replaced}, пропущено ${batch.skipped}, ошибок ${batch.failed}`
}

function formatWhen(iso: string): string {
  const date = new Date(iso)
  return Number.isNaN(date.getTime())
    ? ''
    : date.toLocaleString('ru-RU', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' })
}

// The card of the administrator: uploads the statement of the charges, shows how the receipts are made and the recent batches.
export default function StatementUploadCard() {
  const [files, setFiles] = useState<UploadFile[]>([])
  const [uploading, setUploading] = useState(false)
  const [failure, setFailure] = useState<string | null>(null)
  const [batches, setBatches] = useState<Batch[]>([])
  const [opened, setOpened] = useState<number | null>(null)
  const [detail, setDetail] = useState<BatchDetail | null>(null)

  const reload = useCallback(async () => {
    const result = await fetchBatches()
    if (result.status === 'ok') setBatches(result.batches)
  }, [])

  useEffect(() => {
    void reload()
  }, [reload])

  const refreshOpened = useCallback(async () => {
    if (opened === null) return
    const result = await fetchBatch(opened)
    if (result.status === 'ok') setDetail(result.batch)
  }, [opened])

  useEffect(() => {
    setDetail(null)
    void refreshOpened()
  }, [refreshOpened])

  // while a batch is being made, both the list and the opened batch follow it
  const running = batches.some((batch) => active(batch.status)) || (detail !== null && active(detail.status))
  usePolling(
    () => {
      void reload()
      void refreshOpened()
    },
    POLL_MS,
    running,
  )

  const upload = async () => {
    const file = files[0]?.originFileObj
    if (!file || uploading) return
    setUploading(true)
    setFailure(null)
    const result = await uploadStatement(file)
    setUploading(false)
    if (result.status === 'accepted') {
      setFiles([])
      setOpened(result.batch.id)
      await reload()
    } else {
      setFailure(result.status === 'refused' ? result.message : 'Не удалось загрузить ведомость. Попробуйте позже')
    }
  }

  return (
    <Card title="Загрузить ведомость">
      <Flex vertical gap={16}>
        <Typography.Text type="secondary">
          Ведомость начислений (.xls): по ней для каждой квартиры будет сформирован PDF и опубликован собственнику.
        </Typography.Text>
        <Flex gap={12} wrap align="center">
          <Upload
            accept=".xls,application/vnd.ms-excel"
            maxCount={1}
            fileList={files}
            beforeUpload={() => false}
            onChange={({ fileList }) => setFiles(fileList.slice(-1))}
            disabled={uploading}
          >
            <Button icon={<UploadOutlined />} disabled={uploading}>
              Выбрать ведомость
            </Button>
          </Upload>
          <Button type="primary" disabled={files.length === 0 || uploading} loading={uploading} onClick={() => void upload()}>
            Загрузить
          </Button>
        </Flex>
        {failure && <Alert type="error" showIcon message={failure} />}

        {batches.length > 0 && (
          <Flex vertical gap={8} aria-label="Последние пакеты">
            <Typography.Text strong>Последние загрузки</Typography.Text>
            {batches.map((batch) => (
              <Flex key={batch.id} vertical gap={6} data-testid={`batch-${batch.id}`} style={{ padding: '8px 0', borderTop: '1px solid #f0f0f0' }}>
                <Flex gap={8} align="center" wrap>
                  <Typography.Text strong>{formatReceiptMonth(batch.month)}</Typography.Text>
                  <Tag color={STATUS_COLORS[batch.status]} style={{ marginInlineEnd: 0 }}>
                    {STATUS_LABELS[batch.status]}
                  </Tag>
                  <Typography.Text type="secondary">{formatWhen(batch.created_at)}</Typography.Text>
                  <Button type="link" size="small" onClick={() => setOpened(opened === batch.id ? null : batch.id)}>
                    {opened === batch.id ? 'Скрыть' : 'Подробнее'}
                  </Button>
                </Flex>
                {active(batch.status) && (
                  <Flex vertical gap={2}>
                    <Progress percent={batch.total ? Math.round((batch.processed / batch.total) * 100) : 0} size="small" />
                    <Typography.Text type="secondary">
                      Обработано {batch.processed} из {batch.total}
                    </Typography.Text>
                  </Flex>
                )}
                {!active(batch.status) && <Typography.Text>{summary(batch)}</Typography.Text>}
                {batch.status === 'failed' && batch.error && <Alert type="error" showIcon message={batch.error} />}
                {opened === batch.id && detail?.id === batch.id && <BatchItems batch={detail} />}
              </Flex>
            ))}
          </Flex>
        )}
      </Flex>
    </Card>
  )
}

// The accounts that got no receipt (skipped or failed) with the reason; the others are only counted.
function BatchItems({ batch }: { batch: BatchDetail }) {
  const problems = batch.items.filter((item) => item.status === 'skipped' || item.status === 'failed')
  if (problems.length === 0) return <Typography.Text type="secondary">Все квитанции сформированы</Typography.Text>
  return (
    <Flex vertical gap={2} data-testid="batch-problems">
      {problems.map((item) => (
        <Typography.Text key={item.account_number}>
          Л/с {item.account_number} — {ITEM_LABELS[item.status]}
          {item.reason ? `: ${item.reason}` : ''}
        </Typography.Text>
      ))}
    </Flex>
  )
}
